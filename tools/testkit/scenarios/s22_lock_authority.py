"""S22 — 잠금의 authority.

잠금은 건 쪽(authority)을 기억한다. 순위는
API_WRITE > PROTOCOL_WRITE > ERROR_SIMULATION > VALUE_SIMULATION 이다.

  사람(API_WRITE)의 잠금   프로토콜 제어도 막는다. 응답은 나가지만 값은 쓰지 않고,
                           쓰지 못했으므로 stopOnControl 도 걸지 않는다
  같거나 높은 쪽의 잠금    나중에 건 잠금으로 바뀐다. 만료 시각도 새 값을 따른다
  ERROR 의 정지 잠금       프로토콜 제어를 막지 못한다
  XGT                     잠금은 실제 byte 번지로 찾는다. W/D target 은 겹치는 byte 하나만 잠겨도 막힌다
  편집 세션 종료          만료 없는 잠금만 푼다. 초 단위 잠금은 만료될 때까지 남는다
"""
import socket
import struct
import time
import cts

NAME = '잠금의 authority'


def fc6(port, addr, value, host='127.0.0.1'):
    """Modbus TCP 단일 레지스터 쓰기. 응답을 받으면 bytes, 못 받으면 None."""
    s = socket.create_connection((host, port), 5)
    try:
        s.sendall(struct.pack('>HHHB', 1, 0, 6, 1) + struct.pack('>BHH', 6, addr, value))
        s.settimeout(5)
        try:
            return s.recv(256)
        except Exception:
            return None
    finally:
        s.close()


def enabled(sim_id):
    return cts.api('/simulation/getEnabled/%d' % sim_id)[1]


def run(ctx):
    port = cts.PORT_BASE + 160
    dev = cts.add_device(cts.PREFIX + 's22', port, settings=cts.modbus_settings(unit_id=1))
    did = dev['id']
    browser = None
    try:
        # ── 사람의 잠금이 프로토콜 제어를 막는다 ────────────────────────
        # stopOnControl=-1 은 제어를 받으면 Simulation 을 DB 에서 끈다.
        # 막힌 제어가 정지를 걸었는지 enabled 로 남아서 확인할 수 있다.
        perm = cts.add_simulation(did, 'perm', '3_90', [cts.policy(value=100)],
                                  interval=1, stop_on_control=-1)
        time.sleep(3)
        cts.write(did, '3_90', 5555, lock_seconds=20)
        ctx.given('MODBUS 장비에 STATIC 100 주기 1초 → 3_90 을 stopOnControl=-1(제어 시 영구 정지)로 '
                  '등록하고, 사람이 REST 로 {value: 5555, lockSeconds: 20} 을 써서 잠근다. '
                  '그 뒤 외부 마스터가 Modbus FC6 으로 레지스터 90 에 1234 를 쓴다')
        resp = fc6(port, 90, 1234)
        time.sleep(2)
        ctx.check(resp is not None,
                  'FC6 에 정상 응답 프레임이 돌아온다. 막힌 제어도 응답은 그대로 나간다',
                  resp.hex() if resp else '(응답 없음)')
        ctx.check(cts.holding(did, 90) == 5555,
                  '값은 사람이 쓴 5555 그대로다. 프로토콜 제어가 사람의 잠금을 넘지 못한다',
                  '%s (기대 5555, 제어 값 1234 면 실패)' % cts.holding(did, 90))
        ctx.check(enabled(perm['id']) is True,
                  'Simulation 이 켜져 있다. 쓰지 못한 제어는 stopOnControl 도 걸지 않는다',
                  'enabled=%s' % enabled(perm['id']))

        cts.write(did, '3_90', 5555, lock_seconds=0)
        ctx.given('lockSeconds=0 으로 사람의 잠금을 풀고 같은 FC6(1234)을 다시 보낸다. '
                  '잠금이 없을 때는 제어가 들어가는지 대조한다')
        fc6(port, 90, 1234)
        time.sleep(2)
        ctx.check(cts.holding(did, 90) == 1234 and enabled(perm['id']) is False,
                  '이번에는 1234 가 쓰이고 stopOnControl=-1 이 Simulation 을 끈다',
                  'value=%s, enabled=%s' % (cts.holding(did, 90), enabled(perm['id'])))

        # ── 나중에 건 높은 잠금으로 바뀐다 ──────────────────────────────
        cts.add_simulation(did, 'soc', '3_91', [cts.policy(kind='ACCUMULATE', rng=[500, 1])],
                           interval=1, stop_on_control=30)
        time.sleep(3)
        ctx.given('ACCUMULATE range=[500,1] 주기 1초 → 3_91 을 stopOnControl=30 으로 등록하고 '
                  'FC6 으로 3333 을 써서 30초짜리 PROTOCOL_WRITE 잠금을 건다. 이어서 사람이 '
                  'REST 로 {value: 4444, lockSeconds: 3} 을 쓴다')
        fc6(port, 91, 3333)
        time.sleep(1)
        ctx.check('3_91' in cts.locks(did),
                  '제어로 stopOnControl 잠금이 걸린다', cts.locks(did))
        status, body = cts.write(did, '3_91', 4444, lock_seconds=3)
        time.sleep(1)
        ctx.check(status == 200 and cts.holding(did, 91) == 4444,
                  '사람의 쓰기는 정지 잠금에 막히지 않는다',
                  'HTTP %s, value=%s' % (status, cts.holding(did, 91)))
        time.sleep(4)
        ctx.check('3_91' not in cts.locks(did),
                  '5초 뒤 잠금이 없다. 잠금이 사람의 3초짜리로 바뀌어 30초를 기다리지 않는다',
                  cts.locks(did))
        time.sleep(2)
        ctx.check(cts.holding(did, 91) != 4444,
                  '잠금이 풀린 뒤 시뮬레이션이 다시 쓴다',
                  '%s (4444 가 아니어야 한다)' % cts.holding(did, 91))

        # ── ERROR 의 정지 잠금은 프로토콜 제어를 막지 못한다 ────────────
        err_sim = cts.add_simulation(did, 'err', '3_92',
                                     [cts.policy(kind='ACCUMULATE', rng=[800, 1])], interval=1)
        eid = cts.add_error(did, 'SIMULATION', frequency=100, interval=2,
                            simulation_id=err_sim['id'], error_value=7777.0,
                            simulation_stop=30)
        ctx.given('ACCUMULATE 주기 1초 → 3_92 에 SIMULATION 에러(frequency=100%, interval=2초, '
                  'errorValue=7777, simulation_stop=30초)를 붙여 ERROR_SIMULATION 잠금을 건다. '
                  '한 번 적중하면 에러를 지우고 FC6 으로 2222 를 쓴다')
        hit = False
        for _ in range(10):
            time.sleep(1)
            if '3_92' in cts.locks(did) and cts.holding(did, 92) == 7777:
                hit = True
                break
        cts.ok('/error/deleteError/%d' % eid, 'DELETE')
        ctx.check(hit, '에러가 7777 을 쓰고 정지 잠금을 건다',
                  'value=%s, locks=%s' % (cts.holding(did, 92), cts.locks(did)))
        fc6(port, 92, 2222)
        time.sleep(2)
        ctx.check(cts.holding(did, 92) == 2222,
                  '제어 값 2222 가 들어간다. PROTOCOL_WRITE 는 ERROR 의 잠금보다 높다',
                  '%s (기대 2222)' % cts.holding(did, 92))

        # ── XGT 는 실제 byte 번지로 잠금을 찾는다 ──────────────────────
        xgt = cts.add_device(cts.PREFIX + 's22xgt', cts.PORT_BASE + 161, protocol='XGT_PLC',
                             settings={'cpuId': '1', 'maxBlockSize': '1400', 'registers': '10000'})
        try:
            xid = xgt['id']

            def dword(i):
                b = cts.snapshot(xid)['payload']['blocks']['D']
                return b[i] << 8 | b[i + 1], b[i + 1] << 8 | b[i]

            cts.add_simulation(xid, 'w50', '%DW50', [cts.policy(value=100)], interval=1)
            cts.add_simulation(xid, 'w60', '%DW60', [cts.policy(value=200)], interval=1)
            time.sleep(3)
            ctx.given('XGT 장비에 STATIC 100 주기 1초 → %DW50 (byte 100~101) 을 등록하고, '
                      '사람이 REST 로 %DW50 에 {value: 5555, dataType: UI16AB, lockSeconds: 15} 를 쓴다. '
                      '잠금은 byte 번지 %DB100, %DB101 로 걸린다. 4초 동안 1초 간격으로 읽는다')
            cts.write(xid, '%DW50', 5555, lock_seconds=15)
            got = []
            for _ in range(4):
                time.sleep(1)
                got.append(dword(100))
            ctx.check(all(5555 in g for g in got),
                      '4회 모두 5555 다. %DW50 의 시뮬레이션이 같은 byte 의 잠금을 찾아 멈춘다',
                      '%s, locks=%s' % (got, cts.locks(xid)))

            ctx.given('STATIC 200 주기 1초 → %DW60 (byte 120~121) 을 두고, 사람이 그중 byte 하나인 '
                      '%DB121 에만 {value: 7, dataType: UI8, lockSeconds: 15} 를 쓴다. '
                      'word 의 절반만 잠긴 상태다. 4초 동안 읽는다')
            cts.write(xid, '%DB121', 7, data_type='UI8', lock_seconds=15)
            b121 = []
            for _ in range(4):
                time.sleep(1)
                b121.append(cts.snapshot(xid)['payload']['blocks']['D'][121])
            ctx.check(all(v == 7 for v in b121),
                      '%DB121 이 4회 모두 7 이다. 겹치는 byte 가 하나라도 잠기면 '
                      '%DW60 의 시뮬레이션은 그 word 를 쓰지 않는다',
                      '%s, locks=%s' % (b121, cts.locks(xid)))

            # XGT target 은 대문자로 관리한다. 소문자 표기도 같은 번지로 판정된다
            cts.add_simulation(xid, 'lower', '%dw80', [cts.policy(value=300)], interval=1)
            time.sleep(3)
            ctx.given('XGT 장비에 소문자 target %dw80 (byte 160~161) 으로 STATIC 300 주기 1초를 등록하고, '
                      '사람이 REST 로 소문자 %dw70 에 {value: 4321, dataType: UI16AB, lockSeconds: 15} 를 쓴다')
            got80 = dword(160)
            status, body = cts.write(xid, '%dw70', 4321, lock_seconds=15)
            time.sleep(2)
            ctx.check(300 in got80,
                      '소문자 target 의 시뮬레이션도 값을 쓴다',
                      '%s (기대 300)' % (got80,))
            ctx.check(status == 200 and 4321 in dword(140)
                      and {'%DB140', '%DB141'} <= set(cts.locks(xid)),
                      '소문자 REST 쓰기가 같은 번지에 쓰이고, 잠금은 대문자 byte 번지로 걸린다',
                      'HTTP %s, value=%s, affected=%s, locks=%s'
                      % (status, dword(140), body.get('affectedTargets') if isinstance(body, dict) else body,
                         cts.locks(xid)))
        finally:
            cts.delete_device_tree(xgt['id'])

        # ── 편집 세션 종료는 만료 없는 잠금만 푼다 ──────────────────────
        try:
            import cdp
        except ImportError:
            ctx.check(False, 'cdp 모듈을 찾을 수 없다')
            return
        browser = cdp.Chrome(width=1400, height=950)
        browser.goto(cts.BASE + '/devices/%d/serverdata' % did, wait=7)
        s_timed, _ = cts.write(did, '3_93', 111, lock_seconds=60)
        s_perm, _ = cts.write(did, '3_94', 222, lock_seconds=-1)
        ctx.given('이 장비의 Server Data 화면을 헤드리스 Chrome 으로 열어 편집 세션을 잡은 채로, '
                  'REST 로 3_93 에 60초 잠금, 3_94 에 만료 없는 잠금(lockSeconds=-1)을 건다. '
                  '그 뒤 화면을 닫아 세션을 끝낸다')
        ctx.check(s_timed == 200 and s_perm == 200
                  and {'3_93', '3_94'} <= set(cts.locks(did)),
                  '세션이 있으므로 두 잠금이 모두 접수된다',
                  'HTTP %s/%s, locks=%s' % (s_timed, s_perm, cts.locks(did)))
        browser.close()
        browser = None
        for _ in range(15):
            time.sleep(1)
            if '3_94' not in cts.locks(did):
                break
        now = cts.locks(did)
        ctx.check('3_94' not in now and '3_93' in now,
                  '만료 없는 잠금 3_94 만 풀리고 60초 잠금 3_93 은 남는다. '
                  '초 단위 잠금은 세션 자신이 건 것이라도 만료까지 둔다',
                  now)
    finally:
        if browser is not None:
            browser.close()
        cts.delete_device_tree(did)
