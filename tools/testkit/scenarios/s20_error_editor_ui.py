"""S20 — Error 편집 화면 (설명서 VI장).

화면에서만 걸리는 규칙들이다. 헤드리스 Chrome 으로 편집 화면을 열어 확인한다.

  Frequency 캡    과거엔 한 장비의 Frequency 합이 100% 를 넘지 못했지만, 지금은 각
                  에러가 자기 interval 로 독립 스케줄되므로 합계 캡이 걷어내졌다.
                  슬라이더에 100 을 넣어도 깎이지 않고 그대로 보존된다
  Interval        확률 판정 주기. 슬라이더가 아니라 숫자 입력칸이다
  Simulation stop ServerData 를 쓰기 보호해서 멈추는 값이므로 DB 장비에는 보이지 않는다

슬라이더 호출은 jQuery 객체를 돌려주는데 CDP 가 그걸 직렬화하지 못한다. 값을 쓰는
호출은 _void 로 감싸 숫자만 돌려받는다.
"""
import time
import cts

NAME = 'Error 편집 화면'


def _probe(c, expr, default=None):
    try:
        return c.js('(function(){try{return (%s);}catch(e){return null;}})()' % expr)
    except Exception:
        return default


def _void(c, stmt, wait=1.0):
    """반환값을 버리고 실행만 한다."""
    return c.js('(function(){%s return 1;})()' % stmt, wait=wait)


def _freq(c):
    return _probe(c, "frequency.bootstrapSlider('getValue')")


def _wait_loaded(c, seconds=12):
    """onload 가 끝나 슬라이더와 입력 DOM 이 준비될 때까지 기다린다."""
    for _ in range(seconds):
        if _probe(c, "typeof frequency") == 'object' \
                and _probe(c, "document.getElementById('error-interval') ? 1 : 0") == 1:
            return True
        time.sleep(1)
    return False


def run(ctx):
    try:
        import cdp
    except ImportError:
        ctx.check(False, 'cdp 모듈을 찾을 수 없다')
        return

    dev = cts.add_device(cts.PREFIX + 's20', cts.PORT_BASE + 140,
                         settings=cts.modbus_settings(unit_id=1))
    did = dev['id']
    db_dev = cts.add_device(
        cts.PREFIX + 's20db', cts.PORT_BASE + 141, protocol='DB',
        settings={'dbdriver': 'org.mariadb.jdbc.Driver',
                  'dbUrl': 'jdbc:mariadb://127.0.0.1:3306/x',
                  'dbUsername': 'u', 'dbPassword': 'p', 'dbDatabase': 'x'},
        enabled=0)
    db_id = db_dev['id']
    c = None
    try:
        sim = cts.add_simulation(did, 'v', '3_10',
                                 [cts.policy(kind='STATIC', value=1)], interval=60)
        # System 몫 70, SIMULATION 몫 40 을 미리 채워 둔다
        cts.add_error(did, 0, frequency=70, interval=10, duration=5)
        cts.add_error(did, 4, frequency=40, interval=10,
                      simulation_id=sim['id'], error_value=1)
        time.sleep(1)

        c = cdp.Chrome(width=1300, height=1000)
        ctx.given('MODBUS 장비에 System(DISCARD) 에러 Frequency 70%, '
                  'SIMULATION 에러 40% 를 미리 등록해 두고 /addError/{id} 화면을 연다. '
                  '과거엔 두 몫의 합을 100% 로 캡 했지만, 지금은 각 에러가 자기 interval 로 '
                  '독립 스케줄되므로 그 캡이 걷어내졌다. 캡이 없는 것을 아래서 확인한다')
        c.goto(cts.BASE + '/addError/%d' % did, wait=5)
        ok_load = _wait_loaded(c)
        ctx.check(ok_load,
                  '화면이 뜨고 Frequency 슬라이더와 Interval 입력 DOM 이 준비된다',
                  'frequency=%s, interval=%s'
                  % (_probe(c, "typeof frequency"),
                     _probe(c, "document.getElementById('error-interval') ? 'yes' : 'no'")))
        if not ok_load:
            return

        ctx.check(_probe(c, "typeof system_frequency_occupied") == 'undefined'
                  and _probe(c, "typeof simulation_frequency_occupied") == 'undefined',
                  '합계 캡 로직의 전역 변수가 더 이상 선언되지 않는다 (캡 자체가 걷어내짐)',
                  'system_frequency_occupied=%s, simulation_frequency_occupied=%s'
                  % (_probe(c, "typeof system_frequency_occupied"),
                     _probe(c, "typeof simulation_frequency_occupied")))
        ctx.check(_freq(c) == 10,
                  '새 에러 화면의 Frequency 는 기본 10% 로 열린다', '%s%%' % _freq(c))

        ctx.given('모드를 DISCARD(System 계열)로 둔 채 Frequency 슬라이더에 100 을 넣는다. '
                  '과거엔 이미 쓰인 70% 때문에 30 으로 깎였지만, 지금은 그대로 100 이 들어간다')
        _void(c, "frequency.bootstrapSlider('setValue', 100, false, true);")
        ctx.check(_freq(c) == 100,
                  '100 이 그대로 보존된다. System 몫 70 과 합쳐 170 이 돼도 캡이 걸리지 않는다',
                  '%s%% (기대 100%%)' % _freq(c))

        ctx.given('모드를 SIMULATION 으로 바꾼다. 과거엔 계열별 몫 (SIMULATION 40%) 에 따라 '
                  '한도가 60 으로 줄었지만, 지금은 어느 모드든 캡이 없다')
        _void(c, "$('#errorMode').val('4'); displayForms('4');", wait=1.5)
        ctx.check(_freq(c) == 100,
                  '모드를 바꿔도 Frequency 가 깎이지 않고 100 그대로 유지된다',
                  '%s%% (기대 100%%)' % _freq(c))
        _void(c, "frequency.bootstrapSlider('setValue', 100, false, true);")
        ctx.check(_freq(c) == 100,
                  '다시 100 을 넣어도 100 이 들어간다. 계열별 캡도 모두 걷어내졌다',
                  '%s%% (기대 100%%)' % _freq(c))

        ctx.given('모드가 SIMULATION 인 상태에서 입력칸 구성을 본다. 모드마다 필요한 항목이 '
                  '달라서 화면이 보여 주고 숨기는 것이 갈린다')
        ctx.check(_probe(c, "document.getElementById('error-interval').type") == 'number',
                  'Interval 은 슬라이더가 아니라 숫자 입력칸이다. 슬라이더로는 정확한 주기를 '
                  '넣기 어렵다',
                  _probe(c, "document.getElementById('error-interval').type"))
        ctx.check(_probe(c, "document.getElementById('error-interval').min") == '1',
                  'min=1 이라 0 이하를 넣을 수 없다. 0 은 스케줄에 올라가지 않아 아무 일도 안 한다',
                  _probe(c, "document.getElementById('error-interval').min"))
        ctx.check(_probe(c, "$('#interval-form-group').is(':visible')") is True,
                  'SIMULATION 모드에도 Interval 칸이 보인다. 이 모드도 자기 주기로 확률을 판정한다',
                  _probe(c, "$('#interval-form-group').is(':visible')"))
        ctx.check(_probe(c, "$('#simulation-stop-group').is(':visible')") is True,
                  'MODBUS 장비에는 Simulation stop 칸이 보인다 (멈출 ServerData 가 있다)',
                  _probe(c, "$('#simulation-stop-group').is(':visible')"))
        ctx.check(_probe(c, "$('#duration-from-group').is(':visible')") is False,
                  'SIMULATION 모드에는 Duration 칸이 숨는다. 이 모드는 값을 한 번 덮는 것이라 '
                  '유지 시간 개념이 없다',
                  _probe(c, "$('#duration-from-group').is(':visible')"))

        ctx.given('이번에는 DB 프로토콜 장비의 /addError/{id} 화면을 열고 모드를 '
                  'SIMULATION 으로 바꾼다. DB 장비는 ServerData 없이 SQL 로 바로 쓰므로 '
                  'Simulation stop 을 걸 대상이 없다')
        other = c.open_tab(cts.BASE + '/addError/%d' % db_id)
        time.sleep(5)
        _void(other, "$('#errorMode').val('4'); displayForms('4');", wait=1.5)
        ctx.check(_probe(other, "$('#simulation-stop-group').is(':visible')") is False,
                  'Simulation stop 칸이 숨는다. 걸 수 없는 값을 입력받지 않는다',
                  _probe(other, "$('#simulation-stop-group').is(':visible')"))
    finally:
        if c:
            c.close()
        cts.delete_device_tree(db_id)
        cts.delete_device_tree(did)
