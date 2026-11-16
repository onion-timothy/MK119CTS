"""S25 — XGT FEnet 전용 프로토콜 (xgt-fenet 예시 정의).

LS ELECTRIC XGT 전용 프로토콜(XGK)을 Protocol Definition 으로 만든 예시를 실제 소켓으로 시험한다.
요청 프레임과 기대 응답은 여기서 따로 만든다(XGL-EFMTB 사용설명서 7.1절의 형식).

  - REST 쓰기·Value Simulation 으로 넣은 워드 값을 %DW·%MB·%MX·%DD·연속 읽기로 꺼낸다
  - 패킷 쓰기(워드·비트·연속)가 Server Data 의 워드 Target 을 바꾸고, 잠금은 지킨다
  - 쪼개진 프레임, 붙어 온 두 프레임, 앞의 잡음, UDP, NAK
"""
import socket
import time
import cts

NAME = 'XGT FEnet 전용 프로토콜'

LSIS = b'LSIS-XGT\x00\x00'


def le16(v):
    return (v & 0xFFFF).to_bytes(2, 'little')


def frame(body, invoke=0, source=0x33):
    h = LSIS + le16(0) + bytes([0, source]) + le16(invoke) + le16(len(body)) + b'\x00'
    return h + bytes([sum(h) & 0xFF]) + body


def reply(body, invoke=0):
    # 정의의 기본 파라미터: cpuType 1, sysState 1(RUN) → PLC Info 0x0101, CPU Info 0xA0, 위치 0
    h = LSIS + le16(0x0101) + bytes([0xA0, 0x11]) + le16(invoke) + le16(len(body)) + b'\x00'
    return h + bytes([sum(h) & 0xFF]) + body


def var(name):
    return le16(len(name)) + name.encode('ascii')


def read_req(dt, names, count=None):
    b = le16(0x54) + le16(dt) + le16(0) + le16(len(names)) + b''.join(var(n) for n in names)
    return b + (le16(count) if dt == 0x14 else b'')


def write_req(dt, names, datas):
    return (le16(0x58) + le16(dt) + le16(0) + le16(len(names)) + b''.join(var(n) for n in names)
            + b''.join(le16(len(d)) + d for d in datas))


def read_ack(dt, datas):
    return le16(0x55) + le16(dt) + le16(0) + le16(0) + le16(len(datas)) + b''.join(le16(len(d)) + d for d in datas)


def write_ack(dt, n):
    return le16(0x59) + le16(dt) + le16(0) + le16(0) + le16(n)


def nak(cmd, dt, code):
    return le16(cmd + 1) + le16(dt) + le16(0) + le16(0xFFFF) + le16(code)


def show(b):
    return ' '.join('%02X' % x for x in b) if b else '(응답 없음)'


def recv(s, want, wait=2.0):
    """want 바이트가 올 때까지 (또는 wait 초)"""
    s.settimeout(0.3)
    out, deadline = b'', time.time() + wait
    while len(out) < want and time.time() < deadline:
        try:
            chunk = s.recv(4096)
            if not chunk:
                break
            out += chunk
        except socket.timeout:
            pass
        except ConnectionResetError:
            break
    return out


def device_id(name):
    return [d for d in cts.ok('/device/devices') if d['name'] == name][0]['id']


def fields(did):
    return cts.snapshot(did).get('payload', {}).get('fields', {})


def run(ctx):
    port = cts.PORT_BASE + 180
    xgt = cts.add_definition(cts.PREFIX + 's25_xgt', 'xgt-fenet')
    try:
        _targets(ctx, xgt)
        _tcp(ctx, xgt, port)
        _udp(ctx, xgt, port + 1)
    finally:
        cts.sweep()


def _targets(ctx, xgt):
    t = cts.ok('/protocols/%d/targets' % xgt)['targets']
    names = [x['target'] for x in t]
    ctx.given('xgt-fenet 정의를 저장하고 Target 목록(/api/protocols/{id}/targets)을 본다')
    ctx.check(len(names) == 1026 and names[0] == 'P0' and 'D511' in names and 'R127' in names and '_CNF_ER' in names,
              '워드 Target 1024개(P0…R127) + 상태 플래그 2개', '%d개, 처음 %s' % (len(names), names[:3]))
    d = next(x for x in t if x['target'] == 'D100')
    f = next(x for x in t if x['target'] == 'F3')
    ctx.check(d['access'] == 'rw' and d['dataTypes'] == ['INTEGER'] and f['access'] != 'rw',
              'D100 은 rw·INTEGER, F 영역은 읽기 전용', '%s / %s' % (d, f))


def _tcp(ctx, xgt, port):
    name = cts.PREFIX + 's25_tcp'
    cts.add_device(name, port, protocol='GENERIC', settings=cts.generic_settings(xgt))
    did = device_id(name)
    time.sleep(1.5)
    for target, v in (('D100', 0x1234), ('D101', 0xABCD), ('M1', 0x0203), ('D20', 0x5678), ('D21', 0x9ABC), ('M2', 0x00F0)):
        cts.write(did, target, v, data_type='INTEGER')
    cts.add_simulation(did, 's10', 'D10', [cts.policy(kind='STATIC', data_type='INTEGER', value=777)], interval=1)
    ctx.given('GENERIC TCP 장비(xgt-fenet)에 REST 로 D100=0x1234 D101=0xABCD M1=0x0203 D20=0x5678 D21=0x9ABC M2=0x00F0, '
              'Value Simulation 으로 D10=777(STATIC) 을 넣는다')
    time.sleep(2.5)

    s = socket.create_connection(('127.0.0.1', port), 5)
    try:
        req = frame(read_req(2, ['%DW100', '%DW101', '%DW10']), invoke=0x0102)
        s.sendall(req)
        want = reply(read_ack(2, [le16(0x1234), le16(0xABCD), le16(777)]), invoke=0x0102)
        r = recv(s, len(want))
        ctx.check(r == want, '%DW100 %DW101 %DW10 개별 읽기 — 값은 little endian, Invoke ID 0x0102 를 되돌린다', show(r))

        s.sendall(frame(read_req(3, ['%DD10'])))
        want = reply(read_ack(3, [(0x9ABC5678).to_bytes(4, 'little')]))
        r = recv(s, len(want))
        ctx.check(r == want, '%DD10 = D20·D21 (아래 워드가 먼저)', show(r))

        s.sendall(frame(read_req(1, ['%MB3'])))
        want = reply(read_ack(1, [b'\x02']))
        r = recv(s, len(want))
        ctx.check(r == want, '%MB3 = M1 의 윗바이트 0x02', show(r))

        s.sendall(frame(read_req(0, ['%MX16', '%MX17', '%MX36'])))
        want = reply(read_ack(0, [b'\x01', b'\x01', b'\x01']))
        r = recv(s, len(want))
        ctx.check(r == want, '%MX16 %MX17 %MX36 = M1 bit0·bit1, M2 bit4 (비트는 한 바이트 00/01)', show(r))

        s.sendall(frame(read_req(0x14, ['%DB201'], 3)))
        want = reply(read_ack(0x14, [bytes([0x12, 0xCD, 0xAB])]))
        r = recv(s, len(want))
        ctx.check(r == want, '연속 읽기 %DB201 3바이트 = D100 윗바이트 + D101', show(r))

        ctx.given('패킷으로 %DW7=0xBEEF, %MX33=1, 연속 %DB11 3바이트(11 22 33)를 쓴다')
        s.sendall(frame(write_req(2, ['%DW7'], [le16(0xBEEF)])))
        r = recv(s, 30)
        s.sendall(frame(write_req(0, ['%MX33'], [b'\x01'])))
        r2 = recv(s, 30)
        s.sendall(frame(write_req(0x14, ['%DB11'], [b'\x11\x22\x33'])))
        r3 = recv(s, 30)
        time.sleep(0.3)
        f = fields(did)
        ctx.check(r == reply(write_ack(2, 1)) and r2 == reply(write_ack(0, 1)) and r3 == reply(write_ack(0x14, 1)),
                  '세 쓰기 모두 ACK (0x0059, Error status 0)', '%s / %s / %s' % (show(r), show(r2), show(r3)))
        ctx.check(f.get('D7') == 0xBEEF and f.get('M2') == 0x00F2 and f.get('D5') == 0x1100 and f.get('D6') == 0x3322,
                  'Server Data: D7=48879, M2=0x00F2(bit1 켬), D5=0x1100(윗바이트만), D6=0x3322',
                  'D7=%s M2=%s D5=%s D6=%s' % (f.get('D7'), f.get('M2'), f.get('D5'), f.get('D6')))

        ctx.given('REST 로 D8=5 를 60초 잠근 뒤 패킷으로 %DW8=9 를 쓴다')
        cts.write(did, 'D8', 5, data_type='INTEGER', lock_seconds=60)
        s.sendall(frame(write_req(2, ['%DW8'], [le16(9)])))
        r = recv(s, 30)
        time.sleep(0.3)
        ctx.check(r == reply(write_ack(2, 1)) and fields(did).get('D8') == 5,
                  '응답은 ACK 이지만 잠긴 Target 은 바뀌지 않는다', '%s D8=%s' % (show(r), fields(did).get('D8')))

        ctx.given('같은 요청을 둘로 쪼개 보내고, 앞에 잡음 2바이트를 붙인 두 요청을 한 번에 보낸다')
        req = frame(read_req(2, ['%DW100']), invoke=7)
        want = reply(read_ack(2, [le16(0x1234)]), invoke=7)
        s.sendall(req[:11])
        time.sleep(0.05)
        s.sendall(req[11:])
        r = recv(s, len(want))
        ctx.check(r == want, '쪼개진 프레임은 모아서 하나로 읽는다', show(r))
        s.sendall(b'\xFF\x00' + req + req)
        r = recv(s, 2 * len(want))
        ctx.check(r == want * 2, '잡음은 버리고(0x4C 까지 다시 맞춤), 두 요청에 각각 답한다', show(r))

        ctx.given('범위 밖 %DW512, 없는 디바이스 %QW0, 모르는 명령 0x0060, PLC 방향 프레임(0x11)을 보낸다')
        s.sendall(frame(read_req(2, ['%DW512'])))
        r = recv(s, 30)
        ctx.check(r == reply(nak(0x54, 2, 4)), '영역 밖 → NAK 0x0004', show(r))
        s.sendall(frame(read_req(2, ['%QW0'])))
        r = recv(s, 30)
        ctx.check(r == reply(nak(0x54, 2, 3)), '없는 디바이스 → NAK 0x0003', show(r))
        s.sendall(frame(le16(0x60) + le16(2) + le16(0)))
        r = recv(s, 30)
        ctx.check(r == reply(nak(0x60, 2, 0x78)), '모르는 명령 → NAK 0x0078', show(r))
        s.sendall(frame(read_req(2, ['%DW100']), source=0x11))
        r = recv(s, 30, wait=1.0)
        ctx.check(r == b'', 'Source of Frame 이 0x33 이 아니면 답하지 않는다', show(r))
    finally:
        s.close()
    return did


def _udp(ctx, xgt, port):
    name = cts.PREFIX + 's25_udp'
    cts.add_device(name, port, protocol='GENERIC', transport='UDP', settings=cts.generic_settings(xgt, cpuInfo=0xB0, position=0x12))
    did = device_id(name)
    time.sleep(1.5)
    cts.write(did, 'R3', 0x0BB8, data_type='INTEGER')
    ctx.given('GENERIC UDP 장비(cpuInfo 0xB0 XGB, position 0x12)에 R3=3000 을 넣고 %RW3 을 읽는다')
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.settimeout(2)
    try:
        u.sendto(frame(read_req(2, ['%RW3']), invoke=0x55AA), ('127.0.0.1', port))
        r, _ = u.recvfrom(4096)
    except socket.timeout:
        r = b''
    finally:
        u.close()
    body = read_ack(2, [le16(3000)])
    h = LSIS + le16(0x0101) + bytes([0xB0, 0x11]) + le16(0x55AA) + le16(len(body)) + b'\x12'
    want = h + bytes([sum(h) & 0xFF]) + body
    ctx.check(r == want, 'UDP 데이터그램 하나에 응답 하나, CPU Info·FEnet 위치는 장비 파라미터', show(r))
