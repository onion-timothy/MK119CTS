"""S09 — 부록 A: 프로토콜별 Target 형식.

설명서 부록 A 가 약속한 형식과 제약을 프로토콜 9종에 대해 그대로 두드린다.
맞는 형식은 등록되고, 틀린 형식은 거절되어야 한다. 조용히 통과하면 그 Simulation 은
영영 아무 일도 하지 않으므로, 거절되는 쪽이 더 중요하다.

EtherNet/IP 는 target 접두어가 CIP 타입을 정하므로 data_type 도 거기 맞아야 한다.
형식만 보려는 시나리오라 맞는 짝을 CIP_DATA_TYPE 에 적어 두고 쓴다.
(접두어와 data_type 이 어긋나는 경우는 s21 이 따로 본다.)
"""
import cts

NAME = '프로토콜별 Target 형식 (부록 A)'

#: EtherNet/IP 의 CIP 타입별로 짝이 되는 data_type
CIP_DATA_TYPE = {'BOOL': 'BINARY', 'SINT': 'SI8', 'INT': 'SI16BA',
                 'DINT': 'SI32DA', 'REAL': 'FP32DA'}


#: (프로토콜, 전송, 통신설정, [맞는 target], [틀린 target])
CASES = [
    ('MODBUS', 'TCP', cts.modbus_settings(registers=1000),
     ['3_10', '1_0', '4_999'],
     ['3-10', 'x_1', '5_1', '3_99999', '']),

    ('XGT_PLC', 'TCP', {'cpuId': '1', 'maxBlockSize': '1400', 'registers': '10000'},
     ['%MW100', '%DB300', '%MB10'],
     ['MW100', '%ZW100', '%MX100', '%MW', '%M']),

    ('DAEYUN_RCU', 'TCP',
     cts.rcu_settings(di=32, do=32, ai=8),
     ['@RCUDI_1', '@RCUDI_32', '@RCUAI_8'],
     ['@RCUDI_0', '@RCUDI_33', '@RCUAI_9', '@RCUDI_x']),

    ('SNMP', 'UDP',
     {'snmpVersion': '0', 'getCommunity': 'public', 'setCommunity': 'private'},
     ['1.3.6.1.2.1.1.1.0', '.1.3.6.1'],
     ['1', 'a.b.c', '1.3.6.1.', '']),

    ('OPC_AGENT', 'UDP', {'registers': '100'},
     ['0', '5', '99'],
     ['100', '-1', 'abc']),

    ('ETHERNET_IP', 'TCP',
     cts.eip_settings(),
     ['REAL@TAG1', 'DINT@Motor_Speed', 'BOOL@Flag'],
     ['TAG1', 'FLOAT@TAG1', 'REAL@', '@TAG1']),

    ('BACNET', 'UDP', {'deviceInstanceId': '4321', 'broadcast': '255.255.255.255'},
     ['analog_input 1', 'binary_value 7'],
     ['analog_input', 'nope 1', 'analog_input x']),

    # GENERIC 은 장비의 프로토콜 정의(power-meter 예시)가 선언한 타겟만 받는다. 설정은 run 이 정의를 만든 뒤 채운다
    ('GENERIC', 'TCP', None,
     ['전압1', '전력량'], ['', 'voltage1', 'anything-goes']),

    # DB 는 형식 제한이 없다. 비어 있는 것만 거절된다.

    ('DB', 'TCP',
     {'dbdriver': 'org.mariadb.jdbc.Driver', 'dbUrl': 'jdbc:mariadb://127.0.0.1:3306/x',
      'dbUsername': 'u', 'dbPassword': 'p', 'dbDatabase': 'x'},
     ['UPDATE t SET v = ${v1}'], ['']),
]


def run(ctx):
    port = cts.PORT_BASE + 100
    definition = cts.add_definition(cts.PREFIX + 's09_def', 'power-meter')
    for protocol, transport, settings, good, bad in CASES:
        port += 1
        if protocol == 'GENERIC':
            settings = cts.generic_settings(definition)
        dev = cts.add_device(cts.PREFIX + 's09_' + protocol.lower(), port,
                             protocol=protocol, transport=transport,
                             settings=settings, enabled=0)
        did = dev['id']
        try:
            okc = badc = 0
            bad_detail = []
            # 이름은 장비 안에서 고유해야 한다. 같은 이름을 쓰면 target 과 무관하게 거절된다.
            for i, t in enumerate(good):
                # 형식만 보려는 시나리오라 그 target 이 받는 첫 타입을 쓴다(타입 규칙은 s21 이 본다)
                dt = CIP_DATA_TYPE.get(t.split('@')[0], 'SI16BA') \
                    if protocol == 'ETHERNET_IP' else cts.types_for(protocol, t)[0]
                st, _ = cts.api('/simulation/addSimulation', 'PUT', {
                    'device': {'id': did}, 'name': 'g%d' % i, 'target': t,
                    'policies': [cts.policy(value=1, data_type=dt)],
                    'interval': 60, 'enabled': 0})
                if 200 <= st < 300:
                    okc += 1
                else:
                    bad_detail.append('수용되어야 할 %r 가 거절됨' % t)
            for i, t in enumerate(bad):
                st, body = cts.api('/simulation/addSimulation', 'PUT', {
                    'device': {'id': did}, 'name': 'b%d' % i, 'target': t,
                    'policies': [cts.policy(value=1, data_type=cts.types_for(protocol, t)[0])],
                    'interval': 60, 'enabled': 0})
                if 200 <= st < 300:
                    bad_detail.append('거절되어야 할 %r 가 수용됨' % t)
                else:
                    badc += 1
            ctx.given('%s 장비를 세우고(통신설정 %s) PUT /api/simulation/addSimulation 으로 '
                      'target 만 바꿔 가며 등록을 시도한다. 수용 기대 %d건 %s, '
                      '거절 기대 %d건 %s. 목록은 설명서 부록 A 의 예시와 제약을 그대로 옮긴 것이다'
                      % (protocol, settings, len(good), good, len(bad), bad))
            ctx.check(okc == len(good) and badc == len(bad),
                      '%s: 맞는 형식 %d건은 모두 등록되고, 틀린 형식 %d건은 모두 거절된다. '
                      '틀린 target 이 조용히 통과하면 그 Simulation 은 영영 아무 일도 하지 않는다'
                      % (protocol, len(good), len(bad)),
                      '수용 %d/%d, 거절 %d/%d%s'
                      % (okc, len(good), badc, len(bad),
                         (' — ' + '; '.join(bad_detail)) if bad_detail else ''))
        finally:
            cts.delete_device_tree(did)
    cts.api('/protocols/%d' % definition, 'DELETE')
