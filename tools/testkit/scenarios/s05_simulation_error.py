"""S05 — Simulation Error 와 Simulation stop.

SIMULATION 모드 에러는 활성 상태를 두지 않고 ServerData 값을 한 번 바꾼다.
simulation_stop 을 주면 그 Target 을 그만큼 쓰기 보호해 값이 바로 덮이지 않게 한다.
0 이하는 보호 없음이다. 음수 동작은 정의하지 않는다.
"""
import time
import cts

NAME = 'Simulation Error 와 Simulation stop'


def run(ctx):
    dev = cts.add_device(cts.PREFIX + 's05', cts.PORT_BASE + 5)
    did = dev['id']
    try:
        sim = cts.add_simulation(did, 'accum', '3_50',
                                 [cts.policy(kind='ACCUMULATE', rng=[1000, 1])],
                                 interval=1)
        sid = sim['id']
        time.sleep(4)

        # ── stop 이 있는 경우 ────────────────────────────────────────────
        # 일단 잠기면 다음 적중은 그 잠금에 막혀 보호 시간을 갱신하지 못한다.
        # 그래서 '적중 시점이 언제냐' 에 따라 남은 보호 시간이 들쭉날쭉하다.
        # 20초로 넉넉히 잡아 샘플링이 만료를 넘지 않게 한다.
        ctx.given('MODBUS 장비에 ACCUMULATE range=[1000,1] 주기 1초 → 3_50 을 등록하고, '
                  '그 Simulation 을 대상으로 SIMULATION 에러를 건다: frequency=100%, '
                  'interval=3초, errorValue=4242, simulation_stop=20초. '
                  '5초 기다려 에러가 한 번 적중하게 한다')
        eid = cts.add_error(did, 'SIMULATION', frequency=100, interval=3,
                            simulation_id=sid, error_value=4242.0,
                            simulation_stop=20)
        time.sleep(5)
        ctx.check(cts.holding(did, 50) == 4242,
                  '레지스터 50 이 errorValue 4242 로 덮인다. 누적값 대신 에러 값이 보인다',
                  '%s (기대 4242)' % cts.holding(did, 50))
        ctx.check('3_50' in cts.locks(did),
                  'simulation_stop 20 이 그 target 의 쓰기 보호(lock)로 나타난다',
                  cts.locks(did))
        ctx.given('보호가 걸린 상태에서 레지스터 50 을 1초 간격으로 4회 읽는다')
        held = cts.sample(did, 50, 4)
        ctx.check(set(held) == {4242},
                  '4회 모두 4242 다. 보호 중에는 주기 1초 시뮬레이션이 덮어쓰지 못한다',
                  '%s (서로 다른 값 %d종)' % (held, len(set(held))))

        cts.ok('/error/deleteError/%d' % eid, 'DELETE')
        cts.write(did, '3_50', 4242, lock_seconds=0)   # 보호 해제
        time.sleep(3)
        ctx.given('에러를 지우고 lockSeconds=0 으로 보호를 푼 뒤 3초 기다렸다가 4회 읽는다')
        resumed = cts.sample(did, 50, 4)
        ctx.check(len(set(resumed)) > 1,
                  '값이 다시 움직인다. 보호가 풀리면 누적이 이어진다',
                  '%s (서로 다른 값 %d종)' % (resumed, len(set(resumed))))

        # ── stop 이 음수인 경우 ──────────────────────────────────────────
        ctx.given('같은 장비에 simulation_stop 이 음수인 에러를 건다: mode=SIMULATION, '
                  'interval=3초, errorValue=2222, simulation_stop=-30. '
                  '음수는 정의된 동작이 없으므로 0(정지 없음)과 같아야 한다. 5초 대기')
        eid = cts.add_error(did, 'SIMULATION', frequency=100, interval=3,
                            simulation_id=sid, error_value=2222.0,
                            simulation_stop=-30)
        time.sleep(5)
        ctx.check(cts.locks(did) == [],
                  '잠금이 걸리지 않는다. 음수를 "무한 정지" 로 해석하지 않는다', cts.locks(did))
        vals = cts.sample(did, 50, 5)
        ctx.check(len(set(vals)) > 1,
                  '에러 값 2222 와 누적값이 섞여 나온다. 에러는 값만 덮고 시뮬레이션은 계속 돈다',
                  '%s (서로 다른 값 %d종)' % (vals, len(set(vals))))
    finally:
        cts.delete_device_tree(did)
