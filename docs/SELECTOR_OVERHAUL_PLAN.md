# Selector 전면 개편 — 현황과 계획

> 상태: **계획 (2026-11-05)**. 1단계 토대(`cts-select.js`·자산·`cts-select.css`)와 Protocol Definition 편집기 적용은
> 2026-11-11 구현 — 다른 화면(2–6단계)은 아직이다.
>
> **검색칸 기준: 선택지가 5개를 넘을 때(6개 이상)** — 2026-11-11 사용자 확인. 아래 표의 ●/○ 는 처음 계획의
> "5개 이상" 으로 적었으니 5개짜리 칸(error_editor Error mode 등)은 검색 없음으로 읽는다. 기준은 `CtsSelect.SEARCH_OVER` 하나다.

---

## 0. 요약

- 지금 화면의 "열리는 목록"은 **다섯 가지 모양**이 섞여 있다(§1.1). 대부분은 OS 가 그리는 네이티브 `<select>` 팝업이다.
- **검색 셀렉터가 빠진 원인은 Tabler 와의 호환성이 아니었다.** Tabler 의 `tabler-vendors.css` 에는 Tom Select
  **덮어쓰기 규칙만** 있고, Tom Select **기본 CSS** 가 프로젝트에 없었다. 그래서 드롭다운이 떠 있지 못하고 문서
  흐름에 끼어 칸 폭에 갇혔다("좁은 칸에서 글자가 세로로 쪼개짐"). 기본 CSS 한 장을 더하면 풀린다 — 재현·확인함(§1.2).
- 해법: **Tom Select(동작) + Tabler vendors(모양)** 하나로 통일한다. Tabler 가 공식으로 테마를 제공하는 조합이다.
  선택지 **5개 이상이면 검색칸**을 드롭다운 안에 둔다. 표 칸(Tabulator) 편집기로도 동작함을 확인했다(§1.3).
- 가장 시급한 자리: **Error 편집기의 Value simulation**(Modbus-1 은 5,851개를 네이티브 select 로 펼친다),
  **Test Plan 편집기의 Device**(Tabulator 기본 목록, Tabler 와 모양이 다르고 검색칸이라는 표시가 없다).

---

## 1. 현황

### 1.1 열리는 목록의 다섯 가지 모양

| | 종류 | 누가 그리나 | 어디 |
|---|---|---|---|
| A | 네이티브 `<select>` | OS·브라우저. Tabler 는 닫힌 칸만 꾸민다 | 거의 전부 (§1.4) |
| B | 네이티브 `<datalist>` | 브라우저 자동완성 팝업 | SNMP Base OID (`cts-target-aid.js` 의 combo) |
| C | Tabulator `list` 편집기 | Tabulator BS5 테마 — 각진 모서리, 진한 파랑, 다른 글꼴 | Test Plan Device·Type, batchAddDevices Policy, batchAddTestPlans Stop |
| D | 자체 팝오버 `.ta-pop` | Tabler 변수로 직접 그림 | Test Plan Target |
| E | 하드코딩 select | `.sd-viewtabs-select` 가 `#fff` 고정 — 다크 모드에서 흰 칸 | Server Data Row size |

Tom Select 토대(`cts-select.js`, `plugins/tom-select/js`, `tabler-vendors.min.css`, 조각 `select-assets`/`select-scripts`)는
깔려 있지만 **쓰는 화면이 하나도 없다.**

### 1.2 검색 셀렉터가 빠진 진짜 원인

`tabler-vendors.min.css`(Tabler 1.7.3) 의 `.ts-*` 규칙은 색·테두리·그림자 같은 덮어쓰기뿐이다.
`.ts-dropdown{position:absolute}`, `.ts-dropdown-content{max-height;overflow}`, `.ts-hidden-accessible`(원래 select 숨김)
같은 **골격은 Tom Select 의 `tom-select.bootstrap5.css`** 에 있고, 이 파일이 없었다.

최소 재현(같은 Tabler·Tom Select 파일, 110px 칸):

| 경우 | 결과 |
|---|---|
| 기본 CSS 없음 (지난번 상태) | 드롭다운이 문서 흐름에 끼어 아래 내용을 밀어내고, 칸 폭에 갇힌다 |
| 기본 CSS 추가 | 드롭다운이 떠서 정상. 단, 검색 입력이 컨트롤 안에 들어가 **좁은 칸에선 두 줄로 늘어난다** |
| 기본 CSS + `dropdown_input` 플러그인 | 컨트롤은 한 줄 유지, 검색칸은 드롭다운 맨 위. **채택** |
| 다크 모드 | Tabler 덮어쓰기가 그대로 입혀진다 (추가 작업 없음) |

### 1.3 확인한 것 (헤드리스 Chrome·CDP)

- **표 칸 안**(overflow:hidden): `dropdownParent:'body'` 로 칸 밖으로 빠져나온다. 내용이 길면 칸보다 넓게 열린다.
- **Tabulator 칸 편집기**: 실제 Tabulator 6.3.1 에 Tom Select 편집기를 붙여 시험했다 —
  클릭으로 열림 → 바로 타이핑하면 검색 → ↑↓·Enter 확정(`cellEdited` 발생) / Esc 취소 / 바깥 클릭 취소 / 마우스 선택 확정.
  끝난 뒤 남는 드롭다운 없음. 같은 값을 고르면 취소로 처리된다(바뀐 것 없음).
- **묶음 + 여러 낱말 검색**: "bac line1" → 그룹 line1 의 BACnet 장비만. 낱말은 모두 맞아야 한다(AND).
- **성능**: 6,000개 — 초기화 16ms, 열기 27ms, 거르기 34ms. `maxOptions:100` 이면 거르기 2ms.
- **이벤트**: 고르면 원래 `<select>` 에 `input`·`change` 를 둘 다 울린다 → 기존 `onchange`/`oninput`/jQuery 핸들러는 그대로 동작한다.

### 1.4 셀렉터 전수표

검색 열: ● 5개 이상 → 검색칸, ○ 5개 미만 → 검색 없음, ◐ 개수가 바뀜(그때마다 판정).

**Tabler 화면**

| 화면 | 칸 | 선택지 | 지금 | 검색 |
|---|---|---|---|---|
| devices · simulations · errors · testplans | 툴바 검색 열 (`*SearchField`) | 2–3 | A | ○ |
| settings | Socket·Logs 검색 열 | 3 | A | ○ |
| device_editor | Initial state · Packet log · Modbus packet · SNMP version | 2 | A | ○ |
| | Protocol | 9 | A | ● |
| | Transport | 1–2 | A | ○ |
| | PPD/PGD Field type (표 안) | 6 | A | ● |
| | PPD/PGD Data type (표 안) | 26 | A | ● |
| simulation_editor | Policy · Initial state | 4 · 2 | A | ○ |
| | Datatype 선택기(Type·Sign·Bit length·Byte order) | ≤4 | A | ○ |
| | Target 조립: Function code · BACnet Object/Kind · XGT Size · RCU Source | 3–4 | A | ○ |
| | Target 조립: XGT Block | 6 | A | ● |
| | Target 조립: RCU Channel · RCU/Generic TX field | ≤32 · N | A | ◐ |
| | Target 조립: SNMP Base OID (자유 입력 + 제안) | 2+ | B | ◐ |
| error_editor | Error mode | 5 | A | ● |
| | Initial state | 2 | A | ○ |
| | **Value simulation** | **N (Modbus-1: 5,851)** | A | ● |
| testplan_editor | Schedule · Schedule type | 3 | A | ○ |
| | **Device** (표 칸) | N | C | ● |
| | Type (표 칸) | 1–26 | C | ◐ |
| | Target (표 칸) | 팝오버 | D | 이미 Filter 있음 |
| serverdata | Row size | 4 | E | ○ |
| | Watch format (모달) | 8 | A | ● |
| | Watch data type · Edit data type (모달) | ≤29 | A | ◐ |

**일괄 등록 화면** — 2026-11-05 Tabler 로 옮겼다(네이티브 `form-select`). 이 개편 6단계에서 다른 화면과 같이 붙인다.

| 화면 | 칸 | 선택지 | 지금 |
|---|---|---|---|
| batchAddDevices | Initial state · Protocol · Transport · Policy(표 칸) | 2 · 9 · 1–2 · 4 | A · A · A · C |
| batchAddSimulations | Initial state · Policy · Datatype 선택기(Type·Sign·Bit length·Byte order) · Target 조립 | 2 · 4 · ≤4 · N | A |
| batchAddErrors | Error mode · Initial state · Value simulation | 5 · 2 · N | A |
| batchAddTestPlans | Stop (표 칸) | 3 | C |

옛 AdminLTE 템플릿은 2026-11-05 지웠고, 편집기 이름의 구분 숫자(device_editor3 등)도 뗐다.

---

## 2. 설계

### 2.1 원칙

1. **열리는 목록은 하나** — `CtsSelect`(Tom Select + Tabler 테마). 네이티브 select 팝업·datalist·Tabulator `list` 편집기는 쓰지 않는다.
2. **검색칸은 선택지 5개 이상일 때만**, 위치는 항상 드롭다운 맨 위(`dropdown_input`). 컨트롤은 늘 한 줄이다.
   목록이 바뀌면(`refresh`) 다시 센다. 검색칸을 숨기는 건 클래스 하나로 한다 — 다시 붙이지 않는다.
3. **드롭다운은 잘리지 않는 곳에 붙인다** — 기본은 `body`. 단 포커스를 가두는 그릇 안에서는 그 그릇에 붙인다:
   Bootstrap 모달(`.modal` — Bootstrap 5 모달의 focus trap 이 body 에 붙은 검색칸의 포커스를 빼앗는 것으로 알려져 있다.
   1단계에서 확인), Test Plan Target 팝오버(`.ta-pop` —
   포커스가 팝오버 밖으로 나가면 편집을 끝내 버린다). 규칙: `closest('.modal, [data-ts-parent]') || body`.
4. **폭**: 최소는 컨트롤 폭, 내용이 길면 그만큼(최대 `min(480px, 100vw - 16px)`).
5. **긴 목록**: 한 번에 200개까지 그리고, 넘으면 맨 아래에 `Showing 200 of 5,851 — type to narrow` 를 둔다.
6. **키보드**: 열린 채 바로 타이핑 = 검색, ↑↓, Enter 확정, Esc 취소, Tab 다음 칸. 표 칸 편집기도 같다.
7. **항목은 값만이 아니라 알아볼 정보를 함께** — 장비는 이름 + 프로토콜 + 주소, Simulation 은 target + 이름 + data type.
   Target 팝오버의 행 모양과 맞춘다.

### 2.2 공용 모듈 — `cts-select.js` 개편

| API | 하는 일 |
|---|---|
| `mount(el, opts)` | 원칙 2–5 를 기본값으로. `form-select-sm` 크기를 이어받는다. `data-native` 가 붙은 select 는 건너뛴다 |
| `mountAll(root)` | `root` 안의 `select.form-select` 를 모두 붙인다 — HTML 을 다시 그린 뒤 부른다 |
| `destroyAll(root)` | 다시 그리기 **전에** 부른다. 드롭다운이 body 에 붙어 있어 안 부르면 고아로 남는다 |
| `refresh(el)` | `<option>` 을 갈아 끼운 뒤. 검색칸 기준도 다시 센다 |
| `val(el[, v])` · `disable(el, bool)` | 값·사용 여부. `$('#x').val(v)`·`.prop('disabled')` 대신 |
| `cellEditor(paramsFn)` | Tabulator `editor:` 에 넘기는 편집기. 고르면 `success`, 바뀐 것 없이 닫히면 `cancel` |
| `render.device` · `render.simulation` | 항목 그리기 (§3) |

지금 기본값 `controlInput: '<input>'`(검색을 컨트롤 안에 둠)은 버린다 — 좁은 칸에서 두 줄이 되는 원인이다.

### 2.3 자산

- `plugins/tom-select/css/tom-select.bootstrap5.min.css` 추가 — JS 와 같은 **2.6.2**.
- 순서: `tabler.min.css` → `tom-select.bootstrap5.min.css` → `tabler-vendors.min.css` → `css/cts-select.css`(원칙 4·5, 표 칸 모양, 검색칸 숨김).
- Tabler 화면은 모두 셀렉터가 있으므로 `select-assets`/`select-scripts` 를 공용 `assets`/`scripts` 조각에 합친다.

### 2.4 특수한 자리

- **표 안 select**(device_editor 패킷 표): `pk-scroll` 이 가로로 스크롤한다 → body 에 붙이는 기본 규칙으로 해결된다.
- **숨은 값 select**(simulation_editor `policies-n-datatype`, `d-none`): 값 보관용이라 붙이지 않는다(`data-native`).
- **SNMP Base OID**(datalist): `create:true` 로 목록에 없는 값도 그대로 입력받는다.
- **Target 팝오버**(D): 목록 + 조립 줄이 합쳐진 편집기라 그대로 둔다. 바꾸는 것은 셋 —
  안의 select 를 CtsSelect 로(부모 = 팝오버), 행 강조색을 `.ts-dropdown` 과 같은 토큰(`--tblr-active-bg`)으로,
  목록을 원칙 5 처럼 200개 상한으로(지금은 5,851행을 한 번에 그린다).
- **Row size**(E): `form-select form-select-sm` 으로 바꾸고 하드코딩 색을 지운다.

---

## 3. Test Plan 편집기 — Device 선택

지금: Tabulator `list` + autocomplete. 칸에 있던 장비 이름 자리에 그대로 검색어를 친다(검색칸이라는 표시가 없다).
항목은 `Modbus-1 · MODBUS` 한 줄, 묶음 없음. Enter 처리를 위해 `openDeviceSearch` 가 keydown 을 capture 로 가로챈다.

바꾼 뒤:

```
┌ Device ─────────────┐
│ Modbus-1          ▾ │   ← 칸은 장비 이름 그대로
└─────────────────────┘
┌──────────────────────────────────────────────┐
│ [ Search devices                          ]  │   ← 열리면 커서가 여기
│ ROOT                                         │   ← 그룹 묶음
│   Modbus-1      MODBUS     0.0.0.0:10502     │
│   bacnet_0      BACnet     0.0.0.0:47808     │
│ ROOT / line1                                 │
│   eip           Ethernet/IP  …               │
│ Showing 200 of 1,240 — type to narrow        │
└──────────────────────────────────────────────┘
```

- 검색 대상: 이름, 프로토콜(표시 이름과 enum 둘 다), host:port, 그룹. 여러 낱말은 모두 맞아야 한다.
- 프로토콜 표시 이름(`LSIS-XGT`, `MK RCU` …)은 지금 device_editor(PROTOCOLS) 와 batchAddDevices(PROTOCOL_TEXT) 에 따로 있다 → 공용으로 뺀다.
- `cellEditor` 로 바꾸면 `openDeviceSearch` 는 지운다.
- Type 칸도 같은 `cellEditor` 를 쓴다(1–26개, 5개 이상일 때 검색).

---

## 4. 작업 순서

| 단계 | 내용 | 확인 |
|---|---|---|
| 1 토대 | §2.2 `cts-select.js`, §2.3 자산·조각, `cts-select.css` | 시험 페이지: 좁은 칸·표 칸·모달·다크·5,851개 |
| 2 고정 목록 | 툴바 검색 열 6곳, device_editor 고정 칸, simulation_editor Policy·Initial state, error_editor Error mode·Initial state, testplan_editor Schedule 2곳 | 화면마다 열기·고르기·저장 |
| 3 동적 목록 | device_editor Protocol→Transport·패킷 표, error_editor Value simulation, serverdata 모달 3곳·Row size | 목록 갈아 끼운 뒤 표시값, 모달 안 검색칸 포커스 |
| 4 공용 JS | `cts-datatype-picker.js`, `cts-target-aid.js`(다시 그리기 전 `destroyAll`, 포커스 복원을 Tom Select 컨트롤로) | 조립 줄 조각 바꾸기 반복 후 body 의 `.ts-dropdown` 수 = 열린 것만 |
| 5 Test Plan | Device·Type `cellEditor`, Target 팝오버 정리(§2.4) | §1.3 의 키보드·마우스 시나리오 |
| 6 일괄 등록 4종 | 2–5단계와 같은 방식 (이미 Tabler) | |

**값을 직접 쓰는 곳** — `$('#x').val(v)`·`.empty().append()`·`.prop('disabled')` 로 select 를 건드리는 곳이 Tabler 화면에
11곳(device_editor 3, serverdata 3, error_editor 2, testplan_editor 2, simulation_editor 1) + JS 가 HTML 로 만드는 select 들이다.
화면을 옮길 때 전부 `CtsSelect.val/refresh/disable` 로 바꾼다. 안 바꾸면 값은 바뀌는데 보이는 글자는 옛 값으로 남는다.

**testkit**: `s20_error_editor_ui.py` 가 `$('#errorMode').val('4')` 로 값을 넣는다. 판정은 `displayForms('4')` 를 직접
부르므로 통과하지만 화면 캡처의 표시값이 어긋난다 → `CtsSelect.val` 로 바꾼다.

---

## 5. 결정 필요

1. ~~일괄 등록 4종(AdminLTE·Bootstrap 4)~~ — 2026-11-05 결정: 화면을 먼저 Tabler 로 옮기고(네이티브 select),
   셀렉터는 이 개편 6단계에서 다른 화면과 같이 한다.
