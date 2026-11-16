"""S03 — 쓰기 보호(lock).

lockSeconds 가 걸린 동안 Value Simulation 이 값을 덮지 못하고,
ACCUMULATE 의 누적도 나아가지 않는다. 시간이 지나면 스스로 풀린다.

누적이 멈추는지가 중요하다. 잠금 검사가 값을 만든 뒤에 있으면 쓰기만 버려지고
누적은 계속 흘러, 잠금이 풀리는 순간 값이 껑충 뛴다.
"""
import time
import cts

NAME = '쓰기 보호와 누적 동결'


def run(ctx):
    dev = cts.add_device(cts.PREFIX + 's03', cts.PORT_BASE + 3)
    did = dev['id']
    try:
        cts.add_simulation(did, 'accum', '3_40',
                           [cts.policy(kind='ACCUMULATE', rng=[1000, 1])], interval=1)
        time.sleep(4)

        ctx.given('MODBUS 장비에 ACCUMULATE range=[1000,1] 주기 1초 → 3_40 을 등록하고 4초 돌린 뒤, '
                  'REST 로 {target: "3_40", value: 60000, lockSeconds: 10} 을 쓴다. '
                  '사람이 값을 고정해 두는 상황이다')
        before = cts.holding(did, 40)
        st, _ = cts.write(did, '3_40', 60000, lock_seconds=10)
        ctx.check(st == 200, 'HTTP 200 으로 수용된다 (쓰기와 잠금이 함께 걸린다)', st)
        ctx.check('3_40' in cts.locks(did),
                  '스냅샷의 locks 에 3_40 이 올라온다', cts.locks(did))

        ctx.given('잠금이 걸린 상태에서 레지스터 40 을 1초 간격으로 5회 읽는다. '
                  '주기 1초 ACCUMULATE 가 계속 돌고 있지만 쓰지 못해야 한다')
        held = cts.sample(did, 40, 5)
        ctx.check(set(held) == {60000},
                  '5회 모두 사람이 쓴 60000 이다. 시뮬레이션이 덮어쓰지 못한다',
                  '%s (서로 다른 값 %d종)' % (held, len(set(held))))

        # 잠금 10초. 4초를 샘플링했으니 7초 더 자면 만료 직후가 된다.
        # 만료 후 흐른 시간이 1초 남짓이어야 '누적이 멈췄는지' 를 깨끗하게 잴 수 있다.
        time.sleep(7)
        ctx.given('lockSeconds 10 이 지나도록 기다린다 (샘플링 4초 + 7초)')
        ctx.check(cts.locks(did) == [],
                  '만료되어 locks 가 빈다. 따로 풀어 주지 않아도 스스로 풀린다', cts.locks(did))

        after = cts.holding(did, 40)
        ctx.check(after - before <= 3,
                  '잠금 전후의 누적값 차이가 3 이하다. 잠긴 10초 동안 누적이 멈춰 있었다는 뜻이다 '
                  '(계속 돌았다면 10 이상 올랐을 것)',
                  '%s -> %s (증가 %s)' % (before, after, after - before))

        ctx.given('잠금이 풀린 뒤 레지스터 40 을 1초 간격으로 4회 읽는다')
        moved = cts.sample(did, 40, 4)
        ctx.check(len(set(moved)) > 1,
                  '값이 다시 바뀐다. 잠금은 멈춤일 뿐 시뮬레이션을 끄지 않는다',
                  '%s (서로 다른 값 %d종)' % (moved, len(set(moved))))

        ctx.given('같은 target 에 lockSeconds=0 으로 쓴다 (값만 바꾸고 잠그지는 않겠다는 뜻)')
        cts.write(did, '3_40', 12345, lock_seconds=0)
        ctx.check(cts.locks(did) == [],
                  'locks 가 비어 있다. 0 은 잠금을 걸지 않는다', cts.locks(did))
    finally:
        cts.delete_device_tree(did)
