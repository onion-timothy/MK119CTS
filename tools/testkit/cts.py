#!/usr/bin/env python3
"""MK119CTS 시나리오 테스트 공용 모듈 (순수 stdlib, 설치 불필요).

서버를 실제로 띄운 상태에서 REST API 로 두드려 보는 통합 테스트다.
단위 테스트가 아니라, 주기 작업/잠금/에러 상태처럼 '시간이 지나야 드러나는'
동작을 확인하는 것이 목적이라 대부분의 시나리오가 수 초씩 기다린다.

안전 규칙 (모든 시나리오가 지킨다):
  - 테스트가 만드는 장비 이름은 반드시 PREFIX 로 시작한다.
  - 시나리오는 finally 에서 자기가 만든 것을 지운다.
  - 기존 장비/시뮬레이션은 읽기만 하고 절대 고치지 않는다.
  - run.py 가 끝에 PREFIX 잔여물을 한 번 더 훑는다.
"""
import json
import os
import time
import urllib.error
import urllib.request

BASE = 'http://localhost:8082'
API = BASE + '/api'

#: 테스트가 만든 것을 식별하는 접두어. 운영 데이터와 섞이지 않게 한다.
PREFIX = 'ZZTMP_'

#: 테스트 전용 포트 대역. 실제 장비 설정과 겹치지 않게 높은 번호를 쓴다.
PORT_BASE = 15900


# ── HTTP ────────────────────────────────────────────────────────────────

def api(path, method='GET', body=None, raw=False):
    """실패 응답도 읽어야 하므로 HTTPError 를 삼키고 (status, body) 를 돌려준다."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
                                 headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as f:
            text = f.read().decode()
            status = f.status
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        status = e.code
    if raw:
        return status, text
    try:
        return status, json.loads(text)
    except ValueError:
        return status, text


def ok(path, method='GET', body=None):
    """2xx 를 기대하는 호출. 본문만 돌려준다."""
    status, payload = api(path, method, body)
    if not (200 <= status < 300):
        raise RuntimeError('%s %s -> %s %s' % (method, path, status, payload))
    return payload


def server_up():
    try:
        with urllib.request.urlopen(BASE + '/devices', timeout=5) as f:
            return f.status == 200
    except Exception:
        return False


# ── 장비 설정 틀 ────────────────────────────────────────────────────────
# 프로토콜마다 필수 키가 다르다. 빠뜨리면 디코더 생성자에서 터지면서
# 연결이 조용히 끊기므로(로그도 안 남는다) 여기 모아 둔다.

def modbus_settings(unit_id=1, registers=30000):
    return {'unitId': str(unit_id), 'packetType': '1', 'registers': str(registers)}


def eip_settings(cpu_slot=0, syn_port=2222):
    """장비 설정 검사(DeviceSettingsValidator)가 요구하는 키를 모두 채운다."""
    return {'cpuSlot': str(cpu_slot), 'syn_port': str(syn_port),
            'implicit_t2o_addr': '127.0.0.1',
            'implicit_t2o_assy_size': '0', 'implicit_o2t_assy_size': '0'}


def rcu_settings(cpu_addr=1, di=32, do=32, ai=8):
    """AI 는 1~8, DI·DO 는 1~32 (DeviceSettingsValidator)."""
    return {'cpuAddr': str(cpu_addr), 'diCount': str(di), 'doCount': str(do), 'aiCount': str(ai)}


# ── data type 표 ────────────────────────────────────────────────────────
# 화면 static/js/cts-datatypes.js · 서버 DataTypeRules 와 같은 표. 목록의 첫 타입이 기본값이다.

_SIZED = ['UI8', 'SI8', 'UI16AB', 'UI16BA', 'SI16AB', 'SI16BA',
          'UI32AD', 'UI32CB', 'UI32BC', 'UI32DA', 'SI32AD', 'SI32CB', 'SI32BC', 'SI32DA',
          'SI64AH', 'SI64GB', 'SI64BG', 'SI64HA',
          'FP32AD', 'FP32CB', 'FP32BC', 'FP32DA', 'FP64AH', 'FP64GB', 'FP64BG', 'FP64HA']
_PLAIN = ['INTEGER', 'FLOAT', 'BINARY']


def _widths(*w):
    def size(t):
        for n, b in (('8', 1), ('16', 2), ('32', 4), ('64', 8)):
            if n in t:
                return b
        return 0
    return [t for t in _SIZED if size(t) in w]


def types_for(protocol, target):
    t = str(target or '').strip()
    if protocol == 'MODBUS':
        if t[:2] in ('1_', '2_'):
            return ['BINARY']
        if t[:2] in ('3_', '4_'):
            return _widths(2, 4, 8)
        return ['BINARY'] + _widths(2, 4, 8)
    if protocol == 'XGT_PLC':
        size = t[2:3].upper()
        return {'B': _widths(1), 'W': _widths(2), 'D': _widths(4)}.get(size, _widths(1, 2, 4))
    if protocol == 'ETHERNET_IP':
        return ['SI8', 'SI16BA', 'SI32DA', 'FP32DA', 'BINARY']
    if protocol == 'BACNET':
        low = t.lower()
        if low.startswith('binary_'):
            return ['BINARY']
        if low.startswith('multi_state_'):
            return ['INTEGER']
        if low.startswith('analog_'):
            return ['FLOAT', 'INTEGER']
        return list(_PLAIN)
    if protocol == 'DAEYUN_RCU':
        if t.startswith('@RCUDI_'):
            return ['BINARY']
        if t.startswith('@RCUAI_'):
            return ['INTEGER']
        return list(_PLAIN)
    if protocol in ('SNMP', 'GENERIC', 'OPC_AGENT', 'DB'):
        return list(_PLAIN)
    return _SIZED + _PLAIN


def device_body(name, port, protocol='MODBUS', transport='TCP',
                settings=None, enabled=1, group_id=1):
    return {
        'name': name, 'host': '0.0.0.0', 'transport': transport, 'port': port,
        'protocol': protocol, 'enabled': enabled, 'log': 0,
        'group': {'id': group_id},
        'communicationSettings': settings if settings is not None else modbus_settings(),
    }


def policy(pid='v1', kind='STATIC', data_type='UI16AB', value=None, rng=None):
    return {'id': pid, 'policy': kind, 'data_type': data_type,
            'value': value, 'range': rng, 'distribution': None}


# ── 프로토콜 정의 ──────────────────────────────────────────────────────
# GENERIC·DAEYUN_RCU 장비는 저장된 정의를 가리킨다 (docs/PROTOCOL_ENGINE_DESIGN.md).
# 예시 문서(static/protocol-examples)를 이름만 바꿔 저장해 쓴다.

EXAMPLES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
                        'MK119CommunicationTestSuite', 'src', 'main', 'resources', 'static', 'protocol-examples')


def example_definition(example='power-meter'):
    with open(os.path.join(EXAMPLES, example + '.json'), encoding='utf-8') as f:
        return json.load(f)


def add_definition(name, example='power-meter', document=None):
    """정의를 저장하고 id 를 돌려준다. document 를 주면 그것을, 아니면 예시를 쓴다."""
    assert name.startswith(PREFIX), '테스트 정의 이름은 %s 로 시작해야 한다' % PREFIX
    doc = dict(document if document is not None else example_definition(example))
    doc['name'] = name
    return ok('/protocols', 'POST', {'document': doc})['definition']['id']


def generic_settings(definition_id, **params):
    cs = {'definitionId': definition_id}
    if params:
        cs['params'] = params
    return cs


# ── 생성/삭제 ───────────────────────────────────────────────────────────

def add_device(name, port, **kw):
    assert name.startswith(PREFIX), '테스트 장비 이름은 %s 로 시작해야 한다' % PREFIX
    return ok('/device/addDevice', 'PUT', device_body(name, port, **kw))


def add_simulation(device_id, name, target, policies, interval=1,
                   enabled=1, stop_on_control=0):
    return ok('/simulation/addSimulation', 'PUT', {
        'device': {'id': device_id}, 'name': name, 'target': target,
        'policies': policies, 'interval': interval, 'enabled': enabled,
        'stopOnControl': stop_on_control})


def add_error(device_id, mode, frequency=100, interval=3, duration=0,
              simulation_id=None, error_value=None, simulation_stop=0,
              timeout=0, garbage_length=0, enabled=1):
    ok('/error/addError', 'PUT', {
        'device': {'id': device_id}, 'mode': mode, 'frequency': frequency,
        'enabled': enabled, 'timeout': timeout, 'garbageLength': garbage_length,
        'simulation': {'id': simulation_id} if simulation_id else None,
        'errorValue': error_value, 'simulation_stop': simulation_stop,
        'interval': interval, 'errorDuration': duration})
    errors = ok('/error/errors/%d' % device_id)
    return errors[-1]['id'] if errors else None


def delete_device_tree(device_id):
    """장비와 거기 달린 에러/시뮬레이션을 모두 지운다. 실패해도 계속 진행한다."""
    for e in (api('/error/errors/%d' % device_id)[1] or []):
        if isinstance(e, dict):
            api('/error/deleteError/%d' % e['id'], 'DELETE')
    for s in (api('/simulation/simulations/%d' % device_id)[1] or []):
        if isinstance(s, dict):
            api('/simulation/deleteSimulation/%d' % s['id'], 'DELETE')
    api('/device/deleteDevice/%d' % device_id, 'DELETE')


def sweep():
    """남아 있는 테스트 장비와 프로토콜 정의를 모두 지운다. 지운 이름 목록을 돌려준다."""
    gone = []
    for d in (api('/device/devices')[1] or []):
        if isinstance(d, dict) and str(d.get('name', '')).startswith(PREFIX):
            delete_device_tree(d['id'])
            gone.append(d['name'])
    # 정의는 쓰는 장비가 있으면 지워지지 않으므로 장비 뒤에 지운다
    for d in (api('/protocols')[1] or []):
        if isinstance(d, dict) and str(d.get('name', '')).startswith(PREFIX):
            api('/protocols/%d' % d['id'], 'DELETE')
            gone.append(d['name'])
    return gone


# ── ServerData 읽기 ─────────────────────────────────────────────────────

def snapshot(device_id):
    return ok('/devices/%d/serverdata' % device_id)


def locks(device_id):
    return snapshot(device_id).get('locks', [])


def holding(device_id, addr):
    """MODBUS holding register 한 칸. 스냅샷은 배열을 통째로 준다."""
    regs = snapshot(device_id).get('payload', {}).get('holdingRegisters')
    return regs[addr] if regs and addr < len(regs) else None


def write(device_id, target, value, data_type='UI16AB', lock_seconds=None):
    body = {'target': target, 'value': value, 'dataType': data_type}
    if lock_seconds is not None:
        body['lockSeconds'] = lock_seconds
    return api('/devices/%d/serverdata' % device_id, 'POST', body)


def sample(device_id, addr, seconds, period=1.0):
    """초 단위로 값을 모아 리스트로 돌려준다."""
    vals = []
    for i in range(seconds):
        vals.append(holding(device_id, addr))
        if i < seconds - 1:
            time.sleep(period)
    return vals


# ── 결과 수집 ───────────────────────────────────────────────────────────

class Ctx:
    """시나리오 하나의 검증 결과를 모은다.

    보고서가 케이스별로 입력·기대·실제를 나열해야 하므로 셋을 따로 들고 있는다.
    입력은 케이스마다 되풀이되므로 given() 으로 한 번 정해 두면 뒤따르는 check 가 물려받는다.
    """

    def __init__(self, name):
        self.name = name
        self.checks = []
        self._given = ''

    def given(self, text):
        """뒤따르는 check 들이 공유할 입력 조건."""
        self._given = str(text)
        print('    [입력] %s' % text)

    def check(self, cond, label, detail=''):
        self.checks.append({'ok': bool(cond), 'given': self._given,
                            'expected': label, 'actual': str(detail)})
        print('    %s %s%s' % ('ok  ' if cond else 'FAIL',
                               label, ('  — ' + str(detail)) if detail else ''))
        return bool(cond)

    @property
    def passed(self):
        return sum(1 for c in self.checks if c['ok'])

    @property
    def failed(self):
        return sum(1 for c in self.checks if not c['ok'])
