"""S15 — 프로토콜 정의 (GENERIC · MK RCU RS).

GENERIC 장비는 저장된 프로토콜 정의를 가리키고, 엔진이 그 정의대로 요청을 읽고 답한다
(docs/PROTOCOL_ENGINE_DESIGN.md). 예시 문서 셋을 이름만 바꿔 저장해 쓴다.

  power-meter       Modbus RTU FC01·02·03·05·06·16 — 순차 타겟, 32비트 두 칸, 예외 응답, CRC (여기서는 FC03 만 본다)
  thermo-controller STX … BCC ETX   — 길이 필드, 패킷 쓰기(PROTOCOL_WRITE), NAK
  ascii-meter       '#R01\\r'        — 글자·10진 글자·16진 글자 체크섬, UDP

진짜 소켓으로 요청을 보내고, 바이트 하나하나가 맞는지 우리가 따로 계산한 값과 대조한다.
"""
import json
import socket
import struct
import time
import cts

NAME = '프로토콜 정의 (GENERIC · MK RCU RS)'


def crc16(data):
    """Modbus RTU CRC16 — 응답 끝 두 바이트를 따로 계산해 본다."""
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def rtu(*parts):
    body = bytes(parts)
    c = crc16(body)
    return body + bytes([c & 0xFF, c >> 8])


def show(b):
    return ' '.join('%02X' % x for x in b) if b else '(응답 없음)'


def recv(s, wait=1.5):
    s.settimeout(wait)
    try:
        return s.recv(4096)
    except (socket.timeout, ConnectionResetError):
        return b''


def device_id(name):
    return [d for d in cts.ok('/device/devices') if d['name'] == name][0]['id']


def run(ctx):
    base = cts.PORT_BASE + 80
    pm = cts.add_definition(cts.PREFIX + 's15_pm', 'power-meter')
    th = cts.add_definition(cts.PREFIX + 's15_thermo', 'thermo-controller')
    asc = cts.add_definition(cts.PREFIX + 's15_ascii', 'ascii-meter')
    try:
        _settings_rules(ctx, pm, base)
        _modbus(ctx, pm, base + 1)
        _writes(ctx, th, base + 2)
        _udp_text(ctx, asc, base + 3)
        _rcu(ctx, pm, base + 4)
    finally:
        cts.sweep()


def _settings_rules(ctx, pm, port):
    ctx.given('GENERIC 장비를 정의 없이, 그리고 unitId 300(UI8 범위 밖)으로 등록해 본다')
    st, body = cts.api('/device/addDevice', 'PUT',
                       cts.device_body(cts.PREFIX + 's15_nodef', port, protocol='GENERIC', settings={}))
    ctx.check(st >= 400 and 'Choose a protocol definition' in json.dumps(body),
              '정의가 없는 GENERIC 장비는 등록되지 않는다', '%s %s' % (st, body))
    st, body = cts.api('/device/addDevice', 'PUT',
                       cts.device_body(cts.PREFIX + 's15_bad', port, protocol='GENERIC',
                                       settings=cts.generic_settings(pm, unitId=300)))
    ctx.check(st >= 400 and 'unitId must be from 0 to 255' in json.dumps(body),
              '파라미터는 정의가 선언한 타입의 범위를 따른다 (UI8 → 0~255)', '%s %s' % (st, body))


def _modbus(ctx, pm, port):
    cts.add_device(cts.PREFIX + 's15_pm_dev', port, protocol='GENERIC', settings=cts.generic_settings(pm, unitId=1))
    did = device_id(cts.PREFIX + 's15_pm_dev')
    time.sleep(1.5)
    cts.add_simulation(did, 'v1', '전압1', [cts.policy(kind='STATIC', data_type='FLOAT', value=220.1)], interval=1)
    cts.add_simulation(did, 'e', '전력량', [cts.policy(kind='STATIC', data_type='FLOAT', value=12345.6)], interval=1)
    st, body = cts.api('/simulation/addSimulation', 'PUT', {
        'device': {'id': did}, 'name': 'bad', 'target': 'voltage1',
        'policies': [cts.policy(kind='STATIC', data_type='FLOAT', value=1)], 'interval': 1, 'enabled': 1})
    ctx.given('power-meter 정의를 쓰는 GENERIC TCP 장비에 전압1=220.1, 전력량=12345.6 을 STATIC 으로 넣고, '
              '정의에 없는 타겟 voltage1 도 등록해 본다')
    ctx.check(st >= 400 and 'No target' in json.dumps(body, ensure_ascii=False),
              'Simulation 은 정의가 선언한 타겟에만 등록된다', '%s %s' % (st, body))
    time.sleep(2.5)

    s = socket.create_connection(('127.0.0.1', port), 5)
    try:
        req = rtu(1, 3, 0, 0, 0, 1)
        s.sendall(req)
        r = recv(s)
        ctx.given('Modbus RTU 요청 %s (unit 1, FC03, 시작 0, 1칸) 를 보낸다' % show(req))
        ctx.check(r == rtu(1, 3, 2, 0x08, 0x99),
                  '전압1 220.1 이 scale 0.1 로 2201(0x0899) 이 되어 오고, CRC 가 맞다', show(r))

        s.sendall(rtu(1, 3, 0, 7, 0, 2))
        r = recv(s)
        ctx.check(r == rtu(1, 3, 4, 0x00, 0x01, 0xE2, 0x40),
                  '32비트 전력량은 7~8 두 칸을 차지한다 (12345.6 → 0001 E240)', show(r))

        s.sendall(rtu(1, 3, 0, 7, 0, 1))
        r = recv(s)
        ctx.check(r == rtu(1, 0x83, 2),
                  '32비트 값의 절반만 읽으면 예외 02 (fits 거짓)', show(r))

        ctx.given('같은 요청을 둘로 쪼개 보내고, 앞에 쓰레기 한 바이트를 붙인 두 요청을 한 번에 보낸다')
        s.sendall(req[:3])
        time.sleep(0.02)
        s.sendall(req[3:])
        r = recv(s)
        ctx.check(r == rtu(1, 3, 2, 0x08, 0x99), '쪼개진 프레임은 모아서 하나로 읽는다', show(r))
        s.sendall(b'\xAA' + req + req)
        r = b''
        deadline = time.time() + 2
        while len(r) < 14 and time.time() < deadline:
            r += recv(s, 0.5)
        ctx.check(r == rtu(1, 3, 2, 0x08, 0x99) * 2,
                  '앞의 쓰레기는 버리고(다시 맞춤), 붙어 온 두 요청에 각각 답한다', show(r))

        s.sendall(rtu(2, 3, 0, 0, 0, 1))
        r = recv(s, 0.8)
        ctx.check(r == b'', '다른 unit id 에는 답하지 않는다 (respond when … reply none)', show(r))

        ctx.given('같은 연결을 둔 채 정의의 "수량 범위 밖" 예외 코드를 3 → 4 로 고쳐 저장한다')
        doc = cts.ok('/protocols/%d' % pm)
        d2 = doc['document']
        # FC03 메시지(readHolding)의 두 번째 규칙이 수량 범위 밖 → 예외. 예시에 메시지가 늘어도 이름으로 찾는다
        fc03 = next(m for m in d2['messages'] if m['name'] == 'readHolding')
        fc03['respond'][1]['args']['code'] = 4
        d2['tests'] = [t for t in d2['tests'] if t['name'] != 'FC03 수량 0']
        st, body = cts.api('/protocols/%d' % pm, 'PUT', {'document': d2, 'version': doc['version']})
        s.sendall(rtu(1, 3, 0, 0, 0, 0))
        r = recv(s)
        ctx.check(st == 200 and r == rtu(1, 0x83, 4),
                  '저장하면 연결을 끊지 않고 다음 요청부터 새 정의로 답한다', '%s %s' % (st, show(r)))
    finally:
        s.close()


def _writes(ctx, th, port):
    cts.add_device(cts.PREFIX + 's15_th_dev', port, protocol='GENERIC', settings=cts.generic_settings(th))
    did = device_id(cts.PREFIX + 's15_th_dev')
    time.sleep(1.5)
    s = socket.create_connection(('127.0.0.1', port), 5)
    try:
        frame = bytes.fromhex('020120020109' + '2B03')
        s.sendall(frame)
        r = recv(s)
        fields = (cts.snapshot(did).get('payload') or {}).get('fields', {})
        ctx.given('thermo-controller 정의의 쓰기 요청 %s (설정온도 26.5) 를 보낸다' % show(frame))
        ctx.check(r == bytes.fromhex('020106000703') and fields.get('설정온도') == 26.5,
                  '패킷 쓰기가 저장소에 들어가고 ACK 로 답한다', '%s, 설정온도 %s' % (show(r), fields.get('설정온도')))
        ctx.check(list(fields)[:5] == ['운전', '경보', '현재온도', '습도', '설정온도'],
                  'Server Data 는 정의가 선언한 타겟을 모두 그 순서로 보인다', list(fields))

        st, body = cts.write(did, '설정온도', 30, 'FLOAT', 30)
        s.sendall(frame)
        r = recv(s)
        v = (cts.snapshot(did).get('payload') or {}).get('fields', {}).get('설정온도')
        ctx.given('REST 로 설정온도=30 을 30초 잠그고 같은 쓰기 요청을 다시 보낸다')
        ctx.check(r == bytes.fromhex('020106000703') and v == 30,
                  'API 잠금이 패킷 쓰기(PROTOCOL_WRITE)를 막는다. 장비는 평소대로 답한다', '%s, 설정온도 %s' % (show(r), v))
        st, body = cts.write(did, 'voltage1', 1, 'FLOAT')
        ctx.check(st == 501, 'REST 쓰기도 정의의 타겟 목록 밖이면 거절된다 (501)', '%s %s' % (st, body))
    finally:
        s.close()


def _udp_text(ctx, asc, port):
    cts.add_device(cts.PREFIX + 's15_as_dev', port, protocol='GENERIC', transport='UDP', settings=cts.generic_settings(asc))
    did = device_id(cts.PREFIX + 's15_as_dev')
    time.sleep(1.5)
    cts.add_simulation(did, 'v', '전압', [cts.policy(kind='STATIC', data_type='FLOAT', value=220.5)], interval=1)
    cts.add_simulation(did, 'i', '전류', [cts.policy(kind='STATIC', data_type='FLOAT', value=5.12)], interval=1)
    cts.add_simulation(did, 'p', '전력', [cts.policy(kind='STATIC', data_type='INTEGER', value=1100)], interval=1)
    time.sleep(2.5)
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.settimeout(1.5)
    try:
        u.sendto(b'#R01\r', ('127.0.0.1', port))
        try:
            r = u.recv(1024)
        except socket.timeout:
            r = b''
        ctx.given("ascii-meter 정의의 UDP 장비에 '#R01\\r' 를 보낸다")
        ctx.check(r == b'=01,+220.5,+005.12,1100*1A\r',
                  '글자 응답: 부호·0 채움·소수 자리와 16진 두 글자 합 체크섬', r)
    finally:
        u.close()


def _rcu(ctx, pm, port):
    cts.add_device(cts.PREFIX + 's15_rcu_dev', port, protocol='DAEYUN_RCU',
                   settings=dict(cts.generic_settings(pm, unitId=1), cpuAddr=1, diCount=8, doCount=8, aiCount=4))
    did = device_id(cts.PREFIX + 's15_rcu_dev')
    time.sleep(1.5)
    cts.add_simulation(did, 'v1', '전압1', [cts.policy(kind='STATIC', data_type='FLOAT', value=220.1)], interval=1)
    time.sleep(2.5)
    payload = rtu(1, 3, 0, 0, 0, 1)
    frame = bytes([1, 0x0A]) + struct.pack('>HH', 2, len(payload)) + payload
    c = crc16(frame)
    frame += bytes([c & 0xFF, c >> 8])
    inner = rtu(1, 3, 2, 0x08, 0x99)
    want = bytes([1, 0x0A]) + struct.pack('>HH', 2, len(inner)) + inner
    c = crc16(want)
    want += bytes([c & 0xFF, c >> 8])
    s = socket.create_connection(('127.0.0.1', port), 5)
    try:
        s.sendall(frame)
        r = recv(s)
    finally:
        s.close()
    ctx.given('MK RCU 장비(RS 본문 정의 = power-meter)에 RS(0x0A) 요청으로 Modbus FC03 을 감싸 보낸다')
    ctx.check(r == want, 'RS 본문은 정의가 답하고, 답은 포트·길이·CRC 껍데기에 다시 담긴다', show(r))
    st, body = cts.api('/protocols/%d' % pm, 'DELETE')
    ctx.check(st == 409, '장비가 쓰는 정의는 지워지지 않는다 (409)', '%s %s' % (st, body))
