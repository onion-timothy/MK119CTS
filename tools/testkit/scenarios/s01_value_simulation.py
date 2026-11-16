"""S01 — Value Simulation 이 주기마다 ServerData 에 값을 쓴다.

정책 세 가지가 실제로 다르게 움직이는지 본다.
  STATIC      값이 고정된다
  RANDOM      범위 안에서 흔들린다
  ACCUMULATE  초기값에서 증가분만큼 쌓인다
"""
import time
import cts

NAME = 'Value Simulation 정책'


def run(ctx):
    dev = cts.add_device(cts.PREFIX + 's01', cts.PORT_BASE + 1)
    did = dev['id']
    try:
        cts.add_simulation(did, 'static', '3_10',
                           [cts.policy(kind='STATIC', value=777)], interval=1)
        cts.add_simulation(did, 'random', '3_11',
                           [cts.policy(kind='RANDOM', rng=[100, 200])], interval=1)
        cts.add_simulation(did, 'accum', '3_12',
                           [cts.policy(kind='ACCUMULATE', rng=[1000, 1])], interval=1)
        time.sleep(4)

        ctx.given('MODBUS 장비(unitId=1, holding register 30000칸)에 주기 1초 Simulation 3건을 '
                  '등록한다: STATIC value=777 → 3_10, RANDOM range=[100,200] → 3_11, '
                  'ACCUMULATE range=[1000,1](시작 1000, 주기마다 +1) → 3_12. '
                  '4초를 기다려 주기가 여러 번 돈 뒤, 레지스터 10 을 1초 간격으로 4회 읽는다.')
        s = cts.sample(did, 10, 4)
        ctx.check(set(s) == {777},
                  'STATIC: 4회 샘플이 모두 지정값 777 이다. 주기가 돌아도 값이 바뀌지 않는다',
                  '%s (서로 다른 값 %d종)' % (s, len(set(s))))

        ctx.given('같은 장비의 레지스터 11 (RANDOM range=[100,200], 주기 1초) 을 '
                  '1초 간격으로 5회 읽는다')
        r = cts.sample(did, 11, 5)
        ctx.check(len(set(r)) > 1,
                  'RANDOM: 5회 샘플에 서로 다른 값이 섞여 있다. 주기마다 새로 뽑는다',
                  '%s (서로 다른 값 %d종)' % (r, len(set(r))))
        ctx.check(all(100 <= v <= 200 for v in r if v is not None),
                  'RANDOM: 모든 샘플이 지정 범위 100~200 안에 있다',
                  '%s (최소 %s, 최대 %s)' % (r, min(r), max(r)))

        ctx.given('같은 장비의 레지스터 12 (ACCUMULATE 시작값 1000, 증분 1, 주기 1초) 를 '
                  '1초 간격으로 4회 읽는다')
        a = cts.sample(did, 12, 4)
        rising = all(b is not None and x is not None and b >= x
                     for x, b in zip(a, a[1:]))
        ctx.check(rising and a[-1] > a[0],
                  'ACCUMULATE: 값이 한 번도 줄지 않고, 마지막 샘플이 첫 샘플보다 크다',
                  '%s (%s → %s, 증가 %s)' % (a, a[0], a[-1], a[-1] - a[0]))
    finally:
        cts.delete_device_tree(did)
