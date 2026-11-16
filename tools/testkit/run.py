#!/usr/bin/env python3
"""시나리오를 차례로 돌리고 결과를 모아 보여 준다.

  python3 tools/testkit/run.py            전체
  python3 tools/testkit/run.py s03 s06    일부만 (이름 앞부분으로 고른다)
  python3 tools/testkit/run.py --list     목록만

시나리오는 주기 작업이 몇 번 돌기를 기다리므로 전체가 몇 분 걸린다.
끝에 테스트가 만든 장비(ZZTMP_)가 남아 있지 않은지 확인하고, 남아 있으면 지운다.
"""
import importlib
import os
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, 'scenarios'))

import cts          # noqa: E402


def discover():
    names = sorted(f[:-3] for f in os.listdir(os.path.join(HERE, 'scenarios'))
                   if f.startswith('s') and f.endswith('.py'))
    return [(n, importlib.import_module(n)) for n in names]


def cell(s, width=400):
    """표 한 칸. 파이프와 줄바꿈을 지우고 너무 길면 줄인다.

    입력·기대 열은 그 케이스가 무엇을 했는지 읽는 사람이 재현할 수 있을 만큼 담아야 해서
    넉넉하게 둔다. 잘리면 보고서만 보고는 무슨 조건이었는지 알 수 없다.
    """
    s = str(s).replace('|', '/').replace('\n', ' ').strip()
    return (s[:width] + '…') if len(s) > width else (s or '—')


def write_report(path, results):
    """케이스별 입력·기대·실제를 기능별로 나열한 상세 보고서를 쓴다."""
    import datetime
    total_ok = sum(r[2].passed for r in results)
    total = sum(r[2].passed + r[2].failed for r in results)
    lines = [
        '# MK119 CTS 기능 테스트 상세 결과',
        '',
        '실행일 %s · 검증 %d건 중 %d건 통과'
        % (datetime.date.today().isoformat(), total, total_ok),
        '',
    ]
    for name, label, ctx, err, secs in results:
        lines += ['## %s — %s' % (name, label), '']
        if err:
            lines += ['실행 중 예외가 발생했다.', '', '```', err.strip(), '```', '']
        if not ctx.checks:
            lines += ['_검증 항목 없음_', '']
            continue
        lines += ['| # | 입력 | 기대 결과 | 실제 결과 | 판정 |',
                  '| --- | --- | --- | --- | --- |']
        prev = None
        for i, c in enumerate(ctx.checks, 1):
            given = cell(c['given']) if c['given'] != prev else '〃'
            prev = c['given']
            lines.append('| %d | %s | %s | %s | %s |'
                         % (i, given, cell(c['expected']), cell(c['actual']),
                            '통과' if c['ok'] else '실패'))
        lines.append('')
    open(path, 'w', encoding='utf-8').write('\n'.join(lines))


def main(argv):
    mods = discover()
    if '--list' in argv:
        for n, m in mods:
            print('  %-26s %s' % (n, getattr(m, 'NAME', '')))
        return 0

    picks = [a for a in argv if not a.startswith('-')]
    if picks:
        mods = [(n, m) for n, m in mods if any(n.startswith(p) for p in picks)]
        if not mods:
            print('고른 시나리오가 없다.')
            return 2

    if not cts.server_up():
        print('서버(%s)가 떠 있어야 한다.' % cts.BASE)
        return 1

    before = [d['name'] for d in cts.ok('/device/devices')]
    print('시작: 등록된 장비 %d대\n' % len(before))

    results, t0 = [], time.time()
    for name, mod in mods:
        label = getattr(mod, 'NAME', name)
        print('── %s  %s' % (name, label))
        ctx = cts.Ctx(label)
        started = time.time()
        err = None
        try:
            mod.run(ctx)
        except Exception:
            err = traceback.format_exc()
            print(err)
        results.append((name, label, ctx, err, time.time() - started))
        print()

    # ── 정리 확인 ──────────────────────────────────────────────────────
    left = cts.sweep()
    after = [d['name'] for d in cts.ok('/device/devices')]

    out = next((a.split('=', 1)[1] for a in argv if a.startswith('--report=')), None)
    if out:
        write_report(out, results)
        print('보고서: %s' % out)

    print('=' * 72)
    total_ok = total_bad = 0
    for name, label, ctx, err, secs in results:
        total_ok += ctx.passed
        total_bad += ctx.failed
        mark = 'FAIL' if (err or ctx.failed) else 'ok  '
        print('%s %-26s %-34s %2d/%-2d  %4.0fs'
              % (mark, name, label, ctx.passed, ctx.passed + ctx.failed, secs))
    print('-' * 72)
    print('검증 %d건 중 %d건 통과, %d건 실패   총 %.0f초'
          % (total_ok + total_bad, total_ok, total_bad, time.time() - t0))

    if left:
        print('정리: 남아 있던 테스트 장비 %d대를 지웠다 — %s' % (len(left), ', '.join(left)))
    if sorted(before) == sorted(after):
        print('기존 장비 %d대 그대로.' % len(after))
    else:
        print('경고: 장비 목록이 달라졌다. 전 %d대 / 후 %d대' % (len(before), len(after)))
        return 1
    return 1 if total_bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
