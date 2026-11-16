"""S10 — System Error 네 가지가 실제로 응답을 망가뜨리는가.

s04 는 등록·해제만 봤다. 여기서는 Modbus 클라이언트로 진짜 요청을 보내고
응답이 어떻게 달라지는지 본다. Frequency 100 / Interval 1 이라 곧 적중한다.

  Discard     응답이 오지 않는다
  Timeout     지정한 시간만큼 늦게 온다
  Garbage     길이가 지정한 바이트인 쓰레기가 온다
  Disconnect  포트가 내려가 접속이 거절된다. Duration 뒤 다시 열린다

Duration 이 지나면 스스로 풀리는 것도 함께 본다.
"""
import socket
import struct
import time
import cts

NAME = 'System Error 4종의 실제 동작'

UNIT = 1
ADDR = 10


def read_fc3(host, port, timeout=6.0):
    """FC3 한 칸 읽기. (응답바이트, 걸린시간) 또는 (None, 걸린시간)."""
    t0 = time.time()
    s = socket.create_connection((host, port), 4)
    try:
        s.sendall(struct.pack('>HHHB', 1, 0, 6, UNIT)
                  + struct.pack('>BHH', 3, ADDR, 1))
        s.settimeout(timeout)
        try:
            return s.recv(512), time.time() - t0
        except socket.timeout:
            return None, time.time() - t0
        except ConnectionResetError:
            return None, time.time() - t0
    finally:
        s.close()


def until(fn, seconds, period=0.7):
    """fn() 이 참을 돌려줄 때까지 기다린다. 참이면 True."""
    end = time.time() + seconds
    while time.time() < end:
        if fn():
            return True
        time.sleep(period)
    return False


def _device(ctx, suffix, port):
    dev = cts.add_device(cts.PREFIX + 's10' + suffix, port,
                         settings=cts.modbus_settings(unit_id=UNIT))
    cts.add_simulation(dev['id'], 'v', '3_%d' % ADDR,
                       [cts.policy(kind='STATIC', value=1234)], interval=1)
    time.sleep(4)
    return dev['id']


def run(ctx):
    host = '127.0.0.1'

    # ── 기준: 에러가 없으면 정상 응답 ──────────────────────────────────
    port = cts.PORT_BASE + 20
    did = _device(ctx, 'base', port)
    try:
        ctx.given('기준선. MODBUS 장비(unitId=1)에 STATIC value=1234 → 3_10 만 두고 '
                  '에러는 걸지 않은 채, Modbus TCP FC3 으로 레지스터 10 을 1칸 읽는다')
        resp, _ = read_fc3(host, port)
        ctx.check(resp is not None and len(resp) >= 11,
                  '정상 응답 프레임이 돌아온다 (MBAP 7 + FC 1 + 바이트수 1 + 데이터 2 = 11바이트 이상). '
                  '뒤 케이스들의 비교 기준이다',
                  '%s (%d바이트)' % (resp.hex() if resp else '(응답 없음)',
                                    len(resp) if resp else 0))
    finally:
        cts.delete_device_tree(did)

    # ── DISCARD ────────────────────────────────────────────────────────
    port = cts.PORT_BASE + 21
    did = _device(ctx, 'discard', port)
    try:
        # 주기를 길게, 유지 시간을 짧게 잡아 '풀린 뒤 다시 적중하기 전' 창을 만든다.
        # 주기 1초 / 유지 6초로 두면 풀리자마자 다시 걸려 복구를 관측할 수 없다.
        cts.add_error(did, 'DISCARD', frequency=100, interval=8, duration=2)
        ctx.given('같은 FC3 요청을 반복하면서 DISCARD 에러를 건다: frequency=100%, '
                  'interval=8초(판정 주기), duration=2초(유지). 판정 주기를 길게, 유지를 짧게 두어 '
                  '"걸린 구간" 과 "풀린 구간" 을 모두 관측할 수 있게 한다. 최대 20초 폴링')
        got = until(lambda: read_fc3(host, port, timeout=2.0)[0] is None, 20)
        ctx.check(got,
                  'Discard 활성 구간에서는 요청을 받고도 응답을 보내지 않는다 (읽기 타임아웃)',
                  '타임아웃 관측됨' if got else '20초 동안 계속 응답이 왔다')
        back = until(lambda: read_fc3(host, port, timeout=2.0)[0] is not None, 12)
        ctx.check(back,
                  'duration 2초가 지나면 스스로 풀려 응답이 다시 돌아온다. '
                  '에러는 한 번 걸리면 끝나는 것이 아니라 상태가 오갔다',
                  '응답 복구됨' if back else '12초 동안 응답이 없었다')
    finally:
        cts.delete_device_tree(did)

    # ── TIMEOUT ────────────────────────────────────────────────────────
    port = cts.PORT_BASE + 22
    did = _device(ctx, 'timeout', port)
    try:
        cts.add_error(did, 'TIMEOUT', frequency=100, interval=1, duration=8, timeout=3)
        slow = [0.0]

        def is_slow():
            r, dt = read_fc3(host, port, timeout=8.0)
            slow[0] = dt
            return r is not None and dt >= 2.5

        ctx.given('같은 FC3 요청. TIMEOUT 에러를 건다: frequency=100%, interval=1초, '
                  'duration=8초, timeout=3초. 응답을 버리지 않고 3초 늦춰 보낸다. '
                  '클라이언트 읽기 타임아웃은 8초로 두고 최대 14초 폴링')
        ctx.check(until(is_slow, 14),
                  '응답이 오기는 하지만 지정한 3초만큼 늦게 온다 (2.5초 이상이면 통과). '
                  'Discard 와 달리 연결은 살아 있다',
                  '%.1f초 만에 응답 (기대 3초 안팎)' % slow[0])
    finally:
        cts.delete_device_tree(did)

    # ── GARBAGE ────────────────────────────────────────────────────────
    port = cts.PORT_BASE + 23
    did = _device(ctx, 'garbage', port)
    try:
        cts.add_error(did, 'GARBAGE', frequency=100, interval=1, duration=8,
                      garbage_length=37)
        seen = [b'']

        def is_garbage():
            r, _ = read_fc3(host, port, timeout=3.0)
            if r:
                seen[0] = r
            return r is not None and len(r) == 37

        ctx.given('같은 FC3 요청. GARBAGE 에러를 건다: frequency=100%, interval=1초, '
                  'duration=8초, garbageLength=37. 정상 응답 대신 지정한 길이의 '
                  '임의 바이트를 보낸다. 최대 14초 폴링')
        ctx.check(until(is_garbage, 14),
                  '정상 응답(11바이트)이 아니라 지정한 37바이트가 돌아온다. '
                  '마스터 쪽 파서가 깨진 프레임을 어떻게 다루는지 볼 수 있다',
                  '%d바이트 수신 (기대 37)' % len(seen[0]))
    finally:
        cts.delete_device_tree(did)

    # ── DISCONNECT ─────────────────────────────────────────────────────
    port = cts.PORT_BASE + 24
    did = _device(ctx, 'disconnect', port)
    try:
        eid = cts.add_error(did, 'DISCONNECT', frequency=100, interval=8, duration=2)

        def refused():
            try:
                socket.create_connection((host, port), 2).close()
                return False
            except OSError:
                return True

        ctx.given('이번에는 요청을 보내지 않고 TCP 접속만 반복 시도한다. DISCONNECT 에러를 건다: '
                  'frequency=100%, interval=8초, duration=2초. 포트 자체를 내린다. 최대 20초 폴링')
        ctx.check(until(refused, 20),
                  '활성 구간에는 TCP 접속이 거절된다 (장비가 통신 불능인 상황)',
                  '접속 거절 관측됨')
        ctx.check(until(lambda: not refused(), 12),
                  'duration 2초가 지나면 포트가 다시 열려 접속이 된다',
                  '접속 복구됨')

        # 끄면 유지 시간을 기다리지 않고 그 자리에서 풀린다
        until(refused, 20)
        cts.ok('/error/setEnabled/%d/0' % eid)
        ctx.given('다시 포트가 내려간 상태를 만든 뒤, duration 이 끝나기를 기다리지 않고 '
                  'POST /api/error/setEnabled/{id}/0 으로 에러를 끈다')
        ctx.check(until(lambda: not refused(), 6),
                  '유지 시간을 기다리지 않고 그 자리에서 포트가 열린다. '
                  '에러를 끄면 활성 상태도 함께 해제된다',
                  '6초 안에 접속 복구됨')
    finally:
        cts.delete_device_tree(did)
