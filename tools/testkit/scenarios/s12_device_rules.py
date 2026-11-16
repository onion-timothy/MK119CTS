"""S12 — Device 등록 규칙과 사용 여부.

설명서가 약속한 것:
  이름은 시스템 전체에서 고유하다 (다른 그룹에 같은 이름이 있어도 안 된다)
  같은 host/transport/port 조합은 겹칠 수 없다
  사용 여부를 끄면 소켓이 닫히고, 켜면 다시 열린다
"""
import socket
import time
import cts

NAME = 'Device 등록 규칙과 사용 여부'


def port_open(port, host='127.0.0.1'):
    try:
        socket.create_connection((host, port), 2).close()
        return True
    except OSError:
        return False


def until(fn, seconds, period=0.6):
    end = time.time() + seconds
    while time.time() < end:
        if fn():
            return True
        time.sleep(period)
    return False


def run(ctx):
    port = cts.PORT_BASE + 40
    name = cts.PREFIX + 's12'
    dev = cts.add_device(name, port)
    did = dev['id']
    second = None
    try:
        time.sleep(3)
        ctx.given('MODBUS 장비 ZZTMP_s12 를 0.0.0.0:15940/TCP 에 등록한다 '
                  '(PUT /api/device/addDevice). 등록만으로 리스닝이 시작되어야 한다')
        ctx.check(until(lambda: port_open(port), 8),
                  '8초 안에 포트 15940 이 열린다. 별도 기동 호출이 필요 없다',
                  '포트 %d 접속 가능' % port)

        # 같은 이름
        ctx.given('같은 이름(ZZTMP_s12)으로 포트만 15941 로 바꿔 다시 등록한다. '
                  '장비 이름은 전역 UNIQUE 라 거절되어야 한다')
        st, body = cts.api('/device/addDevice', 'PUT',
                           cts.device_body(name, port + 1))
        ctx.check(not (200 <= st < 300),
                  'DB 의 UNIQUE 제약에 걸려 거절된다 (2xx 가 아니다)',
                  '%s %s' % (st, body))

        # 포트만 다르면 등록된다
        ctx.given('이름과 포트를 모두 다르게 등록한다 (ZZTMP_s12_b, 포트 15941)')
        st, body = cts.api('/device/addDevice', 'PUT',
                           cts.device_body(name + '_b', port + 1))
        ctx.check(200 <= st < 300,
                  '정상 등록된다. 한 장비에만 묶인 제약이 아님을 확인한다', st)
        if 200 <= st < 300:
            second = body['id']

        # host/transport/port 충돌은 일괄 등록의 검증 항목이다.
        # 단건 등록 API 는 이 검사를 하지 않는다 — 설명서도 일괄 등록 절에만 적고 있다.
        ctx.given('이름은 새것(ZZTMP_s12_c)이지만 host/transport/port 가 이미 쓰이는 '
                  '0.0.0.0:15940/TCP 와 같은 장비를 일괄 등록 검증에 넣는다 '
                  '(POST /api/device/batchValidate). 엔드포인트 충돌 검사는 '
                  '단건 등록 API 에는 없고 일괄 등록에만 있다 — 설명서도 그렇게 적고 있다')
        res = cts.ok('/device/batchValidate', 'POST', {'devices': [
            dict(cts.device_body(name + '_c', port), communicationSettings=
                 cts.modbus_settings())], 'simulations': []})
        rows = res.get('devices') or []
        ctx.check(bool(rows) and rows[0].get('error'),
                  '그 행에 오류가 붙는다. 같은 포트를 두 장비가 쓰면 뒤에 뜬 쪽이 '
                  '조용히 바인딩에 실패하므로 등록 전에 잡아야 한다',
                  rows[0].get('error') if rows else res)

        # 사용 여부
        ctx.given('첫 장비를 끈다 (POST /api/device/setEnabled/{id}/0)')
        cts.ok('/device/setEnabled/%d/0' % did, 'POST')
        ctx.check(until(lambda: not port_open(port), 10),
                  '10초 안에 포트 15940 이 닫혀 접속이 거절된다',
                  '포트 %d 접속 불가' % port)
        ctx.check(cts.ok('/device/getEnabled/%d' % did) is False,
                  'getEnabled 가 false 를 돌려준다 (화면 표시와 일치)', 'enabled=false')

        ctx.given('같은 장비를 다시 켠다 (POST /api/device/setEnabled/{id}/1)')
        cts.ok('/device/setEnabled/%d/1' % did, 'POST')
        ctx.check(until(lambda: port_open(port), 12),
                  '12초 안에 포트가 다시 열린다. 끄고 켜는 것으로 소켓이 재개된다',
                  '포트 %d 접속 가능' % port)

        # 꺼진 장비는 ServerData 화면/REST 로 들어갈 수 없다
        ctx.given('장비를 다시 끈 뒤 ServerData 스냅샷을 요청한다 '
                  '(GET /api/devices/{id}/serverdata)')
        cts.ok('/device/setEnabled/%d/0' % did, 'POST')
        time.sleep(3)
        st, _ = cts.api('/devices/%d/serverdata' % did)
        ctx.check(st == 404,
                  'HTTP 404 다. 꺼진 장비는 ServerData 가 없으므로 화면도 열리지 않는다',
                  st)
    finally:
        if second:
            cts.delete_device_tree(second)
        cts.delete_device_tree(did)
        time.sleep(2)
        ctx.check(not port_open(port),
                  '장비를 삭제하면 소켓도 함께 닫힌다 (DELETE /api/device/deleteDevice/{id})',
                  '포트 %d 접속 불가' % port)
