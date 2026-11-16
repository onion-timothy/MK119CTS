#!/usr/bin/env python3
"""
Error occupancy 모델 검증 스크립트.

errors.html 의 Expected occupancy 파이차트가 쓰는 Monte-Carlo 모델을 Python 으로 그대로 돌리고,
서버의 io.log 에 남은 실제 "System error (de)activated" 이벤트와 비교한다.

사용 예:
    # device 34, 전체 로그 범위에서 D=30 활성만 추려서 모델/실측 비교
    python3 tools/error_occupancy_check.py --device 34

    # 특정 구간
    python3 tools/error_occupancy_check.py --device 34 \
        --since "2026-10-20 11:00:00" --until "2026-10-20 17:00:00"

    # 서버가 뜨지 않았을 때 — API 대신 수동 config 입력
    python3 tools/error_occupancy_check.py --device 34 \
        --config DISCARD:42:120:30 TIMEOUT:20:60:30 \
                 GARBAGE:20:60:30 DISCONNECT:20:60:30

    # 수렴 추이도 보고 싶을 때
    python3 tools/error_occupancy_check.py --device 34 --convergence
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
import urllib.error
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable


# ───────────────────────── config / 모델 ─────────────────────────

SYSTEM_MODES = ("DISCARD", "TIMEOUT", "GARBAGE", "DISCONNECT")


@dataclass(frozen=True)
class ErrorCfg:
    mode: str
    frequency: int   # %
    interval: int    # s
    duration: int    # s

    @property
    def p(self) -> float:
        return max(0, min(100, self.frequency)) / 100.0

    @property
    def rho(self) -> float:
        """Lost-call 모델의 offered load (참고용)."""
        if self.p <= 0 or self.interval <= 0:
            return 0.0
        return self.p * self.duration / self.interval

    def duty_solo(self) -> float:
        """에러가 혼자 있을 때의 duty (상한, 간섭 미반영)."""
        if self.p <= 0:
            return 0.0
        return self.duration * self.p / (self.duration * self.p + self.interval)


def is_system(cfg: ErrorCfg) -> bool:
    return cfg.mode in SYSTEM_MODES


# ───────────────────────── Monte-Carlo (JS 와 동일) ─────────────────────────

def mulberry32(seed: int):
    a = [seed & 0xFFFFFFFF]
    def rnd() -> float:
        a[0] = (a[0] + 0x6D2B79F5) & 0xFFFFFFFF
        t = a[0]
        t = ((t ^ (t >> 15)) * (t | 1)) & 0xFFFFFFFF
        t = (t ^ (t + (((t ^ (t >> 7)) * (t | 61)) & 0xFFFFFFFF))) & 0xFFFFFFFF
        return (((t ^ (t >> 14)) & 0xFFFFFFFF) / 4294967296.0)
    return rnd


def _fnv1a(items: list[ErrorCfg]) -> int:
    parts = sorted(f"{it.mode}|{it.p}|{it.interval}|{it.duration}" for it in items)
    s = 2166136261
    for ch in "\n".join(parts):
        s = ((s ^ ord(ch)) * 16777619) & 0xFFFFFFFF
    return s


def simulate_duty(items: list[ErrorCfg], runs: int = 50, sim_sec: int | None = None,
                  symmetric_correction: bool = True) -> tuple[dict[str, float], int]:
    """
    errors.html::simulateDuty 와 동일 알고리즘. 반환: (duty 평균, 1 회 시뮬 길이).
    """
    system_items = [it for it in items if is_system(it)]
    if not system_items:
        return {m: 0.0 for m in SYSTEM_MODES}, 0

    max_wait = max((it.interval / it.p) for it in system_items if it.p > 0)
    sim = sim_sec if sim_sec is not None else max(180, int(300 * max_wait))
    if sim_sec is None:
        sim = min(3600, sim)

    seed = _fnv1a(system_items)
    active_by_mode: dict[str, float] = {it.mode: 0.0 for it in system_items}

    for r in range(runs):
        rand = mulberry32((seed + r * 2654435761) & 0xFFFFFFFF)
        active = _one_sim_run(system_items, rand, sim)
        for m, v in active.items():
            active_by_mode[m] += v

    if symmetric_correction:
        classes: dict[tuple, list[str]] = defaultdict(list)
        for it in system_items:
            classes[(it.p, it.interval, it.duration)].append(it.mode)
        for modes in classes.values():
            if len(modes) <= 1:
                continue
            avg = sum(active_by_mode[m] for m in modes) / len(modes)
            for m in modes:
                active_by_mode[m] = avg

    total_sim = sim * runs
    return {m: active_by_mode[m] / total_sim for m in active_by_mode}, sim


def _one_sim_run(system_items: list[ErrorCfg], rand, sim: int) -> dict[str, float]:
    """단일 Monte-Carlo 1회분. 반환은 각 모드가 'active' 로 보낸 초 수."""
    events: list[tuple[float, int]] = []
    for idx, it in enumerate(system_items):
        jitter_max = min(it.interval, 2)
        phase = rand() * jitter_max
        t = phase
        while t < sim:
            events.append((t, idx))
            t += it.interval
    events.sort()

    active: dict[str, float] = {it.mode: 0.0 for it in system_items}
    busy_until = 0.0
    for t, idx in events:
        if t < busy_until:
            continue
        cfg = system_items[idx]
        if cfg.p <= 0:
            continue
        if rand() < cfg.p:
            end = min(sim, t + cfg.duration)
            active[cfg.mode] += (end - t)
            busy_until = t + cfg.duration
    return active


def simulate_uncertainty(items: list[ErrorCfg], window_sec: int,
                         runs: int = 200, seed_base: int = 0xC0FFEE) -> dict[str, dict]:
    """
    창 길이(window_sec) 짜리 관측을 many-run 샘플링해 realization 변동(1σ) 을 추정.
    - 각 run 이 "같은 설정으로 서버를 켠 한 번의 window_sec 분량 관측" 과 동일 의미.
    - (p, I, D) 가 같은 모드끼리는 pool 해 변동 추정을 안정화 (라벨 교환 불변).
    반환: mode -> {mean, std, p10, p90}
    """
    system_items = [it for it in items if is_system(it)]
    if not system_items:
        return {}
    samples: dict[str, list[float]] = {it.mode: [] for it in system_items}

    for r in range(runs):
        rand = mulberry32((seed_base + r * 2654435761) & 0xFFFFFFFF)
        active = _one_sim_run(system_items, rand, window_sec)
        for m, v in active.items():
            samples[m].append(v / window_sec)

    # exchangeability pool
    classes: dict[tuple, list[str]] = defaultdict(list)
    for it in system_items:
        classes[(it.p, it.interval, it.duration)].append(it.mode)
    for modes in classes.values():
        if len(modes) <= 1:
            continue
        pooled: list[float] = []
        for m in modes:
            pooled.extend(samples[m])
        for m in modes:
            samples[m] = pooled

    out: dict[str, dict] = {}
    for m, xs in samples.items():
        xs_sorted = sorted(xs)
        n = len(xs_sorted)
        mean = sum(xs_sorted) / n
        var = sum((v - mean) ** 2 for v in xs_sorted) / max(1, n - 1)
        p10 = xs_sorted[int(0.10 * n)]
        p90 = xs_sorted[int(0.90 * n)] if n > 0 else xs_sorted[-1]
        out[m] = {"mean": mean, "std": var ** 0.5, "p10": p10, "p90": p90}
    return out


# ───────────────────────── io.log 파싱 ─────────────────────────

_PAT_ACT = re.compile(
    r"(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+) - "
    r"System error activated on device (?P<name>[^(]+)\((?P<id>\d+)\): "
    r"mode=(?P<mode>\w+), duration=(?P<dur>\d+)s"
)
_PAT_DEACT = re.compile(
    r"(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+) - "
    r"System error deactivated on device (?P<name>[^(]+)\((?P<id>\d+)\)"
)


def _parse_ts(s: str) -> datetime:
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S.%f")


@dataclass
class ActivationPair:
    mode: str
    start: datetime
    end: datetime
    duration_cfg: int   # 로그가 알려주는 "설정된" duration

    @property
    def active_sec(self) -> float:
        return (self.end - self.start).total_seconds()


def parse_io_log(path: Path, device_id: int,
                 since: datetime | None = None, until: datetime | None = None,
                 only_duration: int | None = None) -> tuple[list[ActivationPair], datetime, datetime]:
    pairs: list[ActivationPair] = []
    pending: tuple[str, datetime, int] | None = None

    with path.open() as f:
        for line in f:
            m = _PAT_ACT.search(line)
            if m:
                if int(m.group("id")) != device_id:
                    continue
                pending = (m.group("mode"), _parse_ts(m.group("ts")), int(m.group("dur")))
                continue
            m = _PAT_DEACT.search(line)
            if m and pending:
                if int(m.group("id")) != device_id:
                    pending = None
                    continue
                mode, start, dur_cfg = pending
                end = _parse_ts(m.group("ts"))
                pending = None
                if only_duration is not None and dur_cfg != only_duration:
                    continue
                if since and start < since:
                    continue
                if until and end > until:
                    continue
                pairs.append(ActivationPair(mode, start, end, dur_cfg))

    if not pairs:
        raise SystemExit("io.log 에서 device {} 의 활성 이벤트를 찾지 못했습니다.".format(device_id))

    first = min(p.start for p in pairs)
    last = max(p.end for p in pairs)
    return pairs, first, last


# ───────────────────────── API/수동 config 로드 ─────────────────────────

def load_config_from_api(device_id: int, base_url: str) -> list[ErrorCfg]:
    url = f"{base_url.rstrip('/')}/api/error/errors/{device_id}"
    with urllib.request.urlopen(url, timeout=3) as resp:
        data = json.loads(resp.read())
    return [ErrorCfg(mode=e["mode"], frequency=int(e["frequency"]),
                     interval=int(e["interval"]), duration=int(e["errorDuration"]))
            for e in data if int(e.get("enabled", 0)) == 1 and e["mode"] in SYSTEM_MODES]


def parse_manual_config(parts: Iterable[str]) -> list[ErrorCfg]:
    out: list[ErrorCfg] = []
    for s in parts:
        try:
            mode, f, i, d = s.split(":")
            out.append(ErrorCfg(mode=mode.strip().upper(), frequency=int(f),
                                interval=int(i), duration=int(d)))
        except Exception as ex:
            raise SystemExit(f"--config 파싱 실패 ('{s}'): {ex}. 형식: MODE:FREQ:INTERVAL:DURATION")
    return out


# ───────────────────────── 요약 / 비교 ─────────────────────────

def observed_duty(pairs: list[ActivationPair], span_sec: float) -> dict[str, dict]:
    """mode -> {count, active_sec, duty_pct}"""
    by_mode: dict[str, dict] = {m: {"count": 0, "active": 0.0} for m in SYSTEM_MODES}
    for p in pairs:
        if p.mode not in by_mode:
            by_mode[p.mode] = {"count": 0, "active": 0.0}
        by_mode[p.mode]["count"] += 1
        by_mode[p.mode]["active"] += p.active_sec
    for m, agg in by_mode.items():
        agg["duty_pct"] = agg["active"] / span_sec * 100 if span_sec > 0 else 0.0
    return by_mode


def ci95_pp(count: int, duty_pct: float) -> float:
    """
    duty = N·D/T 로 보고 N 에 Poisson 노이즈 (√N) 를 가정하여 95% CI 폭(±pp) 을 돌려준다.
    duty_CI ≈ ±2·√N·D/T = ±2·(1/√N)·duty.
    """
    if count <= 0:
        return float("inf")
    return 2.0 * duty_pct / (count ** 0.5)


def print_report(cfgs: list[ErrorCfg], pairs: list[ActivationPair],
                 first: datetime, last: datetime, device_id: int,
                 sim_runs: int, sim_sec_override: int | None,
                 window_sec_label: int):
    span = (last - first).total_seconds()
    print(f"\n[device {device_id}]")
    print(f"관측 구간: {first} ~ {last}  ({span:.0f}s = {span/60:.1f}min = {span/3600:.2f}h)")
    print(f"관측 활성 이벤트: {len(pairs)} 건  (D={set(p.duration_cfg for p in pairs)} s)")
    print()

    sim_duty, sim_sec = simulate_duty(cfgs, runs=sim_runs, sim_sec=sim_sec_override)
    # realization 변동: 관측 길이 창 기준으로 샘플링. 관측 샘플 길이가 짧을수록 변동 큼.
    unc = simulate_uncertainty(cfgs, window_sec=int(span), runs=300)

    obs = observed_duty(pairs, span)
    total_count = len(pairs)

    print(f"{'mode':12s} {'cfg(f,I,D)':17s} {'solo':>7s} {'sim ± σ(realization)':>24s} "
          f"{'observed':>10s} {'count':>6s} {'±CI95(obs)':>12s} {'Δsim':>8s} {'in band?':>10s}")
    tot_obs = 0.0
    tot_sim = 0.0
    tot_var = 0.0
    for cfg in sorted(cfgs, key=lambda c: c.mode):
        solo = cfg.duty_solo() * 100
        s = sim_duty.get(cfg.mode, 0.0) * 100
        u = unc.get(cfg.mode, {"std": 0.0})
        sigma_pp = u["std"] * 100
        o_agg = obs.get(cfg.mode, {"count": 0, "duty_pct": 0.0})
        obs_pct = o_agg["duty_pct"]
        ci_obs = ci95_pp(o_agg["count"], obs_pct)
        delta = obs_pct - s
        # 합성 band: 모델 realization 2σ 와 관측 Poisson 2σ 를 쿼드러처로 합침
        band_pp = (ci_obs ** 2 + (2 * sigma_pp) ** 2) ** 0.5
        verdict = "YES" if abs(delta) <= band_pp else "no"
        print(f"{cfg.mode:12s} "
              f"({cfg.frequency:>3d}%,{cfg.interval:>4d}s,{cfg.duration:>3d}s)  "
              f"{solo:>6.2f}% "
              f"{s:>7.2f}% ±{sigma_pp:>5.2f}pp  "
              f"{obs_pct:>9.2f}% "
              f"{o_agg['count']:>6d} ±{ci_obs:>6.2f}pp "
              f"{delta:>+7.2f}pp {verdict:>10s}")
        tot_obs += obs_pct
        tot_sim += s
        tot_var += sigma_pp ** 2
    tot_ci = ci95_pp(total_count, tot_obs)
    tot_sigma = tot_var ** 0.5   # (상호 상관 무시, 보수적인 하한)
    tot_delta = tot_obs - tot_sim
    tot_band = (tot_ci ** 2 + (2 * tot_sigma) ** 2) ** 0.5
    tot_verdict = "YES" if abs(tot_delta) <= tot_band else "no"
    print(f"{'TOTAL':12s} {'':17s} {'':>7s} "
          f"{tot_sim:>7.2f}% ±{tot_sigma:>5.2f}pp  "
          f"{tot_obs:>9.2f}% "
          f"{total_count:>6d} ±{tot_ci:>6.2f}pp "
          f"{tot_delta:>+7.2f}pp {tot_verdict:>10s}")
    print(f"  (σ(realization): 관측 길이 창에서 '같은 설정, 다른 startup' 시 모델 duty 가 흔들리는 폭 (1σ).)")
    print(f"  (CI95(obs): √N 기반 Poisson 샘플 노이즈. band = √(CI² + (2σ)²), 둘 다 반영.)")

    # 모델 창 길이도 참고로
    print(f"\n모델 창 (JS adaptive): "
          f"max(I/p)·3 = {max((c.interval/c.p) if c.p>0 else 0 for c in cfgs)*3:.0f}s"
          f" → clamp(180, …, 1800) = {window_sec_label}s")
    print(f"내부 Monte-Carlo: runs={sim_runs}, simSec={sim_sec}s "
          f"(총 샘플 시간 {sim_runs*sim_sec/3600:.1f}h)")


def run_accelerated(cfgs: list[ErrorCfg], sim_sec: int, runs: int, device_id: int) -> None:
    """
    로그 없이, ErrorJob 이벤트 루프를 직접 배속으로 돌려본다.
      - 각 run 은 "서버를 sim_sec 초 동안 방금 켠 상태로 운영한 결과" 와 의미적으로 동일.
      - 서로 다른 random seed → 다른 jitter 위상 → 각 run 이 독립 realization.
      - 이 결과를 errors.html 의 Expected occupancy (symmetric-corrected mean) 와 비교한다.
    """
    import random
    system_items = [c for c in cfgs if is_system(c)]
    if not system_items:
        print("활성 system error 가 없습니다.", file=sys.stderr)
        return

    # UI 익스펙테이션 (chart 가 쓰는 값)
    ui_duty, _ = simulate_duty(cfgs, runs=50, sim_sec=None)   # symmetric-corrected (JS 와 동일 50 runs)
    ui_unc = simulate_uncertainty(cfgs, window_sec=sim_sec, runs=300)

    # 배속 시뮬 — runs 번 독립 realization
    per_mode_runs: dict[str, list[float]] = {it.mode: [] for it in system_items}
    per_mode_counts: dict[str, list[int]] = {it.mode: [] for it in system_items}
    total_duties: list[float] = []

    base_seed = random.randint(0, 0xFFFFFFFF)
    for r in range(runs):
        rand = mulberry32((base_seed + r * 2654435761) & 0xFFFFFFFF)
        active, counts = _one_sim_run_with_counts(system_items, rand, sim_sec)
        tot_active = 0.0
        for m, v in active.items():
            per_mode_runs[m].append(v / sim_sec)
            tot_active += v
        for m, c in counts.items():
            per_mode_counts[m].append(c)
        total_duties.append(tot_active / sim_sec)

    print(f"\n[device {device_id}] 배속 시뮬: 1 run = {sim_sec}s "
          f"({sim_sec/3600:.1f}h = {sim_sec/86400:.2f}일), runs = {runs}")
    print(f"총 시뮬 시간: {sim_sec*runs}s ({sim_sec*runs/3600:.1f}h)")
    print()
    print(f"{'mode':12s} {'cfg(f,I,D)':17s} "
          f"{'UI expect (± σ)':>22s} "
          f"{'sim mean':>10s} {'sim range':>18s} {'avg count':>10s} {'Δ(sim-UI)':>12s}")
    tot_ui = 0.0
    for cfg in sorted(system_items, key=lambda c: c.mode):
        xs = per_mode_runs[cfg.mode]
        counts = per_mode_counts[cfg.mode]
        mean = sum(xs) / len(xs) * 100
        lo = min(xs) * 100
        hi = max(xs) * 100
        ui_pct = ui_duty.get(cfg.mode, 0.0) * 100
        ui_sigma = ui_unc.get(cfg.mode, {"std": 0.0})["std"] * 100
        tot_ui += ui_pct
        delta = mean - ui_pct
        avg_cnt = sum(counts) / len(counts)
        print(f"{cfg.mode:12s} "
              f"({cfg.frequency:>3d}%,{cfg.interval:>4d}s,{cfg.duration:>3d}s)  "
              f"{ui_pct:>7.2f}% ± {ui_sigma:>4.2f}pp  "
              f"{mean:>8.2f}%  "
              f"[{lo:>5.2f} ~ {hi:>5.2f}]%  "
              f"{avg_cnt:>8.1f}  "
              f"{delta:>+9.2f}pp")
    tot_mean = sum(total_duties) / len(total_duties) * 100
    tot_lo = min(total_duties) * 100
    tot_hi = max(total_duties) * 100
    tot_sigma_sq = 0.0
    for cfg in system_items:
        tot_sigma_sq += (ui_unc.get(cfg.mode, {"std": 0.0})["std"] * 100) ** 2
    tot_sigma = (tot_sigma_sq / max(1, len(set(c.mode for c in system_items)))) ** 0.5
    print(f"{'TOTAL':12s} {'':17s} "
          f"{tot_ui:>7.2f}% ± {tot_sigma:>4.2f}pp  "
          f"{tot_mean:>8.2f}%  "
          f"[{tot_lo:>5.2f} ~ {tot_hi:>5.2f}]%  "
          f"{'':>8s}  "
          f"{tot_mean - tot_ui:>+9.2f}pp")
    print()
    print("  UI expect: errors.html 파이차트가 쓰는 symmetric-corrected 평균 ± realization 1σ")
    print("  sim mean:  배속 시뮬 run 들의 평균 duty")
    print("  sim range: run 별 min ~ max — '서버를 매번 다르게 켰을 때 실제로 벌어지는 폭'")
    print("  Δ:         (배속 평균 − UI) — |Δ| 가 σ 보다 작으면 UI 가 잘 맞춤")


def _one_sim_run_with_counts(system_items: list[ErrorCfg], rand, sim: int) -> tuple[dict[str, float], dict[str, int]]:
    """_one_sim_run 과 동일하지만 모드별 활성화 횟수도 반환."""
    events: list[tuple[float, int]] = []
    for idx, it in enumerate(system_items):
        jitter_max = min(it.interval, 2)
        phase = rand() * jitter_max
        t = phase
        while t < sim:
            events.append((t, idx))
            t += it.interval
    events.sort()

    active: dict[str, float] = {it.mode: 0.0 for it in system_items}
    counts: dict[str, int] = {it.mode: 0 for it in system_items}
    busy_until = 0.0
    for t, idx in events:
        if t < busy_until:
            continue
        cfg = system_items[idx]
        if cfg.p <= 0:
            continue
        if rand() < cfg.p:
            end = min(sim, t + cfg.duration)
            active[cfg.mode] += (end - t)
            counts[cfg.mode] += 1
            busy_until = t + cfg.duration
    return active, counts


def convergence_series(pairs: list[ActivationPair], step_min: int = 30) -> list[tuple[float, float, dict[str, float]]]:
    """
    로그 시작 시점부터 step_min 분 단위로 누적 duty 를 계산.
    반환: [(cumulative_hours, total_duty_pct, {mode: duty_pct}), ...]
    """
    if not pairs:
        return []
    pairs = sorted(pairs, key=lambda p: p.start)
    t0 = pairs[0].start
    series: list[tuple[float, float, dict[str, float]]] = []
    cur_cutoff_sec = step_min * 60
    while True:
        cutoff = t0.timestamp() + cur_cutoff_sec
        slice_ = [p for p in pairs if p.start.timestamp() <= cutoff]
        if not slice_:
            break
        span = min(cutoff, max(p.end.timestamp() for p in slice_)) - t0.timestamp()
        if span <= 0:
            break
        by_mode: dict[str, float] = defaultdict(float)
        for p in slice_:
            by_mode[p.mode] += p.active_sec
        total = sum(by_mode.values())
        series.append((span / 3600,
                       total / span * 100,
                       {m: by_mode[m] / span * 100 for m in SYSTEM_MODES}))
        if cutoff >= pairs[-1].end.timestamp():
            break
        cur_cutoff_sec += step_min * 60
    return series


def print_convergence(pairs: list[ActivationPair], target_sim: dict[str, float], step_min: int = 30):
    series = convergence_series(pairs, step_min=step_min)
    if not series:
        return
    print(f"\n[수렴 추이] 매 {step_min} 분 누적 duty — 값이 안정화되는지, 모델({target_sim['TOTAL']:.2f}% total) 로 수렴하는지 보기")
    hdr = f"{'span(h)':>8s} {'TOTAL':>7s}  " + "  ".join(f"{m:>8s}" for m in SYSTEM_MODES)
    print(hdr)
    for h, tot, by in series:
        row = f"{h:>8.2f} {tot:>6.2f}%  " + "  ".join(f"{by[m]:>7.2f}%" for m in SYSTEM_MODES)
        print(row)


# ───────────────────────── CLI ─────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", type=int, default=34, help="device id (default: 34)")
    ap.add_argument("--log", type=Path,
                    default=Path(__file__).resolve().parent.parent /
                    "MK119CommunicationTestSuite" / "logs" / "io.log",
                    help="io.log 경로")
    ap.add_argument("--base-url", default="http://localhost:8082",
                    help="서버 base URL (설정 가져올 때, default: http://localhost:8082)")
    ap.add_argument("--config", nargs="+", default=None,
                    help="수동 설정: MODE:FREQ:INTERVAL:DURATION 를 공백 구분. "
                         "지정하면 API 호출을 건너뛴다.")
    ap.add_argument("--since", default=None, help="관측 시작 (YYYY-MM-DD HH:MM:SS 또는 ISO)")
    ap.add_argument("--until", default=None, help="관측 끝")
    ap.add_argument("--only-duration", type=int, default=None,
                    help="로그에서 특정 duration=N 활성만 추림 (설정 바뀐 구간 섞였을 때 유용)")
    ap.add_argument("--sim-runs", type=int, default=50, help="Monte-Carlo 반복 횟수 (default: 50)")
    ap.add_argument("--sim-sec", type=int, default=None,
                    help="Monte-Carlo 1 회 시뮬 길이 (default: adaptive, min 3600 cap)")
    ap.add_argument("--convergence", action="store_true", help="누적 duty 수렴 추이도 출력")
    ap.add_argument("--convergence-step", type=int, default=30, help="수렴 추이 단위 (분, default: 30)")
    ap.add_argument("--accelerated", type=int, nargs="?", const=86400, default=None, metavar="SIM_SEC",
                    help="로그 분석 대신, ErrorJob 이벤트 루프를 'SIM_SEC 초만큼' 배속 시뮬 (default 86400=1일) "
                         "후 결과를 UI 익스펙테이션과 비교. --since/--until/--log 는 무시.")
    ap.add_argument("--accelerated-runs", type=int, default=5,
                    help="--accelerated 와 함께: 서로 다른 random seed 로 몇 번 돌릴지 (default: 5)")
    args = ap.parse_args()

    # config 로드
    if args.config:
        cfgs = parse_manual_config(args.config)
    else:
        try:
            cfgs = load_config_from_api(args.device, args.base_url)
        except (urllib.error.URLError, urllib.error.HTTPError, OSError) as e:
            print(f"[warn] 서버({args.base_url}) 호출 실패: {e}", file=sys.stderr)
            print("      --config 로 수동 지정하거나 서버를 띄우세요.", file=sys.stderr)
            return 2

    if not cfgs:
        print("활성 system error 설정이 없습니다.", file=sys.stderr)
        return 2

    if args.accelerated is not None:
        run_accelerated(cfgs, sim_sec=args.accelerated, runs=args.accelerated_runs, device_id=args.device)
        return 0

    # 어떤 duration 을 섞어 분석할지 — 명시 안 하면 "가장 많이 나오는 duration" 자동 선택
    only_dur = args.only_duration
    if only_dur is None:
        # 설정에서 가장 흔한 duration 을 쓴 활성만 추림
        # (스케일링 전후가 로그에 섞여도 지금 설정과 일치하는 것만 보도록)
        only_dur = cfgs[0].duration

    since = _parse_ts(args.since.replace("T", " ")) if args.since else None
    until = _parse_ts(args.until.replace("T", " ")) if args.until else None

    pairs, first, last = parse_io_log(args.log, args.device, since=since, until=until,
                                      only_duration=only_dur)

    # adaptive 창 길이 (UI 라벨 참고용)
    max_wait = max((c.interval / c.p) if c.p > 0 else 0 for c in cfgs)
    window = min(1800, max(180, int(3 * max_wait)))

    print_report(cfgs, pairs, first, last, args.device,
                 sim_runs=args.sim_runs, sim_sec_override=args.sim_sec,
                 window_sec_label=window)

    if args.convergence:
        sim_duty, _ = simulate_duty(cfgs, runs=args.sim_runs, sim_sec=args.sim_sec)
        target = {m: v * 100 for m, v in sim_duty.items()}
        target["TOTAL"] = sum(target.values())
        print_convergence(pairs, target, step_min=args.convergence_step)

    return 0


if __name__ == "__main__":
    sys.exit(main())
