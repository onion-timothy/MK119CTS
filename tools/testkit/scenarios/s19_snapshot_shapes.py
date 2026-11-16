"""S19 — 프로토콜별 ServerData 스냅샷 구조 (설명서 VIII장).

GET /api/devices/{id}/serverdata 가 돌려주는 payload 는 프로토콜마다 모양이 다르다.
Server Data 화면과 외부 연동이 모두 이 모양에 맞춰 그리므로, 프로토콜을 하나씩 세워
약속된 키가 그 자리에 있는지와 Simulation 이 쓴 값이 그 안에서 보이는지를 본다.

  MODBUS       registers / coilStatus / inputStatus / holdingRegisters / inputRegisters
  XGT_PLC      blocks (M D P K S U)
  OPC_AGENT    values
  DAEYUN_RCU   analogChannels / digitalChannels / txFields
  SNMP         oids
  ETHERNET_IP  tags
  GENERIC      fields
  BACNET       objects (target / simulationId / name / object / value)
"""
import time
import cts

NAME = '프로토콜별 ServerData 스냅샷 구조'

#: (프로토콜, 전송, 통신설정, payload 에 있어야 하는 키, 값을 심을 target, data_type, 값)
CASES = [
    ('MODBUS', 'TCP', cts.modbus_settings(registers=1000),
     ['registers', 'coilStatus', 'inputStatus', 'holdingRegisters', 'inputRegisters'],
     '3_10', 'UI16AB', 1234),

    ('XGT_PLC', 'TCP', {'cpuId': '1', 'maxBlockSize': '1400', 'registers': '10000'},
     ['blocks'], '%MW100', 'UI16AB', 777),

    ('OPC_AGENT', 'UDP', {'registers': '100'},
     ['values'], '7', 'INTEGER', 55),

    ('DAEYUN_RCU', 'TCP',
     cts.rcu_settings(),
     ['analogChannels', 'digitalChannels', 'txFields'],
     '@RCUAI_3', 'INTEGER', 42),

    ('SNMP', 'UDP',
     {'snmpVersion': '0', 'getCommunity': 'public', 'setCommunity': 'private'},
     ['oids'], '1.3.6.1.2.1.1.1.0', 'INTEGER', 9),

    ('ETHERNET_IP', 'TCP',
     cts.eip_settings(syn_port=2231),
     ['tags'], 'DINT@ZZTAG', 'SI32DA', 321),

    # 설정은 run 이 프로토콜 정의(power-meter 예시)를 만든 뒤 채운다. 전압1 은 UI16 ×0.1 이라 FLOAT 를 받는다
    ('GENERIC', 'TCP', None,
     ['fields'], '전압1', 'FLOAT', 220),

    ('BACNET', 'UDP', {'deviceInstanceId': '4399', 'broadcast': '255.255.255.255'},
     ['objects'], 'analog_value 3', 'FLOAT', 12.5),
]

#: 스냅샷 안에서 심은 값을 찾아보는 방법. payload 모양이 프로토콜마다 달라서 따로 둔다.
def _value_seen(protocol, payload, want):
    if protocol == 'MODBUS':
        return payload['holdingRegisters'][10] == want
    if protocol == 'XGT_PLC':
        m = payload['blocks']['M']
        # %MW100 = 워드 100 = 바이트 200,201
        return (m[200] << 8 | m[201]) == want or (m[201] << 8 | m[200]) == want
    if protocol == 'OPC_AGENT':
        vals = payload['values']
        got = vals.get('7') if isinstance(vals, dict) else vals[7]
        return int(got) == want
    if protocol == 'DAEYUN_RCU':
        # @RCUAI_n 의 채널 번호는 1-base 다. 3번 채널은 배열의 2번 칸.
        return int(payload['analogChannels'][2]) == want
    if protocol == 'SNMP':
        return str(want) in str(payload['oids'])
    if protocol == 'ETHERNET_IP':
        return str(want) in str(payload['tags'])
    if protocol == 'GENERIC':
        return str(want) in str(payload['fields'])
    if protocol == 'BACNET':
        objs = payload['objects']
        return bool(objs) and abs(float(objs[0]['value']) - float(want)) < 0.01
    return False


def run(ctx):
    port = cts.PORT_BASE + 120
    definition = cts.add_definition(cts.PREFIX + 's19_def', 'power-meter')
    for protocol, transport, settings, keys, target, dtype, want in CASES:
        port += 1
        if protocol == 'GENERIC':
            settings = cts.generic_settings(definition)
        dev = cts.add_device(cts.PREFIX + 's19_' + protocol.lower(), port,
                             protocol=protocol, transport=transport,
                             settings=settings)
        did = dev['id']
        try:
            cts.add_simulation(did, 'v', target,
                               [cts.policy(kind='STATIC', data_type=dtype, value=want)],
                               interval=1)
            time.sleep(4)
            snap = cts.snapshot(did)
            payload = snap.get('payload') or {}

            ctx.given('%s 장비를 세우고(통신설정 %s) STATIC %s → %s 를 data_type %s, '
                      '주기 1초로 등록한다. 4초 뒤 GET /api/devices/{id}/serverdata 로 '
                      '스냅샷을 받는다. payload 모양은 프로토콜마다 달라서, 화면과 외부 연동이 '
                      '이 구조에 맞춰 그린다'
                      % (protocol, settings, want, target, dtype))
            ctx.check(snap.get('protocol') == protocol,
                      '%s: protocol 필드로 어떤 모양인지 구분할 수 있다' % protocol,
                      snap.get('protocol'))
            missing = [k for k in keys if k not in payload]
            ctx.check(not missing,
                      '%s: payload 에 약속된 키가 모두 있다 (%s). 하나라도 빠지면 '
                      '화면이 그 자리에서 깨진다' % (protocol, ', '.join(keys)),
                      ('빠진 키 %s' % missing) if missing else ('키 %s' % list(payload.keys())))
            ctx.check(isinstance(snap.get('locks'), list),
                      '%s: 값과 함께 locks(쓰기 보호 중인 target 목록)도 내려온다' % protocol,
                      snap.get('locks'))
            try:
                seen = _value_seen(protocol, payload, want)
                detail = ''
            except Exception as e:
                seen, detail = False, '%s: %s' % (type(e).__name__, e)
            ctx.check(seen,
                      '%s: Simulation 이 쓴 값 %s 가 그 프로토콜의 자리에서 읽힌다 '
                      '(구조만 맞고 값이 안 들어가는 경우를 걸러 낸다)' % (protocol, want),
                      detail or ('값 %s 확인' % want))
        finally:
            cts.delete_device_tree(did)
    cts.api('/protocols/%d' % definition, 'DELETE')
