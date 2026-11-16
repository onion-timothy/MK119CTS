"""S13 — 설정/기타 기능과 Simulation 배포.

설명서 X장과 Value Simulation 목록의 'Deploy to...' 가 약속한 것.

  Socket Test    등록된 장비의 소켓이 열려 있는지 알려 준다
  Clients List   접속한 클라이언트 세션을 보여 준다
  Realtime Logs  최근 로그를 레벨로 걸러 보여 준다
  Deploy to...   한 장비의 Simulation 을 다른 장비들에 복사한다
"""
import socket
import time
import cts

NAME = '설정/기타 기능과 배포'


def run(ctx):
    try:
        import cdp
    except ImportError:
        cdp = None

    port = cts.PORT_BASE + 45
    dev = cts.add_device(cts.PREFIX + 's13a', port,
                         settings=cts.modbus_settings(unit_id=1))
    did = dev['id']
    dev2 = cts.add_device(cts.PREFIX + 's13b', port + 1)
    did2 = dev2['id']
    conn = None
    c = None
    try:
        time.sleep(4)

        # ── Socket Test ────────────────────────────────────────────────
        # 장비 한 대씩 묻는다. OS 의 소켓 상태를 보는 것이라 CTS 점유 여부와는 무관하다.
        ctx.given('MODBUS 장비 두 대를 포트 15945, 15946 에 등록하고 4초 기다린다. '
                  'Settings 화면의 Socket Test 기능으로 열린 포트 15945 를 조회한다 '
                  '(GET /api/socketTest?host=0.0.0.0&transport=TCP&port=15945). '
                  'OS 의 소켓 상태를 보는 것이라 CTS 점유 여부와는 무관하다')
        opened = cts.ok('/socketTest?host=0.0.0.0&transport=TCP&port=%d' % port)
        ctx.check(opened is True, '열린 포트에 true 를 돌려준다',
                  '포트 %d -> %s' % (port, opened))

        ctx.given('아무도 쓰지 않는 포트 15999 로 같은 조회를 한다')
        closed = cts.ok('/socketTest?host=0.0.0.0&transport=TCP&port=%d'
                        % (cts.PORT_BASE + 99))
        ctx.check(closed is False, '닫힌 포트에 false 를 돌려준다. 참/거짓이 갈린다',
                  '포트 %d -> %s' % (cts.PORT_BASE + 99, closed))

        # ── Clients List ───────────────────────────────────────────────
        ctx.given('첫 장비의 세션 목록을 먼저 읽고(GET /api/sessions/{id}), '
                  '포트 15945 에 TCP 로 접속한 뒤 3초 기다려 다시 읽는다')
        before = cts.ok('/sessions/%d' % did)
        conn = socket.create_connection(('127.0.0.1', port), 5)
        time.sleep(3)
        after = cts.ok('/sessions/%d' % did)
        ctx.check(len(after) > len(before),
                  '세션 수가 늘어난다. Clients List 가 접속을 실시간으로 잡아낸다',
                  '%d건 -> %d건' % (len(before), len(after)))
        if after:
            ctx.check('remoteHost' in after[0] or 'remotePort' in after[0],
                      '세션 행에 원격 주소(remoteHost/remotePort)가 담겨 누가 붙었는지 알 수 있다',
                      sorted(after[0].keys())[:6])

        # ── Realtime Logs ──────────────────────────────────────────────
        # 과거의 /api/logs/{level} 풀링은 걷어내고 logback 어펜더(WebRealtimeAppender)가
        # STOMP /topic/logs 로 실시간으로 밀어주는 구조로 바뀌었다. 서버가 들고 있는
        # ring 이 없어서 REST 로 과거 로그를 뽑는 길이 없다. settings 화면이 WebSocket
        # 으로 붙어 쌓는 로그 라인을 눈으로 확인한다.
        if cdp is None:
            ctx.check(False, 'cdp 모듈을 찾을 수 없어 Realtime Logs 확인을 건너뛴다')
        else:
            ctx.given('/settings 화면을 헤드리스 Chrome 으로 연다. 화면이 뜨면 '
                      'SockJS + STOMP 로 /topic/logs 에 구독하고, 서버가 밀어주는 '
                      '로그 라인을 콘솔 뷰 (#logsView) 에 쌓는다')
            c = cdp.Chrome(width=1200, height=800)
            c.goto(cts.BASE + '/settings', wait=4)
            # 서버가 활발히 로그를 찍고 있으니 몇 초면 충분히 쌓인다
            lines = 0
            for _ in range(10):
                lines = c.js("document.querySelectorAll('#logsView .log-line').length") or 0
                if lines > 0:
                    break
                time.sleep(1)
            ctx.check(lines > 0,
                      'WebSocket 구독으로 로그 라인이 수신되어 콘솔 뷰에 쌓인다. '
                      '과거 /api/logs/{level} REST 풀링을 대체한 경로가 살아 있다',
                      '%d줄' % lines)
            # ALL / INFO 필터 버튼이 존재하는지 (뷰어 교체가 레벨 필터 UI 를 지웠는지 확인)
            all_btn = c.js("!!document.querySelector('.logs-toolbar .lvl-btn[data-level=\"ALL\"]')")
            info_btn = c.js("!!document.querySelector('.logs-toolbar .lvl-btn[data-level=\"INFO\"]')")
            ctx.check(all_btn and info_btn,
                      'ALL / INFO 레벨 필터 버튼이 뷰어 툴바에 그대로 있다',
                      'ALL=%s, INFO=%s' % (all_btn, info_btn))

        # ── Deploy to... ───────────────────────────────────────────────
        cts.add_simulation(did, 'dep1', '3_90',
                           [cts.policy(kind='STATIC', value=11)], interval=10)
        cts.add_simulation(did, 'dep2', '3_91',
                           [cts.policy(kind='STATIC', value=22)], interval=10)
        time.sleep(2)
        src = cts.ok('/simulation/simulations/%d' % did)
        st, _ = cts.api('/simulation/copySimulation/%d' % did, 'PUT', [did2])
        ctx.given('첫 장비에 STATIC 2건(3_90=11, 3_91=22, 주기 10초)을 등록한 뒤, '
                  'Deploy to... 로 두 번째 장비에 복사한다 '
                  '(PUT /api/simulation/copySimulation/{srcId} 본문 [dstId])')
        ctx.check(200 <= st < 300, 'HTTP 200 으로 수용된다', st)
        time.sleep(3)
        dst = cts.ok('/simulation/simulations/%d' % did2)
        ctx.check(len(dst) == len(src),
                  '대상 장비에 원본과 같은 수의 Simulation 이 생긴다. '
                  '장비를 여러 대 세울 때 설정을 손으로 되풀이하지 않아도 된다',
                  '원본 %d건 -> 대상 %d건' % (len(src), len(dst)))
        ctx.check(sorted(s['target'] for s in dst) == sorted(s['target'] for s in src),
                  'target 이 하나도 빠지지 않고 그대로 복사된다',
                  '%s (원본 %s)' % (sorted(s['target'] for s in dst),
                                   sorted(s['target'] for s in src)))
    finally:
        if c:
            c.close()
        if conn:
            conn.close()
        cts.delete_device_tree(did2)
        cts.delete_device_tree(did)
