"""S21 — 타입이 있는 프로토콜의 값 표현 (설명서 VI장, 부록 A).

Modbus·XGT 는 raw 레지스터라 data_type 이 바이트 모양을 그대로 정한다. 그 밖의
프로토콜은 값을 맵에 담아 두었다가 보낼 때 타입을 정하는데, 그 규칙이 셋 다 다르다.

  SNMP         data_type 이 BER 타입을 정한다 (정수는 Integer32 / Unsigned32)
  BACnet       object type 이 표현 타입을 정한다 (규격). analog=REAL, binary=ENUMERATED,
               multi-state=Unsigned
  EtherNet/IP  target 접두어가 CIP 타입을 정한다. data_type 이 어긋나면 등록을 거절한다

값만 보면 어느 쪽이든 같아 보이므로, 타입까지 확인하려면 표준 클라이언트가 받는 바이트를
봐야 한다. SNMP 는 snmpclient 로 GET 을 직접 만들고, BACnet 은 ReadProperty 응답의
application tag 를 읽는다.
"""
import socket
import struct
import sys
import time
import cts

NAME = '타입이 있는 프로토콜의 값 표현'

#: (data_type, 넣을 값, 기대하는 BER 타입)
SNMP_CASES = [
    ('SI16AB', 1234,  'Integer32'),
    ('UI8',    200,   'Integer32'),
    ('UI32AD', 70000, 'Gauge32'),
    ('FP32AD', 12.5,  'OctetString'),   # SNMP 에 실수 타입이 없다
]

#: (target, 넣을 값, 기대하는 BACnet application tag)
BACNET_CASES = [
    ('analog_value 1',      12.5, 'real'),
    ('binary_value 2',      1,    'enum'),
    ('multi_state_value 3', 3,    'uint'),
]

#: (target, data_type, 등록되어야 하는가)
ENIP_CASES = [
    ('REAL@T1', 'FP32AD', True),
    ('REAL@T2', 'UI16AB', False),
    ('DINT@T3', 'SI32AD', True),
    ('DINT@T4', 'FP32AD', False),
    ('INT@T5',  'UI16AB', True),
    ('SINT@T6', 'UI8',    True),
    ('SINT@T7', 'SI32AD', False),
    ('BOOL@T8', 'UI8',    True),
]


def _bacnet_read(port, instance, obj_type, inst):
    """tools/mk119_bacnet_client.py 로 ReadProperty 를 보내고 출력 마지막 줄을 돌려준다."""
    import subprocess, os
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    client = os.path.join(root, 'mk119_bacnet_client.py')
    out = subprocess.run(
        [sys.executable, client, 'read', '--host', '127.0.0.1', '--port', str(port),
         '--device', str(instance), '--type', obj_type, '--inst', str(inst)],
        capture_output=True, text=True, timeout=30).stdout.strip().split('\n')
    return out[-1].strip() if out else ''


def run(ctx):
    # ── SNMP ───────────────────────────────────────────────────────────
    import snmpclient
    port = cts.PORT_BASE + 61
    dev = cts.add_device(cts.PREFIX + 's21snmp', port, protocol='SNMP', transport='UDP',
                         settings={'snmpVersion': '0', 'getCommunity': 'public',
                                   'setCommunity': 'private'})
    did = dev['id']
    try:
        oids = []
        for i, (dt, value, _) in enumerate(SNMP_CASES):
            oid = '.1.3.6.1.4.1.9999.%d' % (i + 1)
            oids.append(oid)
            cts.add_simulation(did, 'v%d' % i, oid,
                               [cts.policy(kind='STATIC', data_type=dt, value=value)],
                               interval=1)
        time.sleep(4)
        ctx.given('SNMP 장비(v1, community public)에 data_type 만 달리한 STATIC 4건을 '
                  'OID .1.3.6.1.4.1.9999.1~4 에 주기 1초로 등록한다 '
                  '(SI16AB=1234, UI32AD=70000, FP32AD=12.5, UI8=200). '
                  '라이브러리 없이 BER 로 SNMP v1 GET 패킷을 직접 만들어 보내고, '
                  '응답 varbind 의 타입 태그를 그대로 읽는다 — 표준 매니저가 보는 것과 같다')
        for oid, (dt, value, expect) in zip(oids, SNMP_CASES):
            got_type, got_value = snmpclient.value_of(
                snmpclient.get('127.0.0.1', port, 'public', oid), oid)
            ctx.check(got_type == expect,
                      'data_type %s 는 BER 타입 %s 로 나간다. 전에는 무엇을 넣든 '
                      'OctetString("%s") 문자열이라 매니저가 숫자로 읽지 못했다'
                      % (dt, expect, value),
                      '%s, 값 %r' % (got_type, got_value))
    finally:
        cts.delete_device_tree(did)

    # ── BACnet ─────────────────────────────────────────────────────────
    # 클라이언트가 device instance 를 NPDU DADR 1바이트로 보내므로 255 이하를 쓴다.
    instance, port = 55, cts.PORT_BASE + 70
    dev = cts.add_device(cts.PREFIX + 's21bacnet', port, protocol='BACNET', transport='UDP',
                         settings={'deviceInstanceId': str(instance),
                                   'broadcast': '255.255.255.255'})
    did = dev['id']
    try:
        for i, (target, value, _) in enumerate(BACNET_CASES):
            cts.add_simulation(did, 'o%d' % i, target,
                               [cts.policy(kind='STATIC', data_type='FP32AD', value=value)],
                               interval=1)
        time.sleep(4)
        ctx.given('BACnet 장비(device instance 55)에 object 세 종류를 둔다: '
                  'analog_value 1=12.5, binary_value 2=1, multi_state_value 3=3. '
                  '표준 ReadProperty 로 present-value(0x55)를 읽어 응답 APDU 의 '
                  'application tag 를 본다. 타입은 data_type 이 아니라 object type 이 '
                  '규격으로 정한다')
        for target, value, expect in BACNET_CASES:
            obj_type, inst = target.rsplit(' ', 1)
            line = _bacnet_read(port, instance, obj_type.replace('_', '-'), int(inst))
            ctx.check(line.startswith(expect + ':'),
                      '%s 의 present-value 가 %s 태그로 응답한다. 전에는 object type 과 '
                      '무관하게 모두 real 이라 표준 클라이언트가 타입이 어긋난 값을 받았다'
                      % (target, expect),
                      line or '(응답 없음)')
    finally:
        cts.delete_device_tree(did)

    # ── EtherNet/IP ────────────────────────────────────────────────────
    dev = cts.add_device(cts.PREFIX + 's21enip', cts.PORT_BASE + 75, protocol='ETHERNET_IP',
                         settings={'cpuSlot': '0', 'syn_port': '2244', 'registers': '100',
                                   'implicit_t2o_addr': '127.0.0.1'}, enabled=0)
    did = dev['id']
    try:
        ctx.given('EtherNet/IP 장비에 target 접두어와 data_type 의 조합 8가지를 등록해 본다. '
                  'CIP 타입은 target 접두어가 정하므로(REAL@TAG1) data_type 이 어긋나면 '
                  '화면에서 고른 것과 클라이언트가 읽는 것이 달라진다. '
                  '맞는 짝 5건(REAL+FP32AD, DINT+SI32AD, INT+UI16AB, SINT+UI8, BOOL+UI8), '
                  '어긋난 짝 3건(REAL+UI16AB, DINT+FP32AD, SINT+SI32AD)')
        accepted = rejected = 0
        detail = []
        for i, (target, dt, should_pass) in enumerate(ENIP_CASES):
            st, body = cts.api('/simulation/addSimulation', 'PUT', {
                'device': {'id': did}, 'name': 'c%d' % i, 'target': target,
                'policies': [cts.policy(kind='STATIC', data_type=dt, value=1)],
                'interval': 60, 'enabled': 0})
            ok = (200 <= st < 300) == should_pass
            if not ok:
                detail.append('%s + %s 가 기대와 다름(%d)' % (target, dt, st))
            elif should_pass:
                accepted += 1
            else:
                rejected += 1
        ctx.check(not detail,
                  '맞는 %d건은 등록되고 어긋나는 %d건은 등록 시점에 거절된다. '
                  '폭과 정수/실수 구분만 보고, 바이트 순서(AB/BA)는 CIP 가 '
                  'little-endian 으로 고정하므로 따지지 않는다' % (accepted, rejected),
                  '; '.join(detail) if detail else
                  '수용 %d건, 거절 %d건 모두 기대대로' % (accepted, rejected))
    finally:
        cts.delete_device_tree(did)
