"""S06 — 잠금의 우선순위.

사람이 건 잠금이 가장 세다. 잠긴 Target 에는 Error Simulation 도 값을 쓰지 못한다.
쓰지 못했으면 그 에러는 발생하지 않은 것이므로 Simulation stop 도 걸지 않는다.

여기가 과거에 틀렸던 자리다. 막힌 쓰기를 성공으로 보고 정지를 걸어 두면,
사람이 잠금을 풀어도 시뮬레이션이 계속 죽어 있었다.
"""
import time
import cts

NAME = '사람의 잠금이 최우선'


def run(ctx):
    dev = cts.add_device(cts.PREFIX + 's06', cts.PORT_BASE + 6)
    did = dev['id']
    try:
        sim = cts.add_simulation(did, 'accum', '3_60',
                                 [cts.policy(kind='ACCUMULATE', rng=[1000, 1])],
                                 interval=1)
        sid = sim['id']
        time.sleep(4)

        # 사람이 20초 잠근다
        ctx.given('MODBUS 장비에 ACCUMULATE range=[1000,1] 주기 1초 → 3_60 을 등록한 뒤, '
                  '사람이 REST 로 {value: 55555, lockSeconds: 20} 을 써서 그 target 을 20초 잠근다')
        cts.write(did, '3_60', 55555, lock_seconds=20)
        ctx.check('3_60' in cts.locks(did),
                  '사람의 잠금이 locks 에 올라온다', cts.locks(did))

        # 잠긴 동안 적중하도록 짧은 주기의 에러를 붙인다.
        # simulation_stop 을 길게 줘서, 잘못 걸리면 해제 후에도 멈춰 있게 만든다.
        eid = cts.add_error(did, 'SIMULATION', frequency=100, interval=4,
                            simulation_id=sid, error_value=7777.0,
                            simulation_stop=300)
        ctx.given('잠긴 동안 적중하도록 SIMULATION 에러를 붙인다: frequency=100%, interval=4초, '
                  'errorValue=7777, simulation_stop=300초. 7초 기다려 에러가 한 번 이상 판정되게 한다. '
                  '사람의 잠금과 에러가 같은 target 을 두고 부딪히는 상황이다')
        time.sleep(7)
        ctx.check(cts.holding(did, 60) == 55555,
                  '값이 사람이 쓴 55555 그대로다. 에러 값 7777 이 들어오지 못했다 — '
                  '사람의 잠금이 에러보다 우선한다',
                  '%s (기대 55555, 에러 값 7777 이면 실패)' % cts.holding(did, 60))

        # 에러를 지워 더 적중하지 못하게 한 뒤 잠금을 푼다
        cts.ok('/error/deleteError/%d' % eid, 'DELETE')
        cts.write(did, '3_60', 55555, lock_seconds=0)
        ctx.given('에러를 지워 더 적중하지 못하게 한 뒤, lockSeconds=0 으로 사람의 잠금을 푼다. '
                  '에러의 simulation_stop 이 300초라, 막힌 에러가 정지까지 걸었다면 '
                  '여기서 잠금이 남아 있어야 한다')
        ctx.check(cts.locks(did) == [],
                  '남은 잠금이 하나도 없다. 쓰기가 막힌 에러는 simulation_stop 도 걸지 못한다 — '
                  '막힌 에러를 "발생했다" 로 처리하면 장비가 영문 모를 정지에 빠진다',
                  cts.locks(did))

        time.sleep(3)
        vals = cts.sample(did, 60, 4)
        ctx.check(len(set(vals)) > 1,
                  '잠금 해제 후 값이 다시 움직인다. 누적이 그대로 이어진다',
                  '%s (서로 다른 값 %d종)' % (vals, len(set(vals))))
    finally:
        cts.delete_device_tree(did)
