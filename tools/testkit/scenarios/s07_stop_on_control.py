"""S07 — 프로토콜 제어 수용과 stopOnControl.

클라이언트가 Modbus FC6 으로 레지스터를 쓰면 그 값이 ServerData 에 반영되고
(PROTOCOL_WRITE), Simulation 의 stopOnControl 이 양수면 그 Target 을 그만큼
쓰기 보호한다. 보호 중에는 누적도 나아가지 않는다.

주의: 장비의 communicationSettings 에 packetType 이 없으면 ModbusDecoder 생성자가
터지면서 연결이 리셋된다. 로그가 남지 않아 원인을 찾기 어려우므로 cts.modbus_settings()
를 쓴다.
"""
import socket
import struct
import time
import cts

NAME = '제어 수용과 stopOnControl'

UNIT = 1
ADDR = 70


def fc6(host, port, addr, value, unit=UNIT):
    """Modbus TCP 단일 레지스터 쓰기. 응답을 받으면 bytes, 못 받으면 None."""
    s = socket.create_connection((host, port), 5)
    try:
        s.sendall(struct.pack('>HHHB', 1, 0, 6, unit)
                  + struct.pack('>BHH', 6, addr, value))
        s.settimeout(5)
        try:
            return s.recv(256)
        except Exception:
            return None
    finally:
        s.close()


def run(ctx):
    port = cts.PORT_BASE + 7
    dev = cts.add_device(cts.PREFIX + 's07', port,
                         settings=cts.modbus_settings(unit_id=UNIT))
    did = dev['id']
    try:
        # 주기를 5초로 둔다. 핸들러는 값을 쓴 다음에 잠그므로 두 동작이 완전히 붙어
        # 있지는 않다. ServerData 색인이 들어오며 그 사이의 DB 왕복은 사라졌지만
        # 두 단계인 것은 그대로다. 여기서 보려는 것은 stopOnControl 이라 주기를 떼어 놓는다.
        cts.add_simulation(did, 'accum', '3_%d' % ADDR,
                           [cts.policy(kind='ACCUMULATE', rng=[500, 1])],
                           interval=5, stop_on_control=10)
        time.sleep(6)

        ctx.given('MODBUS 장비(unitId=1)에 ACCUMULATE range=[500,1] 주기 5초 → 3_70, '
                  'stopOnControl=10초 를 등록하고 6초 돌린다. 그 뒤 외부 마스터처럼 '
                  'Modbus TCP FC6(Write Single Register) 로 레지스터 70 에 3333 을 쓴다')
        resp = fc6('127.0.0.1', port, ADDR, 3333)
        ctx.check(resp is not None,
                  'FC6 요청에 정상 응답 프레임이 돌아온다 (에코 응답)',
                  resp.hex() if resp else '(응답 없음)')
        time.sleep(2)

        ctx.check(cts.holding(did, ADDR) == 3333,
                  '제어로 쓴 3333 이 ServerData 에 반영된다. 프로토콜 쓰기는 잠금에 막히지 않는다',
                  '%s (기대 3333)' % cts.holding(did, ADDR))
        ctx.check('3_%d' % ADDR in cts.locks(did),
                  '제어를 수용했으므로 stopOnControl 10초가 그 target 의 잠금으로 걸린다',
                  cts.locks(did))

        ctx.given('보호가 걸린 상태에서 레지스터 70 을 1초 간격으로 4회 읽는다 '
                  '(시뮬레이션 주기는 5초라 이 사이에 최소 한 번은 돌 차례가 온다)')
        held = cts.sample(did, ADDR, 4)
        ctx.check(set(held) == {3333},
                  '4회 모두 3333 이다. 제어 값이 보호 시간 동안 그대로 유지된다',
                  '%s (서로 다른 값 %d종)' % (held, len(set(held))))

        ctx.given('stopOnControl 10초가 지나도록 기다린다 (샘플링 4초 + 8초)')
        time.sleep(8)
        ctx.check(cts.locks(did) == [],
                  '보호가 만료되어 locks 가 빈다', cts.locks(did))
        time.sleep(2)
        after = cts.holding(did, ADDR)
        ctx.check(after != 3333,
                  '값이 제어 값 3333 에서 벗어난다. 시뮬레이션이 다시 쓰기 시작했다',
                  '%s (제어 값 3333 이 아니어야 한다)' % after)
        ctx.check(after - 500 < 20,
                  '시작값 500 에서 누적된 양이 20 미만이다. 20초 넘게 흘렀으므로 '
                  '보호 중에는 누적이 멈춰 있었다는 뜻이다',
                  '누적 %s (경과 20초 이상, 계속 돌았다면 4 이상 + 보호분)' % (after - 500))
    finally:
        cts.delete_device_tree(did)
