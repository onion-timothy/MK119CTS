"""S02 — ServerData REST 쓰기 API.

정상 쓰기, 여러 레지스터를 쓰는 데이터 형식, 잘못된 입력의 응답 코드를 본다.
잘못된 입력이 조용히 무시되지 않고 코드로 구분되어 돌아오는지가 핵심이다.
"""
import time
import cts

NAME = 'ServerData 쓰기 API'


def run(ctx):
    dev = cts.add_device(cts.PREFIX + 's02', cts.PORT_BASE + 2)
    did = dev['id']
    try:
        time.sleep(2)

        ctx.given('MODBUS 장비(holding register 30000칸, Simulation 없음). '
                  'POST /api/devices/{id}/serverdata 에 {target: "3_20", value: 1234, '
                  'dataType: "UI16AB"} 를 보낸다')
        st, body = cts.write(did, '3_20', 1234)
        ctx.check(st == 200 and body.get('ok'),
                  'HTTP 200 과 ok=true 로 수용된다', '%s %s' % (st, body))
        ctx.check(cts.holding(did, 20) == 1234,
                  '스냅샷의 holding register 20 이 보낸 값 1234 가 된다',
                  '%s (기대 1234)' % cts.holding(did, 20))
        ctx.check(body.get('affectedTargets') == ['3_20'],
                  '응답의 affectedTargets 에 실제로 바뀐 칸이 담긴다 (16비트라 한 칸)',
                  body.get('affectedTargets'))

        ctx.given('같은 장비에 32비트 값을 쓴다: {target: "3_30", value: 70000, '
                  'dataType: "UI32AD"}. 70000 은 16비트에 담기지 않아 두 칸을 차지한다')
        st, body = cts.write(did, '3_30', 70000, data_type='UI32AD')
        ctx.check(st == 200, 'HTTP 200 으로 수용된다', '%s %s' % (st, body))
        ctx.check(len(body.get('affectedTargets', [])) == 2,
                  'affectedTargets 가 두 칸(3_30, 3_31)으로 돌아온다. '
                  '잠금도 이 두 칸에 걸려야 한다',
                  body.get('affectedTargets'))

        ctx.given('같은 장비에 장비 용량(30000칸)을 넘는 주소를 쓴다: target "3_999999"')
        st, body = cts.write(did, '3_999999', 1)
        ctx.check(st == 400,
                  'HTTP 400 으로 거절한다. 그냥 쓰면 ArrayIndexOutOfBounds 가 올라가 '
                  '원인이 불분명해진다', '%s %s' % (st, body))

        ctx.given('같은 장비에 없는 dataType 을 쓴다: {target: "3_20", dataType: "NOPE16"}')
        st, body = cts.write(did, '3_20', 1, data_type='NOPE16')
        ctx.check(st == 400, 'HTTP 400 으로 거절한다', '%s %s' % (st, body))

        ctx.given('같은 장비에 Modbus 형식이 아닌 target 을 쓴다: "not-a-target"')
        st, body = cts.write(did, 'not-a-target', 1)
        ctx.check(st in (400, 501),
                  'HTTP 400 또는 501 로 거절한다. 조용히 통과하면 호출자가 성공으로 오해한다',
                  '%s %s' % (st, body))

        ctx.given('없는 장비 id 999999 에 쓰기를 시도한다')
        st, body = cts.api('/devices/999999/serverdata', 'POST',
                           {'target': '3_1', 'value': 1})
        ctx.check(st == 404, 'HTTP 404 로 거절한다 (400 과 구분된다)', '%s %s' % (st, body))

        # ── 타입에 맞지 않는 값은 거절한다 ──────────────────────────────
        ctx.given('레지스터 40 에 {value: 77, dataType: "UI16AB"} 를 써 둔 뒤, 같은 칸에 정수형에 맞지 않는 값을 '
                  '차례로 보낸다: UI16AB 12.7 / SI16AB -12.7 / UI16AB 65536 / dataType 없이 7. '
                  '예전에는 각각 12 로 잘리거나, -12 로 잘리거나, 넘쳐서 0 이 되거나, 아무것도 안 하고 200 이 나갔다')
        cts.write(did, '3_40', 77)
        bad = [(12.7, 'UI16AB'), (-12.7, 'SI16AB'), (65536, 'UI16AB'), (7, None)]
        got = [cts.write(did, '3_40', v, data_type=dt)[0] for v, dt in bad]
        ctx.check(got == [400, 400, 400, 400] and cts.holding(did, 40) == 77,
                  '네 번 모두 HTTP 400 이고 레지스터 40 은 77 그대로다',
                  'HTTP %s, value=%s' % (got, cts.holding(did, 40)))

        ctx.given('코일 1_5 에 value 2 를 보낸다. 코일은 0/1 만 담는다')
        st, body = cts.write(did, '1_5', 2, data_type=None)
        ctx.check(st == 400, 'HTTP 400 으로 거절한다', '%s %s' % (st, body))

        ctx.given('레지스터 50 에 {value: 3000000000, dataType: "UI32AD"} 를 쓴다. '
                  '2147483647 을 넘는 UI32 다. 예전에는 0x7FFFFFFF 로 잘렸다')
        st, body = cts.write(did, '3_50', 3000000000, data_type='UI32AD')
        hi, lo = cts.holding(did, 50) & 0xFFFF, cts.holding(did, 51) & 0xFFFF
        ctx.check(st == 200 and (hi << 16 | lo) == 3000000000,
                  '두 칸이 0xB2D0, 0x5E00 이 되어 3000000000 으로 읽힌다',
                  'HTTP %s, regs=[%d, %d] -> %d' % (st, hi, lo, hi << 16 | lo))
    finally:
        cts.delete_device_tree(did)
