"""S08 — 일괄 등록 화면의 검증.

templates/ 의 엑셀을 실제로 올려 화면이 몇 건을 통과시키는지 본다.
Register 는 누르지 않는다. 검증까지만 보는 것이 목적이고, 누르면 운영 DB 에
장비가 들어간다.

헤드리스라 파일 선택 창을 띄울 수 없지만, CDP 의 DOM.setFileInputFiles 로
input[type=file] 에 파일을 직접 꽂을 수 있다.
"""
import os
import cts

NAME = '일괄 등록 엑셀 검증'

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TPL = os.path.join(HERE, 'templates')


def _upload(c, path):
    doc = c.call('DOM.getDocument')['root']['nodeId']
    node = c.call('DOM.querySelector', nodeId=doc, selector='#excelFile')['nodeId']
    c.call('DOM.setFileInputFiles', files=[path], nodeId=node)
    c.js("$('#excelFile').trigger('change');", wait=3.0)


def run(ctx):
    try:
        import cdp
    except ImportError:
        ctx.check(False, 'cdp 모듈을 찾을 수 없다')
        return

    dev_xlsx = os.path.join(TPL, 'batch_devices.xlsx')
    sim_xlsx = os.path.join(TPL, 'batch_simulations.xlsx')
    if not (os.path.exists(dev_xlsx) and os.path.exists(sim_xlsx)):
        ctx.check(False, '템플릿이 없다. templates/make_templates.py 를 먼저 실행한다')
        return

    # 시뮬레이션 일괄 추가는 대상 장비가 있어야 한다
    dev = cts.add_device(cts.PREFIX + 's08', cts.PORT_BASE + 8)
    did = dev['id']
    c = cdp.Chrome(width=1500, height=1000)
    try:
        c.goto(cts.BASE + '/devices/batch?group=1', wait=4)
        _upload(c, dev_xlsx)
        ctx.given('헤드리스 Chrome 으로 /devices/batch 를 열고, templates/batch_devices.xlsx '
                  '(1번 시트 장비 5행, 2번 시트 시뮬레이션 10행) 를 CDP 의 DOM.setFileInputFiles 로 '
                  '파일 입력에 꽂아 change 를 발생시킨다. 마지막 장비 ups_snmp_01 은 '
                  'snmpVersion=3 으로 일부러 틀려 두었다 (지원 범위는 0~1)')
        n_dev = c.js('devices.length')
        n_bad = c.js('devices.filter(d => d.error).length')
        n_sim = c.js('simulations.length')
        n_sim_bad = c.js('simulations.filter(s => s.error).length')
        ctx.check(n_dev == 5, '장비 시트의 5행을 모두 읽어 표에 올린다', '%s행 (기대 5)' % n_dev)
        ctx.check(n_sim == 10, '시뮬레이션 시트의 10행을 모두 읽어 표에 올린다',
                  '%s행 (기대 10)' % n_sim)
        ctx.check(n_bad == 1,
                  '일부러 틀려 둔 1행만 오류로 잡고 나머지 4행은 통과시킨다. '
                  '오류 메시지가 어느 항목이 왜 틀렸는지 알려 준다',
                  c.js("JSON.stringify(devices.filter(d=>d.error).map(d=>[d.name,d.error]))"))
        ctx.check(n_sim_bad == 0,
                  '시뮬레이션 10행에는 오류가 없다 (deviceName 이 같은 파일의 장비를 가리킨다)',
                  '오류 %s행' % n_sim_bad)

        c.goto(cts.BASE + '/simulations/batch?device=%d' % did, wait=4)
        _upload(c, sim_xlsx)
        ctx.given('이미 등록된 장비에 시뮬레이션만 추가하는 화면(/simulations/batch?device=...)을 열고 '
                  'templates/batch_simulations.xlsx (8행, deviceName 열 없음) 를 꽂는다')
        n = c.js('simulations.length')
        bad = c.js('simulations.filter(s => s.error).length')
        ctx.check(n == 8, '8행을 모두 읽어 표에 올린다', '%s행 (기대 8)' % n)
        ctx.check(bad == 0,
                  '8행 모두 통과한다. 정책별 필수값(STATIC 의 value, RANDOM 의 range 등)이 '
                  '모두 채워져 있다',
                  c.js("JSON.stringify(simulations.filter(s=>s.error).map(s=>[s.name,s.error]))"))
    finally:
        c.close()
        cts.delete_device_tree(did)
