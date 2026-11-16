# testkit — 주요 기능 시나리오 테스트

서버를 실제로 띄운 상태에서 REST API 와 프로토콜 소켓, 그리고 화면까지 두드려 보는
통합 테스트다. 단위 테스트가 아니다. 주기 작업·잠금·에러 상태처럼 **시간이 지나야
드러나는 동작**을 확인하는 것이 목적이라 대부분의 시나리오가 수 초씩 기다린다.

설치할 것이 없다. 파이썬 표준 라이브러리만 쓴다. 화면을 쓰는 시나리오는 헤드리스
Chrome 을 CDP 로 직접 조작한다(`cdp.py`).

## 준비

서버가 `localhost:8082` 에 떠 있어야 한다.

```bash
cd MK119CommunicationTestSuite && ./gradlew bootRun
```

## 실행

```bash
python3 tools/testkit/run.py              # 전체 (약 8분 30초)
python3 tools/testkit/run.py s03 s06      # 이름 앞부분으로 골라서
python3 tools/testkit/run.py --list       # 목록만
python3 tools/testkit/run.py --report=report.md   # 케이스별 상세 보고서까지
```

## 시나리오

| 시나리오 | 무엇을 보는가 | 설명서 |
|---|---|---|
| `s01_value_simulation` | STATIC / RANDOM / ACCUMULATE 가 주기마다 ServerData 에 값을 쓴다 | VI |
| `s02_serverdata_api` | REST 쓰기. 32비트는 레지스터 두 칸. 잘못된 입력이 400/404 로 구분된다 | IX |
| `s03_write_lock` | 잠금이 Value Simulation 을 막고, 잠긴 동안 **누적이 나아가지 않는다** | VIII, IX |
| `s04_system_error` | System Error 등록·해제, Interval/Duration 저장 | VII |
| `s05_simulation_error` | 에러 값 쓰기, `simulation_stop` 이 잠금으로 걸린다. 음수는 무동작 | VII |
| `s06_lock_priority` | **사람의 잠금이 최우선.** 막힌 에러는 값도 못 쓰고 정지도 못 건다 | VII |
| `s07_stop_on_control` | Modbus FC6 제어 수용 → `stopOnControl` 이 잠금으로 걸린다 | VI, VII |
| `s08_batch_register` | 일괄 등록 엑셀을 실제로 올려 검증 결과를 센다 (Register 는 누르지 않는다) | IV |
| `s09_target_formats` | 프로토콜 9종의 Target 형식을 수용/거절한다 | 부록 A |
| `s10_system_error_effect` | Discard / Timeout / Garbage / Disconnect 가 실제 응답을 바꾼다 | VII |
| `s11_simulation_props` | SEQUENCE, 영구 정지, 값·범위·목록 즉시 편집, 누적 초기화 | VI, VIII |
| `s12_device_rules` | 이름 고유, 엔드포인트 충돌, 사용 여부에 따른 소켓 개폐 | III, IV, XI |
| `s13_settings_and_deploy` | Socket Test, Clients List, Realtime Logs, Deploy to... | X, VI |
| `s14_simulation_index` | ServerData 의 target -> Simulation 색인이 최신이고 범위·보조 키로 찾힌다 | III, VI |
| `s15_generic_ppd_pgd` | 설명서 V장의 PPD/PGD(Modbus-RTU FC3) 로 REFERENCE·ITERATION·CRC16 을 검증 | V |
| `s16_serverdata_page` | 편집 세션, Focus / Raw, 값의 출처, Watch (헤드리스 Chrome) | VIII |
| `s17_group_tree` | 그룹 추가·이름 변경·삭제, 장비의 소속 | III |
| `s18_batch_commit` | Register 를 실제로 눌러 커밋까지. 끝나면 지운다 | IV |
| `s19_snapshot_shapes` | 프로토콜 8종의 ServerData 스냅샷 payload 구조와 값 | VIII |
| `s20_error_editor_ui` | Frequency 잔여 한도, Interval 숫자 입력칸, DB 장비의 Simulation stop | VI |
| `s22_lock_authority` | 잠금이 건 쪽을 기억한다. 사람의 잠금은 프로토콜 제어도 막고, 세션 종료는 만료 없는 잠금만 푼다 | VIII, IX |
| `s23_test_plan` | Test Plan 등록 검증(A16 포함), 실행 시각·같은 시각 동시 변경·lock·마지막 lock 까지 실행, 실패 시 기본 계속/Stop 이면 그 자리 중단, 실행 중 409·Stop, 스케줄과 겹침 방지, 엑셀 검증·등록·내보내기 | — |
| `s24_ethernet_ip` | 태그 이름 + data_type 이 CIP 타입으로 나간다, 예전 형식 이전, 태그 중복 거절, REST·Write Tag 의 타입 대조(0xFF/0x2107), 태그 이름 잠금 | 부록 A |

### 아직 덮지 못한 것

설명서 12장과 부록 A 를 훑어 기능을 나열하고 시나리오에 맞춰 보았다. 남은 것은 아래다.

- **패킷 로그** 기록 — 장비별 `log` 플래그를 켠 뒤 파일이 쌓이는지
- **DB 프로토콜의 실제 SQL 실행** — 외부 DB 를 띄워야 한다. 등록·검증까지만 본다
- **BACnet / EtherNet/IP 의 실물 통신** — 스냅샷과 등록까지 본다. 상대 장비 역할을 해 줄 클라이언트가 따로 필요하다

## 배치 템플릿

`templates/` 의 엑셀 두 개는 일괄 등록 화면에 그대로 올릴 수 있다.

- `batch_devices.xlsx` — 1번 시트 장비 5행, 2번 시트 시뮬레이션 10행
- `batch_simulations.xlsx` — 이미 등록된 장비에 추가할 시뮬레이션 8행
- `batch_commit.xlsx` — `s18` 이 Register 까지 누르는 파일. 이름이 `ZZTMP_` 로 시작하고
  포트도 15900번대라 테스트가 끝나면 지워진다. 오류 행이 없다 (오류가 있으면 Register
  버튼이 잠긴다)

`batch_devices.xlsx` 의 마지막 장비 `ups_snmp_01` 은 **일부러 틀려 있다**
(`snmpVersion: 3`, 지원 범위는 0~1). 전부 통과하는 파일로는 화면의 검증 표시를
확인할 수 없어서 한 행만 남겨 두었다. `s08` 이 이 한 건만 걸리는지 센다.

다시 만들려면:

```bash
python3 tools/testkit/templates/make_templates.py
```

파이썬 엑셀 라이브러리를 설치하지 않으려고, 앱이 화면에서 쓰는 SheetJS 를 빌려
브라우저 안에서 만든다. 읽는 쪽과 쓰는 쪽이 같은 라이브러리라 형식이 어긋나지 않는다.
그래서 이 스크립트도 서버가 떠 있어야 한다.

## 안전 장치

운영 데이터와 섞이면 안 되므로 규칙을 둔다.

- 테스트가 만드는 장비 이름은 반드시 `ZZTMP_` 로 시작한다. `cts.add_device()` 가 강제한다.
- 포트는 15900번대만 쓴다.
- 각 시나리오는 `finally` 에서 자기가 만든 것을 지운다.
- `run.py` 가 끝에 `ZZTMP_` 잔여물을 한 번 더 훑어 지우고, 시작·종료 시점의 장비 수를
  비교해 다르면 경고하고 실패로 처리한다.
- 기존 장비와 시뮬레이션은 읽기만 한다. `s08` 은 Register 를 누르지 않는다.
- `s18` 은 Register 를 누르지만, 올리는 파일이 `ZZTMP_` 장비 2대뿐이고 끝에서 지운다.
  시작 전에 같은 이름이 남아 있으면 먼저 지워 중복 검증에 걸리지 않게 한다.

## 알아 둘 것

**타이밍에 기댄다.** 주기 작업이 몇 번 돌기를 기다렸다가 값을 비교하므로, 서버가 많이
바쁘면 간헐적으로 어긋날 수 있다. 실패가 나오면 그 시나리오만 단독으로 다시 돌려
재현되는지 먼저 확인한다.

```bash
python3 tools/testkit/run.py s07
```

**`s07` 은 시뮬레이션 주기를 5초로 둔다.** 핸들러가 제어 값을 쓴 다음에 잠그기 때문에
두 동작 사이가 완전히 붙어 있지는 않다. ServerData 색인이 들어오면서 그 사이의 DB 왕복은
사라졌지만(1초 주기로 6회 돌려 모두 정상), 두 단계인 것은 그대로다. 이 시나리오에서 보려는
것은 `stopOnControl` 이지 그 경합이 아니라서 주기를 떼어 놓았다.
