"""S11 — Value Simulation 의 나머지 프로퍼티와 즉시 편집 API.

s01 이 STATIC / RANDOM / ACCUMULATE 를 봤다. 여기서는 설명서가 약속한 나머지를 본다.

  SEQUENCE            숫자 목록을 차례로 돌려 쓴다
  stopOnControl < 0   제어를 받으면 영구 정지 (DB 에 enabled=0 기록)
  즉시 편집 API       편집기에 들어가지 않고 값/범위/목록만 고친다
  누적 초기화         ACCUMULATE 를 초기값부터 다시 시작한다
"""
import socket
import struct
import time
import cts

NAME = 'Simulation 프로퍼티와 즉시 편집'

UNIT = 1


def run(ctx):
    # ── SEQUENCE ───────────────────────────────────────────────────────
    dev = cts.add_device(cts.PREFIX + 's11seq', cts.PORT_BASE + 30)
    did = dev['id']
    try:
        sim = cts.add_simulation(
            did, 'seq', '3_80',
            # SEQUENCE 는 숫자 배열을 range 가 아니라 value 에 담는다
            [{'id': 'v1', 'policy': 'SEQUENCE', 'data_type': 'UI16AB',
              'value': [11, 22, 33], 'range': None, 'distribution': None}],
            interval=1)
        sid = sim['id']
        time.sleep(4)
        ctx.given('MODBUS 장비에 SEQUENCE value=[11,22,33] 주기 1초 → 3_80 을 등록하고 '
                  '(SEQUENCE 는 숫자 배열을 range 가 아니라 value 에 담는다) 4초 돌린 뒤, '
                  '레지스터 80 을 1초 간격으로 7회 읽는다')
        vals = cts.sample(did, 80, 7)
        seen = [v for v in vals if v is not None]
        ctx.check(set(seen) <= {11, 22, 33} and len(set(seen)) >= 3,
                  '목록에 없는 값은 나오지 않고, 세 값이 모두 등장한다. 목록을 차례로 돈다',
                  '%s (등장한 값 %s)' % (vals, sorted(set(seen))))

        # 목록을 즉시 편집한다
        st, _ = cts.api('/simulation/updateSequenceValues/%d' % sid, 'POST',
                        {'values': [77, 88]})
        ctx.given('돌고 있는 그 Simulation 에 POST /api/simulation/updateSequenceValues/{id} '
                  '{values: [77, 88]} 를 보낸다. 다시 등록하지 않고 목록만 바꾸는 경로다')
        ctx.check(200 <= st < 300, 'HTTP 200 으로 수용된다', st)
        time.sleep(4)
        vals = cts.sample(did, 80, 5)
        seen = [v for v in vals if v is not None]
        ctx.check(seen and set(seen) <= {77, 88},
                  '4초 뒤부터 새 목록 [77, 88] 의 값만 나온다. 옛 값 11/22/33 은 사라진다',
                  '%s (등장한 값 %s)' % (vals, sorted(set(seen))))
    finally:
        cts.delete_device_tree(did)

    # ── 즉시 편집: STATIC 값, RANDOM 범위, 누적 초기화 ─────────────────
    dev = cts.add_device(cts.PREFIX + 's11edit', cts.PORT_BASE + 31)
    did = dev['id']
    try:
        st_sim = cts.add_simulation(did, 'st', '3_81',
                                    [cts.policy(kind='STATIC', value=100)], interval=1)
        rd_sim = cts.add_simulation(did, 'rd', '3_82',
                                    [cts.policy(kind='RANDOM', rng=[10, 20])], interval=1)
        ac_sim = cts.add_simulation(did, 'ac', '3_83',
                                    [cts.policy(kind='ACCUMULATE', rng=[1000, 1])],
                                    interval=1)
        time.sleep(4)

        ctx.given('MODBUS 장비에 주기 1초 Simulation 3건을 등록한다: STATIC value=100 → 3_81, '
                  'RANDOM range=[10,20] → 3_82, ACCUMULATE range=[1000,1] → 3_83. '
                  '4초 돌린 뒤 돌고 있는 상태에서 STATIC 값을 555 로 바꾼다 '
                  '(POST /api/simulation/updateStaticSimulationValue/{id}/555)')
        cts.ok('/simulation/updateStaticSimulationValue/%d/555' % st_sim['id'], 'POST')
        time.sleep(3)
        ctx.check(cts.holding(did, 81) == 555,
                  '3초 뒤 레지스터 81 이 555 다. 재등록 없이 값이 갈린다',
                  '%s (기대 555)' % cts.holding(did, 81))

        st, _ = cts.api('/simulation/updateSimulationRange/%d' % rd_sim['id'],
                        'POST', {'low': 900, 'high': 910})
        ctx.given('같은 장비의 RANDOM Simulation 범위를 [10,20] 에서 [900,910] 으로 바꾼다 '
                  '(POST /api/simulation/updateSimulationRange/{id} {low: 900, high: 910})')
        ctx.check(200 <= st < 300, 'HTTP 200 으로 수용된다', st)
        time.sleep(4)
        r = cts.sample(did, 82, 4)
        ctx.check(all(900 <= v <= 910 for v in r if v is not None),
                  '4회 샘플이 모두 새 범위 900~910 안이다. 옛 범위 10~20 값은 나오지 않는다',
                  '%s (최소 %s, 최대 %s)' % (r, min(r), max(r)))

        high = cts.holding(did, 83)
        cts.ok('/simulation/resetAccumulation/%d' % ac_sim['id'], 'POST')
        time.sleep(3)
        low = cts.holding(did, 83)
        ctx.given('같은 장비의 ACCUMULATE Simulation 에 누적 초기화를 보낸다 '
                  '(POST /api/simulation/resetAccumulation/{id})')
        ctx.check(low is not None and low < high,
                  '쌓여 있던 값이 시작값 근처로 돌아간다. 장비를 다시 만들지 않고 누적을 되감는다',
                  '%s -> %s (감소 %s)' % (high, low, (high - low) if low is not None else '?'))
    finally:
        cts.delete_device_tree(did)

    # ── stopOnControl 음수 = 영구 정지 ─────────────────────────────────
    port = cts.PORT_BASE + 32
    dev = cts.add_device(cts.PREFIX + 's11perm', port,
                         settings=cts.modbus_settings(unit_id=UNIT))
    did = dev['id']
    try:
        sim = cts.add_simulation(did, 'perm', '3_84',
                                 [cts.policy(kind='ACCUMULATE', rng=[700, 1])],
                                 interval=1, stop_on_control=-1)
        sid = sim['id']
        time.sleep(4)
        ctx.given('MODBUS 장비(unitId=1)에 ACCUMULATE range=[700,1] 주기 1초 → 3_84 를 '
                  'stopOnControl=-1 (음수 = 영구 정지)로 등록하고 4초 돌린다')
        ctx.check(cts.ok('/simulation/getEnabled/%d' % sid) is True,
                  '제어를 받기 전에는 enabled 가 true 다', 'enabled=true')

        s = socket.create_connection(('127.0.0.1', port), 5)
        s.sendall(struct.pack('>HHHB', 1, 0, 6, UNIT) + struct.pack('>BHH', 6, 84, 4321))
        s.settimeout(5)
        try:
            s.recv(256)
        except Exception:
            pass
        s.close()
        time.sleep(3)

        ctx.given('Modbus FC6 으로 레지스터 84 에 4321 을 써서 제어를 수용시킨 뒤 3초 기다린다')
        ctx.check(cts.ok('/simulation/getEnabled/%d' % sid) is False,
                  'enabled 가 false 로 DB 에 기록된다. 재시작해도 꺼진 채로 남는다',
                  'enabled=false')
        after = cts.sample(did, 84, 4)
        ctx.check(len(set(after)) == 1,
                  '4회 샘플이 모두 같다. 꺼진 Simulation 은 더 이상 쓰지 않는다',
                  '%s (서로 다른 값 %d종)' % (after, len(set(after))))
        ctx.check(cts.locks(did) == [],
                  'locks 는 비어 있다. 영구 정지는 시간 만료가 있는 잠금이 아니라 '
                  'Simulation 자체를 끄는 것이다',
                  cts.locks(did))
    finally:
        cts.delete_device_tree(did)
