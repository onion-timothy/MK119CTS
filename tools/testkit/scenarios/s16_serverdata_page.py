"""S16 — Server Data 화면 (설명서 VIII장).

화면 상태라 REST 로는 닿지 않는다. 헤드리스 Chrome 을 CDP 로 몰아서 본다.

  편집 세션   한 장비에 한 창만. 두 번째 창은 편집 권한을 받지 못한다
  뷰          MODBUS 는 Focus 로 열리고, 그 밖의 프로토콜은 Raw 고정 + Focus 비활성
  값의 출처   값을 바꾼 주체가 기록된다
  Watch       특정 Target 을 상단에 고정한다

상태 줄은 금방 다음 상태로 덮인다. 마지막 글자만 보면 놓치므로 변화를 모아서 본다.
"""
import socket
import struct
import time
import cts

NAME = 'Server Data 화면'

#: 상태 줄이 바뀔 때마다 모아 두는 훅
WATCH_STATUS = (
    "window.__hist=[];"
    "(function(){var el=document.getElementById('sdStatusText');"
    " if(!el) return;"
    " new MutationObserver(function(){window.__hist.push(el.innerText.trim());})"
    "  .observe(el,{childList:true,subtree:true,characterData:true});"
    " window.__hist.push(el.innerText.trim());})();"
)


def _probe(c, expr, default=None):
    """페이지 요소가 아직 없을 수 있다. 던지지 말고 기본값을 돌려준다."""
    try:
        return c.js('(function(){try{return (%s);}catch(e){return null;}})()' % expr)
    except Exception:
        return default


def _await_status(c, needle, seconds=14):
    """상태 변화 기록에 needle 이 나타날 때까지 기다린다."""
    for _ in range(seconds):
        hist = _probe(c, "JSON.stringify(window.__hist || [])") or '[]'
        if needle in hist:
            return hist
        time.sleep(1)
    return _probe(c, "JSON.stringify(window.__hist || [])") or '[]'


def fc6(port, addr, value, host='127.0.0.1'):
    s = socket.create_connection((host, port), 5)
    try:
        s.sendall(struct.pack('>HHHB', 1, 0, 6, 1) + struct.pack('>BHH', 6, addr, value))
        s.settimeout(5)
        try:
            return s.recv(256)
        except Exception:
            return None
    finally:
        s.close()


def run(ctx):
    try:
        import cdp
    except ImportError:
        ctx.check(False, 'cdp 모듈을 찾을 수 없다')
        return

    mb_port = cts.PORT_BASE + 85
    mb = cts.add_device(cts.PREFIX + 's16mb', mb_port,
                        settings=cts.modbus_settings(unit_id=1))
    mb_id = mb['id']
    enip = cts.add_device(cts.PREFIX + 's16enip', cts.PORT_BASE + 86,
                          protocol='ETHERNET_IP',
                          settings={'cpuSlot': '0', 'syn_port': '2223',
                                    'registers': '100',
                                    'implicit_t2o_addr': '127.0.0.1'})
    enip_id = enip['id']
    browser = None
    try:
        # stopOnControl 을 둔다. 제어 값을 쓰면 그 Target 이 잠겨 1초 주기 시뮬레이션이
        # 곧바로 덮어쓰지 못하므로, 출처 표시를 확인할 시간이 생긴다.
        cts.add_simulation(mb_id, 'v', '3_10',
                           [cts.policy(kind='ACCUMULATE', rng=[1000, 1])],
                           interval=1, stop_on_control=15)
        time.sleep(4)

        # ── 첫 창 ──────────────────────────────────────────────────────
        ctx.given('MODBUS 장비에 ACCUMULATE range=[1000,1] 주기 1초 → 3_10 을 '
                  'stopOnControl=15 로 등록해 두고, /devices/{id}/serverdata 화면을 '
                  '헤드리스 Chrome(CDP)으로 연다. 화면 상태는 REST 로 닿지 않아 '
                  '브라우저를 직접 몰아야 확인된다')
        browser = cdp.Chrome(width=1400, height=950)
        browser.goto(cts.BASE + '/devices/%d/serverdata' % mb_id, wait=7)
        ctx.check(_probe(browser, "typeof stompClient") == 'object',
                  '페이지가 STOMP 클라이언트를 만들어 WebSocket 으로 붙는다',
                  _probe(browser, "document.getElementById('sdStatusText').innerText.trim()"))
        ctx.check(_probe(browser, "document.getElementById('sdStatusBar').className")
                  .find('status-granted') >= 0,
                  '첫 창이 편집 세션을 가져간다 (상태 바가 granted). '
                  '장비 하나에 편집 창은 하나만 허용된다',
                  _probe(browser, "document.getElementById('sdStatusText').innerText.trim()"))

        ctx.check(_probe(browser, "viewMode") == 'focus',
                  'MODBUS 는 Focus 뷰로 열린다. 레지스터가 3만 칸이라 전체를 그리면 '
                  '쓸모가 없어서, 값이 있는 칸 위주로 보여 주는 뷰가 기본이다',
                  _probe(browser, "viewMode"))
        browser.js("setViewMode('raw');", wait=1.5)
        ctx.given('같은 화면에서 Raw 탭을 누른다 (setViewMode("raw"))')
        ctx.check(_probe(browser, "viewMode") == 'raw',
                  '전체 주소를 쭉 보여 주는 Raw 뷰로 바뀐다',
                  _probe(browser, "viewMode"))
        browser.js("setViewMode('focus');", wait=1.5)

        # ── 두 번째 창 ─────────────────────────────────────────────────
        ctx.given('같은 장비의 Server Data 화면을 두 번째 탭에서 연다. '
                  '상태 줄은 금방 다음 상태로 덮이므로 MutationObserver 로 변화를 모두 모아 둔다')
        second = browser.open_tab(cts.BASE + '/devices/%d/serverdata' % mb_id)
        second.js(WATCH_STATUS, wait=0.3)
        hist = _await_status(second, 'Another editor')
        ctx.check('Another editor' in hist,
                  '두 번째 창은 "Another editor is active" 를 받고 편집 권한을 얻지 못한다. '
                  '두 사람이 같은 장비 값을 동시에 덮어쓰는 일을 막는다',
                  hist)

        # ── 값의 출처 ──────────────────────────────────────────────────
        ctx.given('화면이 열린 채로, 먼저 주기 1초 시뮬레이션이 값을 쓰게 두고 '
                  '그 다음 Modbus FC6 으로 레지스터 10 에 7777 을 쓴다. '
                  '화면이 "이 값을 누가 썼는지" 를 구분해 보여 줘야 한다')
        ctx.check('VALUE_SIMULATION' in (_probe(browser, "JSON.stringify(lastChangeMeta)") or ''),
                  '시뮬레이션이 쓴 값에 출처 VALUE_SIMULATION 이 붙는다',
                  _probe(browser, "JSON.stringify(lastChangeMeta)"))
        fc6(mb_port, 10, 7777)
        time.sleep(3)
        meta = _probe(browser, "JSON.stringify(lastChangeMeta['3_10'] || null)") or ''
        ctx.check('PROTOCOL_WRITE' in meta,
                  '같은 target 이 제어를 받으면 출처가 PROTOCOL_WRITE 로 갈린다. '
                  '값만 보면 구분되지 않는 것을 화면이 구분해 준다',
                  meta)

        # ── Watch ──────────────────────────────────────────────────────
        ctx.given('관심 있는 주소만 상단에 고정하는 Watch 에 3_10 을 추가한다')
        browser.js("watchItems.push({target:'3_10', label:'t', format:'dec'});"
                   " if (typeof renderWatch === 'function') renderWatch();", wait=1.0)
        ctx.check(_probe(browser, "watchItems.length") == 1,
                  'Watch 목록에 항목이 담긴다. 3만 칸에서 특정 주소를 계속 보려면 필요하다',
                  '%s건' % _probe(browser, "watchItems.length"))

        # ── MODBUS 가 아닌 프로토콜 ────────────────────────────────────
        ctx.given('MODBUS 가 아닌 ETHERNET_IP 장비의 Server Data 화면을 연다. '
                  'Focus 뷰는 Modbus 레지스터 전용이라 다른 프로토콜에는 맞지 않는다')
        other = browser.open_tab(cts.BASE + '/devices/%d/serverdata' % enip_id)
        time.sleep(6)
        ctx.check(_probe(other, "viewMode") == 'raw',
                  'Raw 뷰로 열린다 (Focus 가 기본이 아니다)', _probe(other, "viewMode"))
        ctx.check(_probe(other, "document.getElementById('sdViewFocus').disabled") is True,
                  'Focus 탭 자체가 비활성이라 누를 수 없다. 맞지 않는 뷰로 들어갈 길을 막는다',
                  _probe(other, "document.getElementById('sdViewFocus').disabled"))
    finally:
        if browser:
            browser.close()
        cts.delete_device_tree(enip_id)
        cts.delete_device_tree(mb_id)
