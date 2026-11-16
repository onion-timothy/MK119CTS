"""S23 — Test Plan.

Action 은 실행 시각(At, 시작부터 센 초)에 실행된다. 같은 시각의 Action 들은 한 차례에 함께 쓴다.
쓰기는 REST 쓰기와 같은 경로(API_WRITE)라 lockSeconds 동안 시뮬레이션이 값을 덮지 못한다.

  등록 검증    규칙 ID(docs/TEST_PLAN_VALIDATION.md)로 오류를 돌려준다
  수동 실행    실행 시각, 같은 시각의 동시 변경, lock
  실패 처리    기본은 기록하고 계속, Stop 이면 그 자리에서 중단 — 같은 시각의 뒤 Action 도 쓰지 않는다
  실행 중      재실행·수정·삭제는 409, Stop 은 남은 Action 을 실행하지 않는다
  스케줄       초 지정 스케줄이 실행을 시작한다. 실행 중이면 이번 차례를 건너뛴다
  엑셀        시트 = Plan. 검증 → 등록, 내보낸 것을 그대로 다시 검증할 수 있다
"""
import time
import datetime
import cts

NAME = 'Test Plan'


def act(device_id, target, value, at, data_type='UI16AB', lock=None, stop=None):
    a = {'deviceId': device_id, 'target': target, 'value': value,
         'dataType': data_type, 'atSeconds': at}
    if lock is not None:
        a['lockSeconds'] = lock
    if stop is not None:
        a['stopOnFailure'] = stop
    return a


def create(name, actions, **schedule):
    body = {'name': cts.PREFIX + name, 'actions': actions}
    body.update(schedule)
    return cts.api('/testplans', 'POST', body)


def rules(body):
    return sorted({e['rule'] for e in (body or {}).get('errors', [])}) if isinstance(body, dict) else body


def wait_finished(plan_id, seconds=15):
    for _ in range(seconds * 4):
        st, plans = cts.api('/testplans/%d' % plan_id)
        if st == 200 and not plans.get('running'):
            return True
        time.sleep(0.25)
    return False


def last_run(plan_id):
    st, runs = cts.api('/testplans/%d/runs' % plan_id)
    if st != 200 or not runs:
        return None, []
    st, detail = cts.api('/testplans/runs/%d' % runs[0]['id'])
    return runs[0], detail.get('actions', []) if st == 200 else []


def run(ctx):
    dev = cts.add_device(cts.PREFIX + 's23', cts.PORT_BASE + 165)
    gone = cts.add_device(cts.PREFIX + 's23gone', cts.PORT_BASE + 166)
    did = dev['id']
    plans = []
    extra = []
    try:
        # ── 등록 검증 ────────────────────────────────────────────────────
        ctx.given('POST /api/testplans 에 이름이 비고 Action 이 없는 Plan, dataType 없는 레지스터 Action, '
                  'UI16AB 에 12.5 를 넣은 Action, 초 지정 스케줄인데 마지막 Action 이 60초인 Plan, '
                  '가장 이른 Action 이 3초인 Plan 을 차례로 보낸다')
        r1 = cts.api('/testplans', 'POST', {'name': '  ', 'actions': []})   # 접두어 없이 빈 이름 그대로
        r2 = create('bad1', [act(did, '3_1', 1, 0, data_type=None)])
        r3 = create('bad2', [act(did, '3_1', 12.5, 0)])
        r4 = create('bad3', [act(did, '3_1', 1, 0), act(did, '3_2', 1, 60)],
                    scheduleType='SECOND', scheduleSecond=0, scheduleEnabled=True)
        r5 = create('bad4', [act(did, '3_1', 1, 5), act(did, '3_2', 1, 3)])
        ctx.check(r1[0] == 400 and rules(r1[1]) == ['P1', 'P3'],
                  '이름 없음·Action 없음은 400 과 P1, P3', '%s %s' % (r1[0], rules(r1[1])))
        ctx.check(r2[0] == 400 and rules(r2[1]) == ['A6'],
                  'dataType 없는 Modbus 레지스터는 A6', '%s %s' % (r2[0], rules(r2[1])))
        ctx.check(r3[0] == 400 and rules(r3[1]) == ['A10'],
                  '정수형의 소수는 A10', '%s %s' % (r3[0], rules(r3[1])))
        ctx.check(r4[0] == 400 and rules(r4[1]) == ['S3'],
                  '실행 시간(60초)이 주기(60초)보다 짧지 않으면 S3', '%s %s' % (r4[0], rules(r4[1])))
        ctx.check(r5[0] == 400 and rules(r5[1]) == ['A16'],
                  '가장 이른 Action 이 0초가 아니면 A16', '%s %s' % (r5[0], rules(r5[1])))

        # ── 수동 실행: 실행 시각, 동시 변경, lock ────────────────────────
        cts.add_simulation(did, 'v10', '3_10', [cts.policy(value=100)], interval=1)
        time.sleep(3)
        st, body = create('order', [
            act(did, '3_10', 500, 0, lock=3),   # 3초 잠근다. 시뮬레이션(STATIC 100, 1초)이 덮지 못한다
            act(did, '3_11', 7, 2),             # ②·③ 은 같은 2초 → 한 차례에 함께
            act(did, '3_12', 8, 2)])
        pid = body['plan']['id']
        plans.append(pid)
        ctx.given('STATIC 100 주기 1초 → 3_10 이 도는 장비에 Plan 을 만든다: '
                  '① 0초 3_10=500 lockSeconds 3 ② 2초 3_11=7 ③ 2초 3_12=8. '
                  'POST /run 후 1초, 2.6초, 5.5초에 레지스터를 읽는다')
        t0 = time.time()
        st, started = cts.api('/testplans/%d/run' % pid, 'POST')
        time.sleep(max(0, 1 - (time.time() - t0)))
        at1 = (cts.holding(did, 10), cts.holding(did, 11), cts.holding(did, 12))
        time.sleep(max(0, 2.6 - (time.time() - t0)))
        at26 = (cts.holding(did, 10), cts.holding(did, 11), cts.holding(did, 12))
        st26, p26 = cts.api('/testplans/%d' % pid)
        finished = wait_finished(pid)
        time.sleep(max(0, 5.5 - (time.time() - t0)))
        at55 = cts.holding(did, 10)
        ctx.check(st == 200 and started.get('runId'), 'POST /run 이 200 과 runId 를 돌려준다', started)
        ctx.check(at1 == (500, 0, 0),
                  '1초: ①만 실행됐다. 3_10 은 500 이고 잠겨 있어 시뮬레이션이 100 으로 되돌리지 못한다',
                  '3_10, 3_11, 3_12 = %s' % (at1,))
        ctx.check(at26[1:] == (7, 8),
                  '2.6초: 2초의 ②와 ③이 함께 실행됐다',
                  '3_10, 3_11, 3_12 = %s' % (at26,))
        ctx.check(st26 == 200 and p26.get('running') and p26.get('durationSeconds') == 3,
                  '2.6초: Action 은 다 썼지만 ①의 lock(0+3초)이 풀릴 때까지 실행 중이다. 실행 시간은 3초',
                  'running=%s, durationSeconds=%s' % (p26.get('running'), p26.get('durationSeconds')))
        ctx.check(at55 == 100,
                  '5.5초: 3초 잠금이 풀려 시뮬레이션이 다시 100 을 쓴다. Plan 이 끝나도 잠금을 따로 풀지 않는다',
                  '3_10 = %s' % at55)
        run1, acts1 = last_run(pid)
        ctx.check(finished and run1 and run1['result'] == 'COMPLETED' and run1['totalActions'] == 3
                  and [a['status'] for a in acts1] == ['OK', 'OK', 'OK'],
                  '실행 이력: 결과 COMPLETED, Action 3개 모두 OK, trigger MANUAL',
                  '%s / %s' % (run1 and {k: run1[k] for k in ('result', 'triggerType', 'totalActions')},
                               [a['status'] for a in acts1]))

        # ── 실패 처리 ───────────────────────────────────────────────────
        # 쓰기가 실행 중에 실패하는 경로로 꺼진(enabled=0) 장비를 쓴다 — 404 device_not_available
        off = cts.add_device(cts.PREFIX + 's23off', cts.PORT_BASE + 167, enabled=0)
        extra.append(off['id'])
        oid = off['id']
        st, b1 = create('skip', [act(oid, '3_1', 1, 0), act(did, '3_20', 55, 0)])
        st, b2 = create('fail', [act(oid, '3_1', 1, 0, stop=True), act(did, '3_21', 66, 0)])
        skip_id, fail_id = b1['plan']['id'], b2['plan']['id']
        plans += [skip_id, fail_id]
        ctx.given('꺼진(enabled=0) 장비에 쓰는 Action 을 첫째로 둔 Plan 두 개(Stop 비움 / Stop=TRUE)를 만든다. '
                  '둘째 Action 은 같은 0초에 살아 있는 장비의 3_20=55 / 3_21=66 을 쓴다. 두 Plan 을 실행한다')
        cts.api('/testplans/%d/run' % skip_id, 'POST')
        cts.api('/testplans/%d/run' % fail_id, 'POST')
        wait_finished(skip_id)
        wait_finished(fail_id)
        rs, as_ = last_run(skip_id)
        rf, af = last_run(fail_id)
        ctx.check(rs and rs['result'] == 'COMPLETED_WITH_SKIPS' and cts.holding(did, 20) == 55
                  and as_ and as_[0]['status'] == 'SKIPPED' and 'device_not_available' in (as_[0]['error'] or ''),
                  'Stop 비움(기본): 첫 Action 은 SKIPPED(device_not_available) 로 남고 둘째가 실행된다. '
                  '결과 COMPLETED_WITH_SKIPS',
                  '%s, 3_20=%s, %s' % (rs and rs['result'], cts.holding(did, 20),
                                       as_ and (as_[0]['status'], as_[0]['error'])))
        ctx.check(rf and rf['result'] == 'FAILED' and cts.holding(did, 21) == 0 and len(af) == 1,
                  'Stop=TRUE: 결과 FAILED, 그 자리에서 멈춰 같은 시각의 둘째 Action 도 실행하지 않는다 (3_21 은 0, 기록도 1건)',
                  '%s "%s", 3_21=%s, 기록 %d건' % (rf and rf['result'], rf and rf['message'],
                                                cts.holding(did, 21), len(af)))

        # ── 장비 삭제: Action 정리 ──────────────────────────────────────
        gid = gone['id']
        st, b3 = create('trim', [act(gid, '3_1', 1, 0), act(did, '3_22', 77, 5), act(did, '3_23', 88, 9)])
        st, b4 = create('only', [act(gid, '3_1', 1, 0), act(gid, '3_2', 2, 3)])
        trim_id, only_id = b3['plan']['id'], b4['plan']['id']
        plans += [trim_id, only_id]
        ctx.given('지울 장비의 Action 을 0초에 둔 Plan(trim: 그 뒤 5초·9초에 살아 있는 장비) 과 '
                  '그 장비의 Action 만 가진 Plan(only) 을 만든 뒤 DELETE /api/device/deleteDevice/{id} 로 장비를 지운다')
        st_del, res_del = cts.api('/device/deleteDevice/%d' % gid, 'DELETE')
        st_t, trim = cts.api('/testplans/%d' % trim_id)
        st_o, _ = cts.api('/testplans/%d' % only_id)
        trim_acts = [(a['target'], a['atSeconds']) for a in (trim or {}).get('actions', [])] if st_t == 200 else None
        ctx.check(st_del == 200 and (res_del or {}).get('deletedPlans') == [cts.PREFIX + 'only'],
                  '삭제 응답의 deletedPlans 에 Action 이 하나도 안 남은 Plan 이름이 실린다',
                  '%s %s' % (st_del, res_del))
        ctx.check(st_o == 404,
                  'Action 이 모두 그 장비 것이던 Plan 은 함께 지워진다 (남겨 두면 검증 A1 에 걸려 다시 저장할 수 없다)',
                  st_o)
        ctx.check(trim_acts == [('3_22', 0), ('3_23', 4)],
                  '남은 Plan 에서는 그 장비의 Action 만 빠지고, 가장 이른 Action 이 0초가 되도록 '
                  '시각을 같은 만큼 당긴다(A16): 5초·9초 → 0초·4초',
                  trim_acts)

        # ── 실행 중: 409 와 Stop ────────────────────────────────────────
        st, b = create('long', [act(did, '3_30', 1, 0), act(did, '3_31', 2, 10)])
        long_id = b['plan']['id']
        plans.append(long_id)
        cts.api('/testplans/%d/run' % long_id, 'POST')
        ctx.given('① 0초 3_30=1 ② 10초 3_31=2 인 Plan 을 실행하고, 대기 중에 다시 실행·수정·삭제를 보낸 뒤 Stop 한다')
        again = cts.api('/testplans/%d/run' % long_id, 'POST')[0]
        put = cts.api('/testplans/%d' % long_id, 'PUT', {'name': cts.PREFIX + 'long', 'actions': [act(did, '3_30', 1, 0)]})[0]
        dele = cts.api('/testplans/%d' % long_id, 'DELETE')[0]
        ctx.check((again, put, dele) == (409, 409, 409),
                  '실행 중에는 재실행·수정·삭제가 모두 409 다', (again, put, dele))
        st, stopped = cts.api('/testplans/%d/stop' % long_id, 'POST')
        time.sleep(1)
        rl, al = last_run(long_id)
        ctx.check(stopped.get('stopped') and rl and rl['result'] == 'STOPPED' and len(al) == 1
                  and cts.holding(did, 31) == 0,
                  'Stop: 결과 STOPPED("Stopped by user"), ②는 실행하지 않는다',
                  '%s "%s", 기록 %d건, 3_31=%s' % (rl and rl['result'], rl and rl['message'], len(al),
                                                cts.holding(did, 31)))

        # ── 스케줄 ──────────────────────────────────────────────────────
        ctx.given('스케줄이 없는 Plan 의 스케줄을 켠다 (POST /schedule/enabled/1)')
        st, b = cts.api('/testplans/%d/schedule/enabled/1' % long_id, 'POST')
        ctx.check(st == 400, '스케줄이 없으면 켤 수 없다 (400)', '%s %s' % (st, b))

        fire = (datetime.datetime.now() + datetime.timedelta(seconds=5)).second
        st, b = create('cron', [act(did, '3_40', 4321, 0), act(did, '3_41', 1, 6)],
                       scheduleType='SECOND', scheduleSecond=fire, scheduleEnabled=True)
        cron_id = b['plan']['id']
        plans.append(cron_id)
        ctx.given('매분 %d초(약 5초 뒤)에 도는 초 지정 스케줄 Plan(0초 3_40=4321, 6초 3_41=1)을 만들고 '
                  '실행 이력에 SCHEDULE 실행이 생길 때까지 기다린다' % fire)
        ctx.check(b['plan']['scheduleEnabled'] and b['plan']['nextRunAt'],
                  '응답에 다음 실행 시각이 실린다', b['plan']['nextRunAt'])
        runs = []
        for _ in range(12):
            time.sleep(1)
            runs = cts.api('/testplans/%d/runs' % cron_id)[1]
            if runs:
                break
        ctx.check(runs and runs[0]['triggerType'] == 'SCHEDULE' and cts.holding(did, 40) == 4321,
                  '스케줄 시각에 실행이 시작된다 (trigger SCHEDULE, 3_40=4321)',
                  '%s, 3_40=%s' % (runs and runs[0]['triggerType'], cts.holding(did, 40)))
        cts.api('/testplans/%d/stop' % cron_id, 'POST')

        # 실행 중에 스케줄 시각이 오면 그 차례는 건너뛴다(E2)
        fire2 = (datetime.datetime.now() + datetime.timedelta(seconds=4)).second
        cts.api('/testplans/%d' % cron_id, 'PUT', {
            'name': cts.PREFIX + 'cron', 'actions': [act(did, '3_40', 4321, 0), act(did, '3_41', 1, 10)],
            'scheduleType': 'SECOND', 'scheduleSecond': fire2, 'scheduleEnabled': True})
        before = len(cts.api('/testplans/%d/runs' % cron_id)[1])
        cts.api('/testplans/%d/run' % cron_id, 'POST')
        ctx.given('같은 Plan 의 둘째 Action 을 10초로 고치고 스케줄을 약 4초 뒤(%d초)로 옮긴 뒤 바로 수동 실행한다. '
                  '스케줄 시각에 수동 실행이 아직 돌고 있다' % fire2)
        time.sleep(7)
        runs = cts.api('/testplans/%d/runs' % cron_id)[1]
        ctx.check(len(runs) == before + 1 and runs[0]['triggerType'] == 'MANUAL',
                  '스케줄 차례가 지나도 실행 이력은 수동 실행 1건만 늘었다. 실행 중이면 다시 시작하지 않는다',
                  '이력 %d → %d건, 최신 trigger %s' % (before, len(runs), runs and runs[0]['triggerType']))
        cts.api('/testplans/%d/stop' % cron_id, 'POST')
        cts.api('/testplans/%d/schedule/enabled/0' % cron_id, 'POST')

        # ── 엑셀 ────────────────────────────────────────────────────────
        header = ['RunAtSeconds', 'Device', 'Target', 'Value', 'DataType', 'LockSeconds', 'StopOnFailure']
        good = {'name': cts.PREFIX + 'xl_good', 'rows': [
            header,
            [0, dev['name'], '3_50', 1, 'UI16AB', None, 'FALSE'],
            [None, None, None, None, None, None, None],
            [1, dev['name'], '1_3', 1, None, 5, 'TRUE']]}
        bad = {'name': cts.PREFIX + 'xl_bad', 'rows': [
            header,
            [0, 'nope', '3_50', 1, 'UI16AB', None, None],
            [0, dev['name'], '3_51', 70000, 'UI16AB', None, None]]}
        ctx.given('시트 두 장(정상 1장, 2·3행이 틀린 1장)을 POST /api/testplans/validate 와 /import 에 보낸다')
        st, v = cts.api('/testplans/validate', 'POST', {'sheets': [good, bad]})
        bad_errs = [(e['rule'], e['row']) for e in v['sheets'][1]['errors']] if st == 200 else v
        ctx.check(st == 200 and not v['ok'] and v['sheets'][0]['ok'] and v['sheets'][0]['actionCount'] == 2
                  and bad_errs == [('A1', 2), ('A10', 3)],
                  '검증: 정상 시트는 Action 2개(빈 행 제외), 틀린 시트는 2행 A1, 3행 A10. 전체 ok=false',
                  '%s, 정상 %s, 오류 %s' % (st, st == 200 and v['sheets'][0]['actionCount'], bad_errs))
        st, imp = cts.api('/testplans/import', 'POST', {'sheets': [good, bad]})
        names = [p['name'] for p in cts.api('/testplans')[1]]
        ctx.check(st == 400 and cts.PREFIX + 'xl_good' not in names,
                  '오류가 하나라도 있으면 400 이고 정상 시트도 등록하지 않는다', '%s, 등록됨=%s' % (st, cts.PREFIX + 'xl_good' in names))
        st, imp = cts.api('/testplans/import', 'POST', {'sheets': [good]})
        new_id = imp['plans'][0]['id'] if st == 200 else None
        if new_id:
            plans.append(new_id)
        st2, exported = cts.api('/testplans/export?ids=%s' % new_id)
        rows = exported[0]['rows'] if st2 == 200 and exported else []
        ctx.check(st == 200 and imp['plans'][0]['actionCount'] == 2 and rows[1:] == [
            [0, dev['name'], '3_50', 1.0, 'UI16AB', 0, 'FALSE'],
            [1, dev['name'], '1_3', 1.0, None, 5, 'TRUE']],
                  '정상 시트만 보내면 등록되고, 내보내기는 같은 시트 형식으로 돌아온다',
                  '%s %s / %s' % (st, imp, rows))
        again = dict(exported[0], name=cts.PREFIX + 'xl_again') if rows else {}
        st, v2 = cts.api('/testplans/validate', 'POST', {'sheets': [again]})
        ctx.check(st == 200 and v2['ok'],
                  '내보낸 시트를 이름만 바꿔 다시 검증하면 통과한다', v2)

        # ── 삭제 ────────────────────────────────────────────────────────
        run_id = run1['id'] if run1 else 0
        ctx.given('수동 실행 이력이 있는 Plan(order)을 지운다')
        st, _ = cts.api('/testplans/%d' % pid, 'DELETE')
        plans.remove(pid)
        ctx.check(st == 200 and cts.api('/testplans/%d' % pid)[0] == 404
                  and cts.api('/testplans/runs/%d' % run_id)[0] == 404,
                  '삭제되고, 그 Plan 과 실행 이력 조회가 모두 404 다',
                  '%s, plan=%s, run=%s' % (st, cts.api('/testplans/%d' % pid)[0],
                                           cts.api('/testplans/runs/%d' % run_id)[0]))
    finally:
        for p in plans:
            cts.api('/testplans/%d/stop' % p, 'POST')
            cts.api('/testplans/%d' % p, 'DELETE')
        cts.delete_device_tree(did)
        cts.delete_device_tree(gone['id'])
        for x in extra:
            cts.delete_device_tree(x)
