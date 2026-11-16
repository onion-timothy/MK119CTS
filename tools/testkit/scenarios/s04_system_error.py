"""S04 — System Error 의 활성 상태.

ErrorJob 은 Interval 마다 확률을 굴리고, 적중하면 장비에 활성 상태를 하나 세운다.
Duration 이 지나면 스스로 풀린다. 끄면 그 자리에서 풀린다.

활성 상태는 ServerData 값이 아니라 IOServer 의 상태라서 스냅샷으로는 보이지 않는다.
그래서 여기서는 '스케줄이 돌고 상태가 생겼다 사라지는' 흐름을 로그 대신
동작으로 확인할 수 있는 범위(등록/해제/중복 방지)만 본다.
"""
import time
import cts

NAME = 'System Error 등록과 해제'


def run(ctx):
    dev = cts.add_device(cts.PREFIX + 's04', cts.PORT_BASE + 4)
    did = dev['id']
    try:
        time.sleep(2)

        ctx.given('MODBUS 장비에 System Error 를 등록한다: mode=DISCARD, frequency=100%, '
                  'interval=2초(확률 판정 주기), duration=3초(걸렸을 때 유지 시간)')
        eid = cts.add_error(did, 'DISCARD', frequency=100, interval=2, duration=3)
        ctx.check(eid is not None, '등록되고 error id 가 돌아온다', eid)

        errs = cts.ok('/error/errors/%d' % did)
        e = errs[0]
        ctx.check(e['mode'] == 'DISCARD', '조회하면 mode 가 DISCARD 로 저장돼 있다', e['mode'])
        ctx.check(e['interval'] == 2,
                  'Interval 2 가 그대로 저장된다 (확률을 2초마다 한 번 뽑는다)',
                  '%s (기대 2)' % e['interval'])
        ctx.check(e['errorDuration'] == 3,
                  'Duration 3 이 그대로 저장된다 (걸리면 3초 동안 활성)',
                  '%s (기대 3)' % e['errorDuration'])

        ctx.given('그 에러를 끈다: POST /api/error/setEnabled/{id}/0')
        cts.ok('/error/setEnabled/%d/0' % eid)
        time.sleep(1)
        e = cts.ok('/error/errors/%d' % did)[0]
        ctx.check(e['enabled'] == 0,
                  'enabled 가 0 이 된다. 스케줄도 함께 내려간다', e['enabled'])

        ctx.given('다시 켠다: POST /api/error/setEnabled/{id}/1')
        cts.ok('/error/setEnabled/%d/1' % eid)
        time.sleep(1)
        e = cts.ok('/error/errors/%d' % did)[0]
        ctx.check(e['enabled'] == 1, 'enabled 가 1 로 돌아온다', e['enabled'])

        ctx.given('같은 장비에 두 번째 에러를 등록한다: mode=TIMEOUT, interval=0, timeout=1. '
                  'Interval 0 은 판정 주기가 없다는 뜻이라 스케줄에 올라가지 않는다')
        eid2 = cts.add_error(did, 'TIMEOUT', frequency=100, interval=0, timeout=1)
        ctx.check(eid2 is not None,
                  '등록 자체는 받아들인다 (값 검증은 화면이 맡는다)', eid2)
        ctx.check(len(cts.ok('/error/errors/%d' % did)) == 2,
                  '장비 조회에 에러 2건이 모두 보인다 (한 장비에 여러 에러를 둘 수 있다)',
                  '%d건' % len(cts.ok('/error/errors/%d' % did)))
    finally:
        cts.delete_device_tree(did)
