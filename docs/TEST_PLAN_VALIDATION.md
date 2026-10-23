# Test Plan — 검증 범위

> 상태: **확정 (2026-10-22)**, A6·A7·A10 개정 (2026-10-23), EtherNet/IP 반영 (2026-10-23)

---

## 0. 전제

- 검증은 **서버 한 곳**에서 한다. 화면 저장, 엑셀 import, 외부 REST 가 모두 같은 validator 를 거친다.
  (Simulation 의 `SimulationTargetValidator` 와 같은 이유 — 경로마다 넣으면 구멍이 생긴다.)
- 검증 시점은 두 가지다.
  - **등록 시점** (저장 / import) — 실패하면 **거절**한다. 저장되지 않는다.
  - **실행 시점** (Action 마다) — 실패하면 그 Action 의 **skip 정책**을 따른다.
    skip=true 면 기록하고 interval 을 기다린 뒤 다음 Action, skip=false 면 Plan 을 중단한다.
- Plan 의 쓰기는 API_WRITE 이고 API_WRITE 는 최상위 authority 다. 따라서 **lock 에 막히는 실패는 없다.**
- **타입에 맞지 않는 값은 거절한다.** A6·A7·A10 은 `ServerDataValueRules` 하나로 판정하고,
  REST 쓰기(`POST /api/devices/{id}/serverdata`)도 같은 규칙으로 400 을 돌려준다.

---

## 1. 등록 시점 — Plan

| ID | 대상 | 규칙 | 실패 시 |
|---|---|---|---|
| P1 | Name | 필수. 앞뒤 공백 제거 후 1~100자 | 거절 |
| P2 | Name | 기존 Plan 과 중복 불가. 엑셀 한 파일 안의 시트끼리도 중복 불가 | 거절 |
| P3 | Actions | 1~1000개 | 거절 |
| P4 | 실행 중 | 실행 중인 Plan 은 수정·삭제할 수 없다. 먼저 Stop | 거절 |

## 2. 등록 시점 — Schedule

| ID | 대상 | 규칙 | 실패 시 |
|---|---|---|---|
| S1 | Type | `NONE`(수동만) / `SECOND` / `MINUTE` / `HOUR` 중 하나 | 거절 |
| S2 | 필드 값 | SECOND: S(0~59). MINUTE: M(0~59), S(0~59). HOUR: H(0~23), M(0~59), S(0~59). 정수, 값 하나 | 거절 |
| S3 | 주기 | 주기 = SECOND 60초 / MINUTE 3600초 / HOUR 86400초. **interval 총합 < 주기** | 거절 |
| S4 | 재검사 | Action 을 고쳐 interval 총합이 바뀌면 S3 을 다시 본다 | 거절 |

S3 의 실행 시간에는 마지막 Action 의 interval 도 들어간다(마지막 Action 뒤에도 기다린 후 완료).
총합 == 주기면 끝나는 순간 다음 실행과 맞물리므로 **미만(<)** 으로 비교한다.

## 3. 등록 시점 — Action

| ID | 대상 | 규칙 | 실패 시 |
|---|---|---|---|
| A1 | Device | 필수, 존재해야 한다. 엑셀은 이름(전역 UNIQUE)으로 찾는다. 앞뒤 공백 제거, 대소문자 구분 | 거절 |
| A2 | Device 프로토콜 | `DB` 는 ServerData 가 없어 거절 | 거절 |
| A3 | Target | 필수. `SimulationTargetValidator.validate(device, target, dataType)` 로 프로토콜별 형식·주소 범위. XGT target 은 대문자로 바꿔 저장한다. EtherNet/IP target 은 태그 이름이며, 예전 형식(`REAL@TAG1`)은 접두어를 떼고 저장한다 | 거절 |
| A4 | Target (SNMP / GENERIC / BACNET / ETHERNET_IP) | 그 장비에 같은 target(EtherNet/IP 는 같은 태그)의 Simulation 이 등록돼 있어야 한다 (Write API 와 같은 규칙) | 거절 |
| A5 | Target (Modbus) | FC 1/2/3/4 만 쓸 수 있다 | 거절 |
| A6 | DataType | 비어 있거나 `DataType` enum 이름. **Modbus 레지스터(FC3/FC4) target 은 필수** — 없으면 쓰기가 아무것도 하지 않았다. **EtherNet/IP 는 비워 두면 태그의 타입을 쓰고, 적으면 태그와 같은 CIP 타입이어야 한다** (태그 하나는 타입 하나). BOOL·BITS 는 EtherNet/IP 전용 | 거절 |
| A7 | DataType (XGT) | 필수. target 의 크기 문자와 맞아야 한다. B→8비트, W→16비트, D→32비트 | 거절 |
| A8 | DataType (EtherNet/IP) | CIP 타입으로 보낼 수 있어야 한다: SI8→SINT, UI8·SI16→INT, UI16·SI32→DINT, FP32→REAL, BOOL, BITS. UI32·SI64·FP64 는 거절 (A3 의 validator 가 본다) | 거절 |
| A9 | Value | 필수. 유한한 숫자 (NaN / Infinity 거절) | 거절 |
| A10 | Value 와 타입 | 정수형은 **소수 거절**, 범위 밖 거절 (예: UI16 은 0~65535, UI32 는 0~4294967295). dataType 이 없으면 target 이 정하는 타입으로 본다(Daeyun AI 는 SI16). 비트(Modbus FC1/2, Daeyun DI, dataType BOOL)는 0/1 만, BITS 는 0~4294967295. EtherNet/IP 는 태그의 타입으로 본다. 실수형은 보지 않는다 | 거절 |
| A11 | LockSeconds | 비어 있으면 0. 0 이상의 정수. **음수(영구 lock) 거절** — 편집 세션 전용 | 거절 |
| A12 | Interval | 필수. 0 이상의 정수(초) | 거절 |
| A13 | Skip | `TRUE` / `FALSE`. 비어 있으면 FALSE (실패 시 중단) | 거절 |
| A14 | 정수 칸의 소수 | 엑셀 숫자는 실수로 읽힌다. `5.0` 은 받고 `5.5` 는 거절 (LockSeconds, Interval) | 거절 |

## 4. 등록 시점 — 엑셀 파일

| ID | 대상 | 규칙 | 실패 시 |
|---|---|---|---|
| X1 | 파일 | `.xlsx`, 열리지 않으면 거절 | 파일 거절 |
| X2 | 시트 수 | 1개 이상. 시트 1개 = Plan 1개. 상한 50개 | 파일 거절 |
| X3 | 시트 이름 | Plan 이름으로 쓴다 (P1, P2 적용). 엑셀의 시트 이름은 31자가 한계 | 시트 거절 |
| X4 | 헤더 | 1행. 필수 열 `Device`, `Target`, `Value`, `DataType`, `LockSeconds`, `Interval`, `Skip`. 순서 무관, 대소문자 무시. 모르는 열은 경고 후 무시 | 시트 거절 |
| X5 | 빈 행 | 모든 칸이 빈 행은 건너뛴다. 남은 행의 순서가 Action 순서다 | — |
| X6 | 데이터 행 | 데이터 행이 없으면 P3 으로 거절 | 시트 거절 |
| X7 | 커밋 | 오류가 하나라도 있으면 Register 를 잠근다 (기존 일괄 등록 화면과 같다). 전부 통과하면 한 트랜잭션으로 등록 | — |
| X8 | 스케줄 | 엑셀에 넣지 않는다. import 된 Plan 은 `NONE` 으로 등록된다 | — |

X3: 같은 이름의 Plan 이 이미 있으면 거절한다(P2). 덮어쓰기 모드는 두지 않는다.

---

## 5. 실행 시점 — Action (skip 정책 적용)

| ID | 상황 | 판정 | Write API 대응 |
|---|---|---|---|
| R1 | 장비가 삭제됨 | 실패 | — |
| R2 | 장비가 꺼짐 / 서버 미기동 / ServerData 없음 | 실패 | 404 `device_not_available` |
| R3 | SNMP / GENERIC / BACNET target 의 Simulation 이 등록 후 삭제됨 | 실패 | 501 |
| R4 | target 형식·주소 범위 오류 (등록 후 장비 설정이 바뀐 경우 — 예: 레지스터 수 감소) | 실패 | 400 |
| R5 | 쓰기 미지원 | 실패 | 501 |
| R6 | 그 밖의 예외 | 실패 | 500 |

실패해도 그 Action 의 interval 은 그대로 기다린다.

## 6. 실행 시점 — Plan

| ID | 상황 | 처리 |
|---|---|---|
| E1 | 수동 실행했는데 이미 실행 중 | 거절 (409) |
| E2 | 스케줄 시각인데 이미 실행 중 | 이번 실행은 하지 않고 로그만 남긴다 (실패로 세지 않음) |
| E3 | Stop | 진행 중인 쓰기 하나는 끝내고, 대기 중이면 즉시 끝낸다. 이미 건 lock 은 건드리지 않는다 |
| E4 | 앱 재시작 | 실행 중이던 Plan 은 이어서 실행하지 않는다. 스케줄은 다시 등록하고, 꺼져 있던 동안 놓친 실행은 건너뛴다 |

실행 결과:

| 결과 | 조건 |
|---|---|
| `COMPLETED` | 모든 Action 성공 |
| `COMPLETED_WITH_SKIPS` | skip=true 인 Action 이 하나 이상 실패 |
| `FAILED` | skip=false 인 Action 이 실패해 중단 |
| `STOPPED` | 사용자가 Stop |
