"""S24 — EtherNet/IP 태그와 타입.

target 은 태그 이름만이다. 타입은 CTS data_type 이 정하고, 패킷이 나갈 때 CIP 타입으로 바뀐다.
실제 Logix 처럼 태그 하나는 타입 하나다.

  CTS → CIP     SI8 SINT / SI16BA INT / SI32DA DINT / FP32DA REAL / BINARY BOOL (그 밖의 타입은 거절)
  예전 형식      REAL@TAG1 도 받되 태그 이름으로 저장하고, 접두어가 뜻하던 CIP 타입을 지킨다
  쓰기          등록된 태그에만, 태그의 타입으로. 다른 타입이면 REST 는 400, Write Tag 는 0xFF/0x2107
  잠금          태그 이름으로 걸린다
  응답 길이      확장 상태가 붙은 오류 응답 뒤에도 같은 연결이 어긋나지 않는다
  홀수 길이 이름  규격대로 채움 바이트가 붙은 요청을 읽는다 (etherip 디코더만으로는 못 읽었다)
"""
import time
import cts
import eipclient as E

NAME = 'EtherNet/IP 태그와 타입'


def add_sim(did, name, target, data_type, value=1, interval=1):
    return cts.api('/simulation/addSimulation', 'PUT', {
        'device': {'id': did}, 'name': name, 'target': target,
        'policies': [cts.policy(value=value, data_type=data_type)],
        'interval': interval, 'enabled': 1, 'stopOnControl': 0})


def run(ctx):
    port = cts.PORT_BASE + 171
    dev = cts.add_device(cts.PREFIX + 's24eip', port, protocol='ETHERNET_IP',
                         settings=cts.eip_settings(syn_port=2271))
    mb = cts.add_device(cts.PREFIX + 's24mb', cts.PORT_BASE + 172)
    did = dev['id']
    try:
        # ── 등록: 태그 이름 + data_type ─────────────────────────────────
        ctx.given('EtherNet/IP 장비에 태그 이름만으로 Simulation 을 등록한다: '
                  'RR=FP32DA 1.5, I8=SI8 -100, SI16=SI16BA -300, SI32=SI32DA -5, FL=BINARY 1. '
                  '예전 형식 INT@OLD1 도 등록한다. Read Tag 로 타입 코드와 값을 읽는다')
        regs = [add_sim(did, 'r', 'RR', 'FP32DA', 1.5, 60), add_sim(did, 'i8', 'I8', 'SI8', -100, 60),
                add_sim(did, 's16', 'SI16', 'SI16BA', -300, 60), add_sim(did, 's32', 'SI32', 'SI32DA', -5, 60),
                add_sim(did, 'f', 'FL', 'BINARY', 1, 60),
                add_sim(did, 'old', 'INT@OLD1', 'SI16BA', 7, 60)]
        ctx.check(all(st == 200 for st, _ in regs), '6건 모두 등록된다', [st for st, _ in regs])
        sims = {s['name']: s for s in cts.api('/simulation/simulations/%d' % did)[1]}
        old = sims.get('old', {})
        ctx.check(old.get('target') == 'OLD1' and old['policies'][0]['data_type'] == 'SI16BA',
                  '예전 형식은 태그 이름 OLD1 로 저장되고, 접두어 INT 가 뜻하던 SI16BA 가 data_type 이 된다',
                  '%s / %s' % (old.get('target'), old.get('policies', [{}])[0].get('data_type')))
        time.sleep(3)
        want = {'RR': (E.CIP_REAL, 1.5), 'I8': (E.CIP_SINT, -100), 'SI16': (E.CIP_INT, -300),
                'SI32': (E.CIP_DINT, -5), 'FL': (E.CIP_BOOL, 1), 'OLD1': (E.CIP_INT, 7)}
        got = {}
        with E.EipClient('127.0.0.1', port) as c:
            for tag in want:
                st, ext, ctype, value = c.read_tag(tag)
                got[tag] = (ctype, value)
        ctx.check(got == want,
                  'Read Tag 가 data_type 에 맞는 CIP 타입으로 나간다: REAL, SINT, INT, DINT, BOOL, INT',
                  {t: (E.CIP_NAMES.get(v[0], v[0]), v[1]) for t, v in got.items()})

        ctx.given('같은 태그를 예전 형식으로 한 번 더 등록한다(REAL@RR), UI32AD·UI16AB·SI16AB 로 등록한다')
        dup = add_sim(did, 'dup', 'REAL@RR', 'FP32DA')
        others = [add_sim(did, 'x' + dt, 'X' + dt, dt) for dt in ('UI32AD', 'UI16AB', 'SI16AB')]
        ctx.check(dup[0] == 400 and 'already used' in str(dup[1]),
                  '같은 태그는 두 번 등록할 수 없다 (태그 하나는 타입 하나)', dup)
        ctx.check(all(st == 400 and 'not allowed' in str(body) for st, body in others),
                  '부호 없는 타입·big-endian 타입은 CIP 로 보낼 수 없어 거절한다 '
                  '(SI8·SI16BA·SI32DA·FP32DA·BINARY 만)', others)

        # ── REST 쓰기 ───────────────────────────────────────────────────
        ctx.given('REST 로 쓴다: RR 에 2.5(dataType 없이), RR 에 SI16AB 로, INT@RR 로, 없는 태그 NOPE 에, '
                  'BOOL 태그 FL 에 2, 예전 형식 REAL@RR 에 3.5 + lockSeconds 10')
        w_ok = cts.write(did, 'RR', 2.5, data_type=None)
        w_dt = cts.write(did, 'RR', 1, data_type='SI16AB')
        w_px = cts.write(did, 'INT@RR', 1, data_type=None)
        w_no = cts.write(did, 'NOPE', 1, data_type=None)
        w_bool = cts.write(did, 'FL', 2, data_type=None)
        w_lock = cts.write(did, 'REAL@RR', 3.5, data_type=None, lock_seconds=10)
        ctx.check(w_ok[0] == 200, '태그 이름만으로 쓰면 태그의 타입(REAL)으로 쓴다', w_ok)
        ctx.check((w_dt[0], w_px[0]) == (400, 400),
                  '태그와 다른 타입은 dataType 으로 말하든 접두어로 말하든 400', (w_dt, w_px))
        ctx.check(w_no[0] == 501, '등록되지 않은 태그는 501', w_no)
        ctx.check(w_bool[0] == 400, 'BOOL 태그에 2 는 400', w_bool)
        ctx.check(w_lock[0] == 200 and 'RR' in cts.locks(did) and 'REAL@RR' not in cts.locks(did),
                  '잠금은 태그 이름 RR 로 걸린다 (예전에는 REAL@RR 문자열로 걸려 접두어가 다르면 맞지 않았다)',
                  '%s, locks=%s' % (w_lock[0], cts.locks(did)))

        # ── Write Tag ──────────────────────────────────────────────────
        ctx.given('한 연결에서 차례로: 없는 태그 NOPE 읽기 → SI32(DINT) 에 INT 로 쓰기 → SI32 에 DINT 99 쓰기 → '
                  'SI32 읽기 → 잠긴 RR 에 REAL 9.5 쓰기 → RR 읽기')
        with E.EipClient('127.0.0.1', port) as c:
            r_no = c.read_tag('NOPE')
            w_bad = c.write_tag('SI32', E.CIP_INT, 3)
            w_good = c.write_tag('SI32', E.CIP_DINT, 99)
            r_s32 = c.read_tag('SI32')
            w_locked = c.write_tag('RR', E.CIP_REAL, 9.5)
            r_r = c.read_tag('RR')
        ctx.check(r_no[0] == 4, '없는 태그는 상태 0x04', r_no)
        ctx.check(w_bad == (0xFF, [0x2107]),
                  '타입이 다른 Write Tag 는 실제 Logix 처럼 0xFF / 확장 0x2107 로 거절한다',
                  '(0x%02X, %s)' % (w_bad[0], ['0x%04X' % x for x in w_bad[1]]))
        ctx.check(w_good[0] == 0 and r_s32[2:] == (E.CIP_DINT, 99),
                  '같은 연결의 다음 요청이 정상이다 (확장 상태가 붙은 응답의 길이가 맞다). 맞는 타입은 쓰인다',
                  '%s / %s' % (w_good, r_s32))
        ctx.check(w_locked[0] == 0 and r_r[3] == 3.5,
                  '사람이 잠근 RR 에 온 Write Tag 는 응답은 정상이지만 값은 3.5 그대로다',
                  '%s / %s' % (w_locked, r_r))

        # ── 홀수 길이 태그 이름 ─────────────────────────────────────────
        # Simulation 은 주기별 묶음으로 돈다. 이미 도는 묶음에 넣으면 다음 차례까지 첫 값이 늦으므로
        # 셋 다 새 묶음이 되도록 주기를 서로 다르게 둔다
        regs = [add_sim(did, 'odd1', 'R', 'FP32DA', 1.25, 31), add_sim(did, 'odd3', 'S16', 'SI16BA', 12345, 32),
                add_sim(did, 'odd5', 'FLAGS', 'BINARY', 0, 33)]
        # 첫 값이 쓰일 때까지 기다린다. 쓰이기 전에는 저장소에 태그가 없어 0x04 로 답한다
        for _ in range(20):
            if {'R', 'S16', 'FLAGS'} <= set(cts.snapshot(did)['payload'].get('tags', {})):
                break
            time.sleep(0.5)
        ctx.given('길이가 홀수인 태그 R(FP32DA 1.25), S16(SI16BA 12345), FLAGS(BINARY 0)를 등록하고, 한 연결에서 '
                  '셋을 읽은 뒤 R 에 REAL 4.5, FLAGS 에 BOOL 1 을 쓰고 다시 읽는다. 요청의 심볼 세그먼트에는 '
                  '규격대로 채움 바이트 0x00 이 붙는다')
        with E.EipClient('127.0.0.1', port) as c:
            before = [c.read_tag(t)[2:] for t in ('R', 'S16', 'FLAGS')]
            writes = [c.write_tag('R', E.CIP_REAL, 4.5), c.write_tag('FLAGS', E.CIP_BOOL, 1)]
            after = [c.read_tag(t)[2:] for t in ('R', 'FLAGS')]
        ctx.check(all(st == 200 for st, _ in regs)
                  and before == [(E.CIP_REAL, 1.25), (E.CIP_INT, 12345), (E.CIP_BOOL, 0)],
                  '홀수 길이 이름도 읽힌다: R=REAL 1.25, S16=INT 12345, FLAGS=BOOL 0',
                  [(E.CIP_NAMES.get(t, t), v) for t, v in before])
        ctx.check(writes == [(0, []), (0, [])] and after == [(E.CIP_REAL, 4.5), (E.CIP_BOOL, 1)],
                  '홀수 길이 이름에 쓰기도 되고, 같은 연결이 끝까지 어긋나지 않는다',
                  '%s / %s' % (writes, [(E.CIP_NAMES.get(t, t), v) for t, v in after]))
    finally:
        cts.delete_device_tree(did)
        cts.delete_device_tree(mb['id'])
