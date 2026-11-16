"""S18 — 일괄 등록의 Register 커밋 (설명서 IV장).

S08 은 검증 표시까지만 본다. 여기서는 Register 를 실제로 눌러
  엑셀 → 화면 → /api/device/batchInsert → DB → 포트 개방 → 시뮬레이션 동작
까지가 한 줄로 이어지는지 확인하고, 끝나면 만든 장비를 지운다.

Register 는 confirm() 으로 한 번 되묻고 성공하면 목록 화면으로 넘어간다.
헤드리스에서는 대화상자를 누를 수 없으니 confirm 을 미리 참으로 바꿔 둔다.
"""
import os
import socket
import struct
import time
import cts

NAME = '일괄 등록 Register 커밋'

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TPL = os.path.join(HERE, 'templates')

#: 템플릿이 담고 있는 내용. 기대값을 여기 한 곳에 적어 둔다.
WANT_DEVICES = ['ZZTMP_s18a', 'ZZTMP_s18b']
PORTS = {'ZZTMP_s18a': 15971, 'ZZTMP_s18b': 15972}
WANT_SIMS = {'ZZTMP_s18a': 2, 'ZZTMP_s18b': 1}
STATIC_TARGET, STATIC_VALUE = '3_100', 4321


def _upload(c, path):
    doc = c.call('DOM.getDocument')['root']['nodeId']
    node = c.call('DOM.querySelector', nodeId=doc, selector='#excelFile')['nodeId']
    c.call('DOM.setFileInputFiles', files=[path], nodeId=node)
    c.js("$('#excelFile').trigger('change');", wait=3.0)


def _devices_named(names):
    found = {}
    for d in (cts.ok('/device/devices') or []):
        if isinstance(d, dict) and d.get('name') in names:
            found[d['name']] = d
    return found


def fc3(port, addr, count=1, unit=1, host='127.0.0.1'):
    """등록된 장비가 실제로 포트를 열고 응답하는지 본다."""
    s = socket.create_connection((host, port), 5)
    try:
        s.sendall(struct.pack('>HHHB', 1, 0, 6, unit)
                  + struct.pack('>BHH', 3, addr, count))
        s.settimeout(5)
        resp = s.recv(256)
    except Exception:
        return None
    finally:
        s.close()
    if not resp or len(resp) < 11:
        return None
    return int.from_bytes(resp[9:11], 'big')


def run(ctx):
    try:
        import cdp
    except ImportError:
        ctx.check(False, 'cdp 모듈을 찾을 수 없다')
        return

    xlsx = os.path.join(TPL, 'batch_commit.xlsx')
    if not os.path.exists(xlsx):
        ctx.check(False, 'templates/batch_commit.xlsx 가 없다. '
                         'make_templates.py 를 먼저 실행한다')
        return

    # 같은 이름이 남아 있으면 중복 검증에 걸려 Register 가 잠긴다
    for d in _devices_named(WANT_DEVICES).values():
        cts.delete_device_tree(d['id'])

    c = cdp.Chrome(width=1500, height=1000)
    try:
        ctx.given('헤드리스 Chrome 으로 /devices/batch?group=1 을 열고 '
                  'templates/batch_commit.xlsx 를 올린다. 장비 2행(ZZTMP_s18a 15971, '
                  'ZZTMP_s18b 15972)과 시뮬레이션 3행이 들어 있고 오류 행은 없다 '
                  '— 오류가 하나라도 있으면 Register 버튼이 잠겨 커밋 경로를 볼 수 없다')
        c.goto(cts.BASE + '/devices/batch?group=1', wait=5)
        c.js("window.confirm = function(){ return true; };"
             " window.alert = function(m){ window.__alert = String(m); };", wait=0.3)
        _upload(c, xlsx)
        ctx.check(c.js('devices.length') == 2, '장비 2행을 읽어 표에 올린다',
                  c.js('devices.length'))
        ctx.check(c.js('simulations.length') == 3, '시뮬레이션 3행을 읽어 표에 올린다',
                  c.js('simulations.length'))
        ctx.check(c.js('devices.filter(d=>d.error).length'
                       ' + simulations.filter(s=>s.error).length') == 0,
                  '오류로 잡히는 행이 없다',
                  c.js("JSON.stringify(devices.filter(d=>d.error).map(d=>d.error)"
                       ".concat(simulations.filter(s=>s.error).map(s=>s.error)))"))
        ctx.check(c.js("document.getElementById('registerBtn').disabled") is False,
                  '오류가 없으므로 Register 버튼이 활성화된다 (disabled=false)',
                  c.js("document.getElementById('registerBtn').disabled"))

        ctx.given('Register 버튼을 실제로 누른다. 화면이 confirm 으로 되묻고 성공하면 '
                  '목록으로 넘어가므로, 헤드리스에서는 confirm 을 참으로 바꿔 두고 '
                  '화면 이동이 아니라 서버 상태로 성공을 판단한다 (최대 20초 폴링)')
        c.js("document.getElementById('registerBtn').click();", wait=1.0)
        # 커밋이 끝나면 /devices 로 넘어간다. 넘어갔는지로 성공을 판단하지 않고
        # 서버에 실제로 들어왔는지로 판단한다.
        found = {}
        for _ in range(20):
            found = _devices_named(WANT_DEVICES)
            if len(found) == 2:
                break
            time.sleep(1)
        ctx.check(len(found) == 2,
                  '장비 2대가 실제로 DB 에 들어간다 (POST /api/device/batchInsert)',
                  sorted(found.keys()))
        ctx.check(c.js("window.__alert || ''") in ('', None),
                  '실패 경고 없이 끝난다', c.js("window.__alert || '(없음)'"))
        if len(found) != 2:
            return

        ctx.check(all(found[n]['port'] == PORTS[n] for n in found),
                  '포트가 엑셀에 적은 값 그대로 들어간다',
                  {n: found[n]['port'] for n in sorted(found)})
        ctx.check(all(found[n].get('group', {}).get('id') == 1 for n in found),
                  'URL 의 group=1 (ROOT) 에 들어간다. 엑셀에는 그룹 열이 없고 화면이 정한다',
                  {n: found[n].get('group', {}).get('id') for n in sorted(found)})

        got_sims = {n: cts.ok('/simulation/simulations/%d' % found[n]['id'])
                    for n in found}
        ctx.check(all(len(got_sims[n]) == WANT_SIMS[n] for n in found),
                  '시뮬레이션 3행이 deviceName 열대로 두 장비에 2건/1건으로 나뉘어 붙는다',
                  {n: len(got_sims[n]) for n in sorted(found)})

        breaker = [s for s in got_sims['ZZTMP_s18b'] + got_sims['ZZTMP_s18a']
                   if s['target'] == '1_10']
        ctx.check(bool(breaker) and breaker[0].get('stopOnControl') == 12,
                  'stopOnControl 12 가 보존된다. 이 값은 Simulation 의 최상위 필드라 '
                  'policies 안에 넣으면 서버가 읽지 않아 그대로 유실된다',
                  breaker[0].get('stopOnControl') if breaker else '(없음)')

        ctx.given('등록으로 끝내지 않고, 새로 생긴 ZZTMP_s18a 의 포트 15971 에 '
                  'Modbus FC3 으로 레지스터 100 을 읽는다. 엑셀 한 장이 실제로 동작하는 '
                  '장비가 되는지까지 본다 (4초 대기)')
        time.sleep(4)
        ctx.check(fc3(PORTS['ZZTMP_s18a'], 100) == STATIC_VALUE,
                  '별도 조작 없이 포트가 열려 있고, 엑셀에 적은 STATIC 4321 을 응답한다',
                  fc3(PORTS['ZZTMP_s18a'], 100))
    finally:
        c.close()
        left = _devices_named(WANT_DEVICES)
        for d in left.values():
            cts.delete_device_tree(d['id'])
        ctx.given('테스트가 등록한 장비 2대를 삭제한다 (운영 DB 에 남기지 않는다)')
        ctx.check(_devices_named(WANT_DEVICES) == {},
                  '2대 모두 지워져 흔적이 남지 않는다',
                  sorted(_devices_named(WANT_DEVICES).keys()) or '(없음)')
