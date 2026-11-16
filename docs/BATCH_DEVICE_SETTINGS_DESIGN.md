# Batch Add Devices — 통신 설정 편집 설계

> 상태: **구현 (2026-11-06, 미커밋)** — §7 의 1~5단계를 마쳤다. 확인 결과는 §7.
>
> 원칙: **장비 편집기가 늘 기준이다**(2026-11-06 사용자 결정). 일괄 등록 화면과 서버 검사는 편집기의 칸·규칙·문구를
> 그대로 따른다. 편집기와 다른 규칙이 필요해 보이면 편집기를 먼저 고치고 나머지가 따라간다.

---

## 0. 요약

- 일괄 등록 상세 폼은 통신 설정을 **JSON TextArea 하나**로 고친다. 장비 편집기에 있는 프로토콜별 칸,
  패킷 기술자(PPD/PGD), 칸 이름으로 된 오류 문구가 없다.
- 프로토콜별 설정 칸은 **상세 폼 안에** 편집기와 같은 칸으로 둔다.
- PPD/PGD 는 상세 칸이 좁아서(화면의 약 1/3) 편집기의 카드 두 장을 **모달**로 연다(결정: 2026-11-06).
  상세 폼에는 개수·짝 경고·첫 문제와 Edit packets 버튼만 둔다.
- 칸 정의·패킷 편집기·검사 문구를 편집기에서 꺼내 **두 화면이 같은 코드**를 쓴다. 복사하지 않는다.
- **서버 검사도 편집기 규칙으로 바꾼다**(§5.2). 지금은 서버가 더 느슨하거나(소수, RCU 채널 수, 패킷 내용) 더 엄격하다
  (Registers 상한). 같은 사례 표를 JUnit 과 JXA 가 함께 돌려 화면과 서버가 같은 답을 내는지 묶어 둔다.
- 정할 것 2건이 남았다(§6).

---

## 1. 현황

### 1.1 지금 상세 폼

Name · Initial state · Protocol · Transport · Host · Port 는 Tabler 전환 때 편집기와 맞췄다.
그 아래 `Communication settings (JSON)` TextArea 가 엑셀 `communicationSettings` 열을 그대로 보여 준다
(`batchAddDevices.html` renderDetail).

### 1.2 편집기와 다른 점

| 항목 | 장비 편집기 | 일괄 등록 |
|---|---|---|
| 프로토콜별 설정 | 이름 붙은 칸 (`SETTINGS` 표) | JSON 직접 |
| 고르는 칸 | Packet type (Modbus-RTU/TCP), SNMP Version (v1/v2) | `0`/`1` 숫자를 직접 |
| MK RCU 채널 수 | 슬라이더 — DI·DO 1–32, AI 1–8 | 숫자 0–2147483647 |
| DB Password | password 칸 | JSON 안에 평문 |
| 프로토콜 안내 | 설정 카드 맨 위 한 줄 (`Modbus slave. Registers sets…`) | 없음 |
| PPD/PGD | 표 편집기, 짝 표시, Acceptance test | JSON 배열 |
| 오류 문구 | `Unit ID must be a whole number from 0 to 255.` | `communicationSettings: 'unitId' must be 0~255.` |
| 숫자 검사 | 정수만 | 숫자면 통과 (`1.5` 도) |
| 패킷 검사 | 빈 ID·빈 Field ID·Required value 숫자·ITERATION Count·STATIC 등의 Value | 배열이 비어 있지 않은지만 |

### 1.3 이 설계에 넣지 않는 차이

통신 설정이 아니라 이번 범위 밖이다. 적어만 둔다.

- **Packet log** — 엑셀 템플릿에 `log` 열이 없다(`devices.html` generateTemplate 가 뺀다).
- **Connection 아래 안내** — 엔드포인트 상태 줄, 기본 포트 안내(`Default 502`). 일괄 등록은 겹침을 행 오류로 알린다.
- **BACnet Host·Port 고정** — 편집기는 `0.0.0.0:47808` 읽기 전용으로 채운다.

---

## 2. 공용 코드 — 편집기에서 꺼낸다

칸 정의(`PROTOCOLS`·`SETTINGS`)와 패킷 편집기(약 300줄)가 지금은 `device_editor.html` 안에만 있다.
일괄 등록에 복사하면 두 벌이 갈라진다. 그래서 꺼내서 두 화면이 같이 쓴다.

| 새 파일 | 담는 것 | 지금 자리 (device_editor.html) |
|---|---|---|
| `static/js/cts-device-form.js` (`CtsDeviceForm`) | `PROTOCOLS`, `PROTOCOL_ORDER`, `SETTINGS`, 설정 검사, 칸 채우기·읽기 | 125–201, 676–715 |
| `static/js/cts-packets.js` (`CtsPackets`) | 패킷 상태 변환·검사(DOM 없음), 표 편집기, Acceptance test | 376–664, 717–770 |
| `static/css/cts-device-form.css` | `.pk-*`, 설정 칸의 컨테이너 쿼리(`.dv-cq`) | `<style>` 77–113 |
| `templates/fragments/device-form.html` | `settings` 조각(프로토콜별 칸 마크업), `packet-cards` 조각(PPD·PGD 카드) | 1005–1200 |

- 검사·변환은 DOM 을 건드리지 않게 나눈다. JXA 로 실제 데이터를 돌려 확인할 수 있다.
- `SETTINGS` 의 슬라이더 칸(RCU DI/DO/AI)에 `label`·`min`·`max` 를 더한다. 지금은 범위가 마크업에만 있어
  DOM 없는 검사가 범위를 모른다.
- PPD/PGD 카드 머리의 `onclick="addDescriptor('rx')"` 는 `data-act` 로 바꿔 `CtsPackets` 가 받는다.
- 편집기는 꺼낸 것을 쓰도록 바뀔 뿐, **동작·문구·모양이 그대로여야 한다**(1단계 확인 대상).
- 일괄 등록의 `PROTOCOL_TEXT`·`transportsOf` 는 `CtsDeviceForm.PROTOCOLS` 로 바꾼다.

### 2.1 DOM 없는 함수

```js
CtsDeviceForm.settingsProblems(protocol, cs)  // → [{ key, id, message }]  편집기 readSettings 의 규칙·문구
CtsPackets.fromSettings(cs)                   // → { rx, tx, unreadable }  rx/tx 가 JSON 글자면 풀고, 못 읽으면 unreadable 에 적는다
CtsPackets.toSettings(state)                  // → { rx, tx }  편집기가 저장하는 모양. 검사하지 않는다
CtsPackets.problems(state)                    // → [{ message, k, i, j, f }]  편집기 readPackets 의 규칙·문구
```

- 편집기 저장: 첫 문제가 있으면 지금처럼 alert 를 띄우고 그 칸으로 데려간다. 없으면 `toSettings` 결과를 저장한다.
- 일괄 등록: 입력할 때마다 같은 함수로 행 오류를 만든다.

---

## 3. 상세 폼

### 3.1 배치

```
┌ Device detail ─────────────────────────────┐
│ ⚠ Unit ID must be a whole number from 0…   │  ← 지금 그대로 (첫 오류 한 줄)
│ Name           [new-Modbus-1             ] │
│ Initial state  [Enabled ▾]                 │
│ Protocol [Modbus ▾]     Transport [TCP ▾]  │
│ Host [0.0.0.0       ]   Port [11502]       │
│ ── MODBUS SETTINGS ─────────────────────── │  ← .detail-section
│ Modbus slave. Registers sets the address…  │  ← 프로토콜 안내
│ Unit ID [1x  ]!  Packet type [Modbus-TCP▾] │  ← settings 조각
│ Registers [30000                         ] │
└────────────────────────────────────────────┘
```

- Connection 아래에 소제목 `<Protocol> settings`(편집기 카드 제목과 같은 글)와 안내 한 줄을 두고,
  그 아래에 `settings` 조각을 넣는다. 소제목 모양은 batchAddSimulations 의 Policy 와 같은 `.detail-section` 이다.
- 상세 폼은 지금처럼 고를 때마다 HTML 을 새로 만들지 않는다. 조각을 넣은 **고정 마크업**에 값만 채운다.
  행을 바꾸면 모든 칸을 먼저 비운다. 편집기의 fillSettings 는 값이 없으면 건너뛰므로, 비우지 않으면 앞 행의 값이 남는다.
- **좁은 칸**: 상세 칸은 가로 화면에서 약 1/3이다(그리드 1 : 1.2 : 1.4). RCU 줄은 `col-sm-3`(화면 폭 기준)이라
  좁은 칸에도 네 개가 한 줄로 끼인다. 카드 폭 기준(컨테이너 쿼리)으로 바꿔 좁으면 2×2, 넓으면 한 줄로 둔다.
  편집기 모양은 그대로다. 실제 폭은 구현 때 찍어서 확인한다.

### 3.2 파일 값 보여 주기

| 경우 | 보여 주기 | 오류 |
|---|---|---|
| 글자·숫자 칸 | 파일 값 그대로 | 편집기 규칙·문구 |
| 고르는 칸에 없는 값 (packetType `2`) | 그 값을 맨 앞 선택지로 남긴다 — 지금 Protocol 칸과 같은 방식 | `Packet type must be Modbus-RTU or Modbus-TCP.` (새 문구) |
| 고르는 칸의 키가 없음 | 빈 선택지 `Select…` | `Packet type cannot be empty.` |
| 슬라이더 범위 밖 (diCount `50`) | 슬라이더는 끝에 두고, 옆 값 표시는 `50` 을 빨갛게 | `DI channels must be a whole number from 1 to 32.` |
| 슬라이더의 키가 없음 | 값 표시 `—`. 움직이면 채워진다 | `DI channels cannot be empty.` |
| `communicationSettings` 가 JSON 객체가 아님 | **이때만** 지금의 TextArea. 객체가 되면 칸으로 바뀐다 | `Communication settings must be a JSON object.` |
| 다른 프로토콜의 키·여분 키 | 보이지 않는다. 고치는 동안은 남겨 둔다. Register 때 뺀다(§5.3) | 없음 |

- 입력은 지금처럼 `d.communicationSettings` 의 그 키에 바로 쓰고 다시 검사한다.
- 오류는 맨 위 알림 한 줄(지금 그대로)로 보이고, 문제 칸에는 `is-invalid` 를 건다.

### 3.3 패킷 요약 (Generic · MK RCU)

```
│ ── PACKETS ─────────────────────────────── │
│ Packet parser      2 PPDs · 5 fields       │
│ Packet generator   1 PGD · 2 fields        │
│ ⚠ PPD 1 “status”: No PGD with this ID — no reply │  ← 짝 경고 (오류 아님)
│ ✕ Field ID of PGD-0 Field-1 is empty.      │  ← 첫 문제 (오류)
│                         [✎ Edit packets]   │
```

- 개수 문구는 편집기 카드 머리(`#ppd-count`)와 같다.
- 짝 경고는 편집기 `.pk-pair` 와 판정이 같고, 경고일 뿐이다. 편집기도 짝이 없다고 저장을 막지 않는다.
- 버튼은 `btn btn-outline-secondary` 아이콘+글자다(fragments/tabler.html 의 "그 밖").

---

## 4. 패킷 모달

### 4.1 모양

```
┌ Packets · new-generic   Generic ──────────────────────── × ┐
│ ✕ 2 problems · ID of PGD-0 is empty.                        │  ← 없으면 숨김
│ ┌ Packet parser  PPD · requests the device receives ──────┐ │
│ │                              1 PPD · 1 field   [+ PPD]  │ │
│ │ PPD 0 [req     ] [Length | 1]  Answered by PGD 0     [×] │ │
│ │  #  Field ID       Data type             Required value  │ │
│ │  0  [1         ]   [UI8              ▾]  [1          ] × │ │
│ │ [+ Field] [Acceptance test — paste a packet in hex     ] │ │
│ └──────────────────────────────────────────────────────────┘ │
│ ┌ Packet generator  PGD · replies the device sends ───────┐ │
│ │ …                                                        │ │
│ └──────────────────────────────────────────────────────────┘ │
│                                           [Cancel] [Apply]  │
└──────────────────────────────────────────────────────────────┘
```

- `modal-dialog modal-xl modal-dialog-scrollable` 을 쓴다(testplans.html 이 modal-xl 을 쓴다). 1140px 이라 PGD 표의
  최소 폭 40rem 이 들어간다. 두 카드는 편집기 오른쪽 칸처럼 위아래로 둔다. 좁은 창에서는 표가 `.pk-scroll`
  안에서 가로로 넘어간다(편집기와 같다).
- 카드 두 장은 `packet-cards` 조각 그대로라 편집기와 마크업이 같다.
- 제목은 `Packets · <device name>` 이고, 프로토콜 이름을 부제로 단다.
- 맨 위 문제 줄은 입력마다 갱신한다.
- 버튼: Cancel `btn btn-outline-secondary`, Apply `btn btn-primary`(모달의 확정 하나).

### 4.2 동작

- 열 때 그 행(`_rid`)의 rx/tx 를 **복사본**으로 불러온다. 상세 폼처럼 연 시점의 행에 묶는다.
- **Apply**: 복사본을 `toSettings` 로 그 행에 쓰고 다시 검사한 뒤 닫는다. 문제가 있어도 적용한다 — 미정(§6-D).
- **Cancel · × · Esc**: 바뀐 게 있으면 `Discard changes to packets?` 를 묻는다. 배경을 눌러서는 닫히지 않는다
  (`data-bs-backdrop="static"`).
- Acceptance test 입력은 저장하지 않는다(편집기와 같다).
- **비어 있을 때**(다른 프로토콜에서 Generic 으로 바꾼 경우 등): 편집기 새 장비처럼 빈 PPD·PGD 하나씩으로 연다.
- **파일의 rx/tx 를 못 읽었을 때**(배열이 아닌 글자 등): 그쪽을 빈 기술자 하나로 열고 맨 위에
  `The file's rx could not be read. Apply replaces it.` 를 띄운다.
- Data type 이 패킷 타입(UI/SI/FP)이 아니거나 Field type 이 목록에 없으면, 그 값을 선택지로 남기고 문제로 알린다
  (§3.2 고르는 칸과 같은 규칙). 공용 코드라 편집기에도 같이 들어가지만, 저장된 데이터가 정상이면 차이가 없다.

---

## 5. 검증과 등록

### 5.1 화면 검사

- batchAddDevices 의 `validateCommSettings` 를 `settingsProblems` + `CtsPackets.problems` 로 바꾼다.
  행 오류 문구가 JSON 키에서 편집기의 칸 이름 문구로 바뀐다.
- Packet type·SNMP Version 은 편집기에서는 고르는 칸이라 값이 틀릴 수 없다. 일괄 등록에는 파일 값이 들어오므로
  선택지 안에 있는지 검사한다(§3.2). 규칙은 편집기 선택지 그대로(`0`/`1`)다.

### 5.2 서버 검사 — 편집기 규칙으로

지금 서버(`ApiController.validateCommSettings`, batchValidate·batchInsert 가 쓴다)와 편집기를 칸별로 대조한 결과:

| 칸 | 편집기 | 서버 지금 | 바꿀 쪽 |
|---|---|---|---|
| 숫자 칸 전부 | 정수만 | 숫자면 통과 (`1.5`) | 서버 → 정수만 |
| MK RCU DI·DO channels | 1–32 (슬라이더) | 0–2147483647 | 서버 → 1–32 |
| MK RCU AI channels | 1–8 (슬라이더) | 0–2147483647 | 서버 → 1–8 |
| PPD/PGD 내용 | 빈 ID·빈 Field ID·Required value 숫자·ITERATION Count·Value·Length 0 이상 | 배열이 비어 있지 않은지만 | 서버 → 편집기 규칙. Data type·Field type 이 목록 안인지도(§4.2) |
| Registers(Modbus·XGT)·Tags(OPC)·assembly size(EIP) | 하한만 | 상한 2147483647 | **편집기 → 상한 추가** |
| IPv4 (EIP T→O address) | 칸마다 숫자 1–3자리 | 자릿수 제한 없음 (`0001`) | 서버 → 편집기 규칙 |
| 나머지 (범위·빈 값) | — | 같다 | — |

- 마지막 줄의 상한은 서버 쪽이 맞다. 편집기에 상한이 없어 큰 값을 그냥 저장하는 것이므로, **편집기 규칙에 상한을 더하고**
  서버는 그것을 따른다. 편집기 문구가 `… of 1 or more.` 에서 `… from 1 to 2147483647.` 로 바뀐다.
- **서버 문구도 편집기 문구와 같게** 한다(`Unit ID must be a whole number from 0 to 255.`). 화면을 거치지 않은 요청에서만
  보이는 문구지만, 어디서 보든 같은 말이어야 한다.
- **같은 규칙이 JS·Java 두 곳에 있게 된다.** 갈라지지 않게 사례 표 한 장(`src/test/resources/device-settings-cases.json`:
  프로토콜·설정 → 기대 문구)을 두고, JUnit 은 서버 검사로, JXA 는 `CtsDeviceForm`·`CtsPackets` 로 같은 표를 돌린다.
  둘 다 통과해야 한다.
- batchAddDevices 와 ApiController 의 "규칙은 서로 같다" 주석은 "규칙은 device_editor 가 기준" 으로 고친다.

### 5.3 Register 로 보내는 모양

편집기가 저장하는 모양과 같게 보낸다.

- 고른 프로토콜의 `SETTINGS` 키와, Generic·MK RCU 면 `rx`·`tx` 만 보낸다. 파일에만 있던 여분 키는 빠진다.
- 값 형식도 편집기와 같다: 글자·숫자 칸과 고르는 칸은 글자, 슬라이더는 정수, rx/tx 는 `toSettings` 모양.

---

## 6. 정할 것

| | 질문 | 결정·권장 |
|---|---|---|
| A | 패킷 편집을 어디서 할지 | **모달 — 결정됨 (2026-11-06)** |
| B | MK RCU 채널 수 범위 | **편집기 기준 — 결정됨 (2026-11-06)**. 화면·서버 모두 DI·DO 1–32, AI 1–8(§5.2) |
| C | Register 때 보내는 모양 | **편집기 기준 — 결정됨 (2026-11-06)**. 고른 프로토콜의 키만, 편집기 값 형식(§5.3) |
| D | 문제가 남아 있어도 모달의 Apply 를 허용할까? | **허용 — 권장안대로 진행 (2026-11-06)**. 편집기에서 Save 에 해당하는 것은 모달의 Apply 가 아니라 Register 이고, Register 는 편집기 Save 처럼 막는다. Apply 를 막으면 다 고칠 때까지 모달에서 못 나온다 |
| E | 서버의 단건 저장(`/device/addDevice`·`updateDevice`)은 지금 통신 설정을 **전혀 검사하지 않는다.** 같은 서버 검사를 여기에도 걸까? | **건다 — 권장안대로 진행 (2026-11-06)**. 막히면 400 과 편집기 문구를 돌려준다. 편집기가 같은 규칙으로 먼저 막으므로 편집기 사용자에게는 달라지는 게 없다. 화면을 거치지 않은 요청만 막힌다 |

---

## 7. 단계와 확인

시작 전에 백업한다(`MK119CTS_backups/batch-settings-before-<날짜>.tar.gz`).

| 단계 | 내용 | 확인 |
|---|---|---|
| 1 | 공용 코드 꺼내기 — 편집기만 바뀐다 | 편집기 회귀: 프로토콜 9종 새로 만들기·고치기의 저장 payload 를 전송을 가로채 전후 비교. 패킷 조작·Acceptance test·저장 전 검사 문구(지난번 CDP 스크립트 재사용). 가로·세로 화면 사진 |
| 2 | 상세 폼 설정 칸 + 검증 교체 | 등록 장비 8대를 이름만 바꿔 sessionStorage 로 심고, 프로토콜마다 정상·빈 값·범위 밖·선택지 밖·JSON 아님을 넣어 본다. `settingsProblems` 는 JXA 로 실제 데이터에 돌린다 |
| 3 | 패킷 요약 + 모달 | 열기·고치기·Apply·Cancel(바뀐 게 있을 때 묻는지)·문제 줄·rx 가 JSON 글자일 때·못 읽는 rx·프로토콜을 바꿔 비었을 때 |
| 4 | 등록 | Register payload 를 가로채 §5.3 모양인지 본다. 실제로 1건 등록해 편집기로 열어 같은지 보고, 확인 뒤 지운다 |
| 5 | 서버 검사를 편집기 규칙으로(§5.2), E 가 정해지면 단건 저장에도 | 사례 표를 JUnit·JXA 가 같이 돌려 둘 다 통과. 화면 검사를 끄고 batchValidate 에 직접 보내 같은 문구가 오는지 |

설명서 그림 `cts_manual_assets/fig_batch_devices.png` 은 바뀐 화면으로 다시 찍어야 한다(별도 작업).

---

## 8. 건드리는 파일

- `templates/device_editor.html` — 꺼낸 것을 쓰도록 바꾼다(마크업·스크립트가 줄어든다).
- `templates/batchAddDevices.html` — 상세 폼, 검증, 모달.
- 새 파일 4개(§2) + `db/service/DeviceSettingsValidator.java`(서버 쪽 같은 규칙).
- `web/ApiController.java` — `validateCommSettings` 를 편집기 규칙·문구로(§5.2). E 를 하면 addDevice·updateDevice 에도 건다.
- 테스트: `src/test/resources/device-settings-cases.json`(31건), `DeviceSettingsValidatorTest`(JUnit), `tools/check-device-settings.sh`(JXA).

---

## 9. 구현 결과 (2026-11-06)

| 단계 | 확인 | 결과 |
|---|---|---|
| 1 | 편집기 회귀 77건(장비 8대 고치기 저장, 프로토콜 9종 새로 만들기, 잘못된 입력 alert, 패킷 조작·Acceptance test·저장 전 검사)을 전후 비교 | 76건 같음. 다른 1건은 §5.2 의 예정된 문구 변경(`Registers must be a whole number from 1 to 2147483647.`) |
| 2 | 등록 장비 8대 + 잘못 만든 6행을 심어 상세 폼 확인 | 8대 Valid. 선택지 밖 값은 맨 앞 선택지로, 범위 밖 슬라이더는 빨간 값으로, JSON 아님은 TextArea 로 보인다 |
| 3 | 패킷 모달: 열기·고치기·Apply·Cancel(바뀌면 확인)·Esc·못 읽은 rx·빈 설정 | 설계대로 |
| 4 | Register 요청을 가로채 모양 확인, 실제로 2건(Modbus·Generic) 등록 후 편집기로 열어 저장 모양 비교, 확인 뒤 삭제 | 여분 키 빠짐. 편집기 저장과 같다(Generic 은 DB 가 `1`→`1.0`, null 키 생략하는 것만 다르고, 편집기로 만든 장비도 같다) |
| 5 | 사례 표 31건을 JUnit·JXA 가 같이 | 둘 다 31/31. batchValidate·addDevice·updateDevice 에 직접 보내도 같은 문구 |
