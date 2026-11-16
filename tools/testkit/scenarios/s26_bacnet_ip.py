"""S26 — BACnet/IP ReadProperty·WriteProperty (bacnet-ip 예시 정의).

BACnet/IP 서버를 Protocol Definition 으로 만든 예시를 실제 UDP 로 시험한다.
요청과 기대 응답은 여기서 따로 만든다(ANSI/ASHRAE 135 의 BVLC·NPDU·APDU 인코딩).

  - REST 쓰기·Value Simulation 으로 넣은 값을 ReadProperty 로 읽는다 (REAL·Enumerated)
  - WriteProperty 가 Server Data 를 바꾸고, 쓰기 금지·잠금은 지킨다
  - Device 파라미터(인스턴스·Vendor), 와일드카드 인스턴스, Reject, Who-Is 무응답
"""
import socket
import struct
import time
import cts

NAME = 'BACnet/IP ReadProperty·WriteProperty'

PV, NAME_PROP, VENDOR_ID = 85, 77, 120
AI, AO, AV, BI, BO, BV, DEVICE = 0, 1, 2, 3, 4, 5, 8


def ctx_tag(tag, data):
    return bytes([(tag << 4) | 0x08 | len(data)]) + data


def oid(t, inst):
    return ((t << 22) | inst).to_bytes(4, 'big')


def bip(apdu, function=0x0A, control=0x04):
    rest = bytes([1, control]) + apdu
    return bytes([0x81, function]) + (4 + len(rest)).to_bytes(2, 'big') + rest


def rp(invoke, t, inst, prop):
    return bip(bytes([0, 5, invoke, 12]) + ctx_tag(0, oid(t, inst)) + ctx_tag(1, bytes([prop])))


def wp(invoke, t, inst, value, prop=PV, priority=None):
    apdu = bytes([0, 5, invoke, 15]) + ctx_tag(0, oid(t, inst)) + ctx_tag(1, bytes([prop])) + b'\x3E' + value + b'\x3F'
    if priority is not None:
        apdu += ctx_tag(4, bytes([priority]))
    return bip(apdu)


def ack(invoke, t, inst, prop, value):
    return bip(bytes([0x30, invoke, 12]) + ctx_tag(0, oid(t, inst)) + ctx_tag(1, bytes([prop])) + b'\x3E' + value + b'\x3F', control=0)


def simple_ack(invoke):
    return bip(bytes([0x20, invoke, 15]), control=0)


def error(invoke, service, cls, code):
    return bip(bytes([0x50, invoke, service, 0x91, cls, 0x91, code]), control=0)


def real(v):
    return b'\x44' + struct.pack('>f', v)


def enum(v):
    return bytes([0x91, v])


def show(b):
    return ' '.join('%02X' % x for x in b) if b else '(응답 없음)'


def ask(port, req, wait=2.0):
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.settimeout(wait)
    try:
        u.sendto(req, ('127.0.0.1', port))
        r, _ = u.recvfrom(4096)
        return r
    except socket.timeout:
        return b''
    finally:
        u.close()


def device_id(name):
    return [d for d in cts.ok('/device/devices') if d['name'] == name][0]['id']


def fields(did):
    return cts.snapshot(did).get('payload', {}).get('fields', {})


def run(ctx):
    port = cts.PORT_BASE + 190
    bac = cts.add_definition(cts.PREFIX + 's26_bacnet', 'bacnet-ip')
    try:
        _targets(ctx, bac)
        _udp(ctx, bac, port)
    finally:
        cts.sweep()


def _targets(ctx, bac):
    t = cts.ok('/protocols/%d/targets' % bac)['targets']
    names = [x['target'] for x in t]
    ctx.given('bacnet-ip 정의를 저장하고 Target 목록(/api/protocols/{id}/targets)을 본다')
    ctx.check(len(names) == 20 and names[0] == 'AI-1' and 'BV-4' in names,
              'I/O 객체 20개(AI·AV·BI·BV 4개씩, AO·BO 2개씩)', '%d개, 처음 %s' % (len(names), names[:3]))
    av = next(x for x in t if x['target'] == 'AV-1')
    bv = next(x for x in t if x['target'] == 'BV-1')
    ai = next(x for x in t if x['target'] == 'AI-1')
    ctx.check(av['access'] == 'rw' and 'FLOAT' in av['dataTypes'] and 'BINARY' in bv['dataTypes'] and ai['access'] != 'rw',
              'AV 는 rw·FLOAT, BV 는 BINARY 로 시뮬레이션할 수 있고, AI 는 읽기 전용', '%s / %s / %s' % (av, bv, ai))


def _udp(ctx, bac, port):
    name = cts.PREFIX + 's26_udp'
    cts.add_device(name, port, protocol='GENERIC', transport='UDP', settings=cts.generic_settings(bac, deviceInstance=2001, vendorId=321))
    did = device_id(name)
    time.sleep(1.5)
    cts.write(did, 'AV-1', 21.5, data_type='FLOAT')
    cts.write(did, 'BV-2', 1, data_type='BINARY')
    cts.add_simulation(did, 'ai1', 'AI-1', [cts.policy(kind='STATIC', data_type='FLOAT', value=12.25)], interval=1)
    ctx.given('GENERIC UDP 장비(bacnet-ip, deviceInstance 2001, vendorId 321)에 REST 로 AV-1=21.5, BV-2=1, '
              'Value Simulation 으로 AI-1=12.25(STATIC) 을 넣는다')
    time.sleep(2.5)

    r = ask(port, rp(1, AV, 1, PV))
    ctx.check(r == ack(1, AV, 1, PV, real(21.5)), 'AV-1 Present_Value = REAL 21.5 (Invoke ID 1 을 되돌린다)', show(r))
    r = ask(port, rp(2, AI, 1, PV))
    ctx.check(r == ack(2, AI, 1, PV, real(12.25)), 'AI-1 Present_Value = 시뮬레이션 값 12.25', show(r))
    r = ask(port, rp(3, BV, 2, PV))
    ctx.check(r == ack(3, BV, 2, PV, enum(1)), 'BV-2 Present_Value = Enumerated 1 (active)', show(r))
    r = ask(port, rp(4, DEVICE, 4194303, VENDOR_ID))
    ctx.check(r == ack(4, DEVICE, 4194303, VENDOR_ID, b'\x22' + (321).to_bytes(2, 'big')),
              '와일드카드 인스턴스로 Device Vendor_Identifier = 장비 파라미터 321', show(r))
    r = ask(port, rp(5, DEVICE, 1001, NAME_PROP))
    ctx.check(r == error(5, 12, 1, 31), '기본값 1001 이 아니라 2001 이 이 장비의 인스턴스 → 1001 은 unknown-object', show(r))

    ctx.given('WriteProperty 로 AV-3=33.25(우선순위 8), BO-1=active 를 쓰고, AI-2 에도 써 본다')
    r1 = ask(port, wp(10, AV, 3, real(33.25), priority=8))
    r2 = ask(port, wp(11, BO, 1, enum(1)))
    r3 = ask(port, wp(12, AI, 2, real(1.0)))
    time.sleep(0.3)
    f = fields(did)
    ctx.check(r1 == simple_ack(10) and r2 == simple_ack(11), '두 쓰기 모두 Simple-ACK', '%s / %s' % (show(r1), show(r2)))
    ctx.check(f.get('AV-3') == 33.25 and f.get('BO-1') == 1, 'Server Data: AV-3=33.25, BO-1=1',
              'AV-3=%s BO-1=%s' % (f.get('AV-3'), f.get('BO-1')))
    ctx.check(r3 == error(12, 15, 2, 40), 'AI-2 는 읽기 전용 → write-access-denied (class 2, code 40)', show(r3))

    ctx.given('REST 로 AV-4=5 를 60초 잠근 뒤 WriteProperty 로 AV-4=9 를 쓴다')
    cts.write(did, 'AV-4', 5, data_type='FLOAT', lock_seconds=60)
    r = ask(port, wp(13, AV, 4, real(9.0)))
    time.sleep(0.3)
    ctx.check(r == simple_ack(13) and fields(did).get('AV-4') == 5, '응답은 Simple-ACK 이지만 잠긴 Target 은 바뀌지 않는다',
              '%s AV-4=%s' % (show(r), fields(did).get('AV-4')))

    ctx.given('ReadPropertyMultiple(서비스 14)과 Who-Is(Unconfirmed, 브로드캐스트 BVLC)를 보낸다')
    rpm = bip(bytes([0, 5, 20, 14]) + ctx_tag(0, oid(AV, 1)) + b'\x1E\x09\x55\x1F')
    r = ask(port, rpm)
    ctx.check(r == bip(bytes([0x60, 20, 9]), control=0), '모르는 서비스 → Reject(unrecognized-service 9)', show(r))
    r = ask(port, bip(b'\x10\x08', function=0x0B, control=0), wait=1.0)
    ctx.check(r == b'', 'Who-Is 에는 답하지 않는다 (읽기·쓰기 스택만)', show(r))
