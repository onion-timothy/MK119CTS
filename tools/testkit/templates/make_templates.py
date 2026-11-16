#!/usr/bin/env python3
"""일괄 등록용 엑셀 템플릿을 만든다.

파이썬 엑셀 라이브러리를 설치하지 않으려고, 앱이 화면에서 쓰는 SheetJS 를 그대로
빌려 쓴다. 읽는 쪽과 쓰는 쪽이 같은 라이브러리라 형식이 어긋날 일이 없다.
서버가 떠 있어야 한다 (xlsx.full.min.js 를 서버에서 받는다).

  python3 tools/testkit/templates/make_templates.py
"""
import base64
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import cts          # noqa: E402
import cdp          # noqa: E402

MB = '{"unitId":"%s","packetType":"1","registers":"30000"}'

# 1번 시트: 장비. 마지막 한 행은 일부러 틀린다 — 검증 표시를 문서와 테스트에서
# 함께 확인하려면 전부 통과하는 파일로는 안 된다. SNMP 는 0~1 만 지원한다.
DEVICES = [
    {'name': 'idpm300_test-11', 'host': '0.0.0.0', 'transport': 'TCP', 'port': 5031,
     'protocol': 'MODBUS', 'communicationSettings': MB % '1', 'enabled': 1},
    {'name': 'idpm300_test-12', 'host': '0.0.0.0', 'transport': 'TCP', 'port': 5032,
     'protocol': 'MODBUS', 'communicationSettings': MB % '1', 'enabled': 1},
    {'name': 'idpm300_test-13', 'host': '0.0.0.0', 'transport': 'TCP', 'port': 5033,
     'protocol': 'MODBUS', 'communicationSettings': MB % '2', 'enabled': 1},
    {'name': 'opc_agent_01', 'host': '0.0.0.0', 'transport': 'UDP', 'port': 9999,
     'protocol': 'OPC_AGENT', 'communicationSettings': '{"registers":"4096"}', 'enabled': 1},
    {'name': 'ups_snmp_01', 'host': '0.0.0.0', 'transport': 'UDP', 'port': 1161,
     'protocol': 'SNMP', 'enabled': 1,
     'communicationSettings':
         '{"snmpVersion":"3","getCommunity":"public","setCommunity":"private"}'},
]


def _sims_for(device):
    return [
        {'deviceName': device, 'name': 'Active Power', 'target': '3_100', 'interval': 1,
         'enabled': 1, 'id': 'v1', 'policy': 'RANDOM', 'data_type': 'UI16AB',
         'value': None, 'range': '100,900', 'stopOnControl': 0},
        {'deviceName': device, 'name': 'Voltage R', 'target': '3_102', 'interval': 1,
         'enabled': 1, 'id': 'v1', 'policy': 'RANDOM', 'data_type': 'UI16AB',
         'value': None, 'range': '2180,2420', 'stopOnControl': 0},
        {'deviceName': device, 'name': 'Breaker State', 'target': '1_10', 'interval': 5,
         'enabled': 1, 'id': 'v1', 'policy': 'STATIC', 'data_type': 'UI16AB',
         'value': 1, 'range': None, 'stopOnControl': 10},
    ]


SIMULATIONS = (_sims_for('idpm300_test-11') + _sims_for('idpm300_test-12')
               + _sims_for('idpm300_test-13')
               + [{'deviceName': 'opc_agent_01', 'name': 'Tank Level', 'target': '12',
                   'interval': 2, 'enabled': 1, 'id': 'v1', 'policy': 'ACCUMULATE',
                   'data_type': 'SI16AB', 'value': None, 'range': '220,1',
                   'stopOnControl': 0}])

# 이미 등록된 장비에 시뮬레이션만 추가하는 템플릿. deviceName 열이 없다.
SIMS_ONLY = [
    {'name': 'Alarm Delay', 'target': '3_6960', 'interval': 10, 'enabled': 1,
     'id': 'v1', 'policy': 'STATIC', 'data_type': 'UI16AB', 'value': 6128,
     'range': None, 'stopOnControl': 0},
    {'name': 'Active Power', 'target': '3_200', 'interval': 1, 'enabled': 1,
     'id': 'v1', 'policy': 'RANDOM', 'data_type': 'UI16AB', 'value': None,
     'range': '100,900', 'stopOnControl': 0},
    {'name': 'Voltage R', 'target': '3_202', 'interval': 1, 'enabled': 1,
     'id': 'v1', 'policy': 'RANDOM', 'data_type': 'UI16AB', 'value': None,
     'range': '2180,2420', 'stopOnControl': 0},
    {'name': 'Voltage S', 'target': '3_204', 'interval': 1, 'enabled': 1,
     'id': 'v1', 'policy': 'RANDOM', 'data_type': 'UI16AB', 'value': None,
     'range': '2180,2420', 'stopOnControl': 0},
    {'name': 'Current R', 'target': '3_206', 'interval': 1, 'enabled': 1,
     'id': 'v1', 'policy': 'ACCUMULATE', 'data_type': 'UI16AB', 'value': None,
     'range': '50,2', 'stopOnControl': 0},
    {'name': 'Run Hours', 'target': '3_210', 'interval': 60, 'enabled': 1,
     'id': 'v1', 'policy': 'ACCUMULATE', 'data_type': 'UI16AB', 'value': None,
     'range': '0,1', 'stopOnControl': 0},
    {'name': 'Breaker State', 'target': '1_20', 'interval': 5, 'enabled': 1,
     'id': 'v1', 'policy': 'STATIC', 'data_type': 'UI16AB', 'value': 1,
     'range': None, 'stopOnControl': 30},
    {'name': 'Fault Flag', 'target': '1_21', 'interval': 5, 'enabled': 0,
     'id': 'v1', 'policy': 'STATIC', 'data_type': 'UI16AB', 'value': 0,
     'range': None, 'stopOnControl': 0},
]

# S18 전용. Register 를 실제로 누르는 시나리오라 이름은 지울 수 있게 ZZTMP_ 로 두고
# 포트는 테스트 구간(15900+)을 쓴다. 오류 행은 두지 않는다 — 오류가 있으면 Register
# 버튼이 잠겨 커밋 경로를 볼 수 없다.
COMMIT_DEVICES = [
    {'name': 'ZZTMP_s18a', 'host': '0.0.0.0', 'transport': 'TCP', 'port': 15971,
     'protocol': 'MODBUS', 'communicationSettings': MB % '1', 'enabled': 1},
    {'name': 'ZZTMP_s18b', 'host': '0.0.0.0', 'transport': 'TCP', 'port': 15972,
     'protocol': 'MODBUS', 'communicationSettings': MB % '2', 'enabled': 1},
]

COMMIT_SIMULATIONS = [
    {'deviceName': 'ZZTMP_s18a', 'name': 'Active Power', 'target': '3_100',
     'interval': 1, 'enabled': 1, 'id': 'v1', 'policy': 'STATIC',
     'data_type': 'UI16AB', 'value': 4321, 'range': None, 'stopOnControl': 0},
    {'deviceName': 'ZZTMP_s18a', 'name': 'Breaker State', 'target': '1_10',
     'interval': 5, 'enabled': 1, 'id': 'v1', 'policy': 'STATIC',
     'data_type': 'UI16AB', 'value': 1, 'range': None, 'stopOnControl': 12},
    {'deviceName': 'ZZTMP_s18b', 'name': 'Voltage R', 'target': '3_102',
     'interval': 1, 'enabled': 1, 'id': 'v1', 'policy': 'RANDOM',
     'data_type': 'UI16AB', 'value': None, 'range': '2180,2420', 'stopOnControl': 0},
]

PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8">
<script src="%s/js/xlsx.full.min.js"></script></head><body><script>
function build(sheets) {
  const wb = XLSX.utils.book_new();
  sheets.forEach(s => XLSX.utils.book_append_sheet(
      wb, XLSX.utils.json_to_sheet(s.rows), s.name));
  return XLSX.write(wb, { bookType: 'xlsx', type: 'base64' });
}
</script></body></html>""" % cts.BASE


def main():
    if not cts.server_up():
        print('서버(%s)가 떠 있어야 한다.' % cts.BASE)
        return 1
    page = os.path.join(HERE, '_mkxlsx.html')
    open(page, 'w').write(PAGE)
    c = cdp.Chrome(width=900, height=600, scale=1)
    try:
        c.goto('file://' + page, wait=3)
        if c.js('typeof XLSX') != 'object':
            print('SheetJS 를 받지 못했다.')
            return 1
        out = {
            'batch_devices.xlsx': [{'name': 'Devices', 'rows': DEVICES},
                                   {'name': 'Simulations', 'rows': SIMULATIONS}],
            'batch_simulations.xlsx': [{'name': 'Simulations', 'rows': SIMS_ONLY}],
            'batch_commit.xlsx': [{'name': 'Devices', 'rows': COMMIT_DEVICES},
                                  {'name': 'Simulations', 'rows': COMMIT_SIMULATIONS}],
        }
        for fname, sheets in out.items():
            b64 = c.js('build(%s)' % json.dumps(sheets), wait=0.4)
            path = os.path.join(HERE, fname)
            open(path, 'wb').write(base64.b64decode(b64))
            print('  %-24s %d bytes' % (fname, os.path.getsize(path)))
    finally:
        c.close()
        os.remove(page)
    return 0


if __name__ == '__main__':
    sys.exit(main())
