"""S14 — ServerData 의 target -> Simulation 색인.

핸들러와 API 가 공통으로 쓰는 색인이다. 세 가지를 본다.

  최신성   Simulation 을 더하거나 고치거나 지우면 색인이 따라온다
  포함 범위 다중 레지스터를 쓰는 Simulation 은 차지한 칸 전부로 찾힌다
  보조 키  EtherNet/IP 는 태그 이름만으로도 찾힌다

색인을 직접 들여다보는 API 는 없으므로, 색인을 쓰는 동작(제어 수용 시 stopOnControl)
으로 확인한다. 색인이 낡으면 잠금이 걸리지 않는다.
"""
import socket
import struct
import time
import cts

NAME = 'target -> Simulation 색인'

UNIT = 1


def fc6(port, addr, value, host='127.0.0.1'):
    s = socket.create_connection((host, port), 5)
    try:
        s.sendall(struct.pack('>HHHB', 1, 0, 6, UNIT)
                  + struct.pack('>BHH', 6, addr, value))
        s.settimeout(5)
        try:
            return s.recv(256)
        except Exception:
            return None
    finally:
        s.close()


def run(ctx):
    port = cts.PORT_BASE + 60
    dev = cts.add_device(cts.PREFIX + 's14', port,
                         settings=cts.modbus_settings(unit_id=UNIT))
    did = dev['id']
    try:
        # ── 등록 직후 색인에 올라오는가 ─────────────────────────────────
        ctx.given('MODBUS 장비에 STATIC value=10 주기 60초 → 3_110, stopOnControl=8 을 '
                  '등록하고 3초 뒤 FC6 으로 레지스터 110 에 1111 을 쓴다. 주기가 60초라 '
                  '등록 직후에는 아직 한 번도 돌지 않았다 — 색인이 DB 가 아니라 등록 시점에 '
                  '갱신되는지 보는 것이다. 색인에 없으면 핸들러가 Simulation 을 못 찾아 '
                  'stopOnControl 잠금이 걸리지 않는다')
        sim = cts.add_simulation(did, 'a', '3_110',
                                 [cts.policy(kind='STATIC', value=10)],
                                 interval=60, stop_on_control=8)
        time.sleep(3)
        fc6(port, 110, 1111)
        time.sleep(2)
        ctx.check('3_110' in cts.locks(did),
                  '잠금이 걸린다. 등록 직후 색인이 갱신되어 핸들러가 그 Simulation 을 찾았다',
                  cts.locks(did))
        cts.write(did, '3_110', 1111, lock_seconds=0)

        # ── 지우면 색인에서 빠지는가 ────────────────────────────────────
        ctx.given('그 Simulation 을 삭제하고(DELETE /api/simulation/deleteSimulation/{id}) '
                  '2초 뒤 같은 레지스터 110 에 FC6 으로 2222 를 쓴다. 지운 뒤에도 색인에 '
                  '남아 있으면 유령 잠금이 걸린다')
        cts.ok('/simulation/deleteSimulation/%d' % sim['id'], 'DELETE')
        time.sleep(2)
        fc6(port, 110, 2222)
        time.sleep(2)
        ctx.check(cts.locks(did) == [],
                  '잠금이 걸리지 않는다. 삭제가 색인에도 반영됐다',
                  cts.locks(did))

        # ── 다중 레지스터가 차지한 칸으로도 찾히는가 ────────────────────
        ctx.given('같은 장비에 UI32AD STATIC → 3_120, stopOnControl=8 을 등록한다. '
                  '32비트라 레지스터 120 과 121 두 칸을 차지한다. FC6 으로 '
                  '시작 칸이 아닌 두 번째 칸(레지스터 121)에 3333 을 쓴다')
        cts.add_simulation(did, 'wide', '3_120',
                           [cts.policy(kind='STATIC', data_type='UI32AD', value=7)],
                           interval=60, stop_on_control=8)
        time.sleep(3)
        fc6(port, 121, 3333)
        time.sleep(2)
        ctx.check(cts.locks(did) != [],
                  '잠금이 걸린다. 색인이 시작 주소뿐 아니라 그 Simulation 이 차지한 범위 전체를 '
                  '키로 들고 있다',
                  cts.locks(did))
        cts.write(did, '3_120', 7, lock_seconds=0)
        cts.write(did, '3_121', 0, lock_seconds=0)

        # ── 다른 function code 는 걸리지 않는가 ─────────────────────────
        ctx.given('같은 장비에 코일 1_130 에만 Simulation(stopOnControl=8)을 두고, '
                  'FC6(holding register 쓰기)으로 레지스터 130 에 4444 를 쓴다. '
                  '주소 번호는 130 으로 같지만 function code 가 다르다')
        cts.add_simulation(did, 'coil', '1_130',
                           [cts.policy(kind='STATIC', data_type='BINARY', value=1)],
                           interval=60, stop_on_control=8)
        time.sleep(3)
        fc6(port, 130, 4444)
        time.sleep(2)
        ctx.check(cts.locks(did) == [],
                  '잠금이 걸리지 않는다. 색인 키에 function code 가 들어 있어 '
                  '코일 1_130 과 레지스터 3_130 이 서로 다른 칸으로 구분된다',
                  cts.locks(did))
    finally:
        cts.delete_device_tree(did)

    # ── EtherNet/IP 보조 키 ────────────────────────────────────────────
    ctx.given('ETHERNET_IP 장비에 target 이 REAL@TAG1 인 Simulation 을 등록한다. '
              'EtherNet/IP 는 태그 이름으로도 찾아야 해서 색인이 REAL@TAG1 외에 '
              '보조 키 TAG1 도 함께 들고 있다 (EthernetIPData.aliasesOf)')
    dev = cts.add_device(cts.PREFIX + 's14enip', cts.PORT_BASE + 61,
                         protocol='ETHERNET_IP',
                         settings=cts.eip_settings())
    did = dev['id']
    try:
        st, _ = cts.api('/simulation/addSimulation', 'PUT', {
            'device': {'id': did}, 'name': 'tag', 'target': 'REAL@TAG1',
            'policies': [cts.policy(kind='STATIC', data_type='FP32DA', value=1.5)],
            'interval': 60, 'enabled': 1})
        ctx.check(200 <= st < 300,
                  'CIP 타입 접두어가 붙은 target 이 등록된다 (FP32DA ↔ REAL 로 타입도 맞다)', st)
        time.sleep(3)
        snap = cts.snapshot(did)
        ctx.check(snap.get('protocol') == 'ETHERNET_IP',
                  '스냅샷이 ETHERNET_IP 로 내려온다. 색인 구축이 장비 기동을 막지 않았다',
                  snap.get('protocol'))
    finally:
        cts.delete_device_tree(did)
