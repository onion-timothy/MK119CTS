# Server Data 화면 — 프로토콜별 카드 구성 설계

> 상태: **구현 완료 (2026-11-04). 1·2단계 반영, 3단계는 미착수**
> 범위: `templates/serverdata.html` 의 **Raw 뷰**. Modbus(Focus/Raw 공용 격자)와 XGT(블록 덤프)는
> 완성된 것으로 보고 건드리지 않는다.
> 근거: 2026-11-04 기준 동작 중인 서버(localhost:8082)의 실제 스냅샷과 화면 캡처.

---

## 1. 요청과 결론

| # | 요청 | 결론 |
|---|---|---|
| 1 | Modbus, XGT 는 완성 | 손대지 않는다. 새 레이아웃 엔진은 이 둘을 **우회**한다 |
| 2 | SNMP·EIP·OPC Agent·Generic 에 줄 만한 추가 정보가 있나 | **있다.** 공통 3열(Simulation / Type / Source)과 "등록만 되고 아직 안 쓰인 target" 행, 그리고 프로토콜마다 한 가지씩 (§5.3) |
| 3 | MK RCU(Daeyun) 에 Generic 영역 추가, 가로 5:5 / 세로 1:1:2 | 카드 3개 + 레이아웃 엔진 (§5.1) |
| 4 | BACnet 을 카드 3개 × 표 3개로 | Binary / Analog / Multi-state × Input / Output / Value (§5.2) |

3·4 는 같은 문제를 푼다 — **카드를 비율대로 배치하고 그 안의 표 높이를 정하는 공통 장치**가 없다는 것.
그래서 §3 에 그 장치를 먼저 두고, §5 는 그 장치에 넘길 선언만 적는다.

---

## 2. 지금 상태 (확인한 사실)

### 2.1 스냅샷 payload (실제 응답)

```
BACNET       objects: [{target:"analog_input 0", simulationId, name:"ai0", object:"Analog Input 0", value:43.0}, ...]
SNMP         oids:    {".1.3.6.1.4.1.2":50.0, ".1.3.6.1.4.1.3":20380.0, ...}
ETHERNET_IP  tags:    {"test1":36}
DAEYUN_RCU   analogChannels:[32], digitalChannels:[32], txFields:{"1":100.0,"2":98.0}
OPC_AGENT    values:  [3000개]
GENERIC      fields:  {"1":71.0,"2":75.87317199245115}
```

### 2.2 화면

- **Daeyun**: 카드 2개(AI, DI)가 세로로 쌓이고 표 높이가 `340px` 고정이라 **DI 카드가 화면 밖으로 넘친다**.
  `txFields` 는 스냅샷에 실려 오는데 **그리는 코드가 없다** — 이것이 요청 3 의 "Generic 영역이 표시되지 않는다".
- **BACnet**: 카드 1개가 컨텐츠 높이의 50%(`fitEntryCardHeight`)만 쓰고 아래 절반이 빈다.
  행은 5개뿐인데 2쪽으로 나뉜다.
- **Daeyun AI/DI 표의 머리글이 `Byte addr`** 이다. XGT 의 `xgtColumns()` 를 그대로 가져다 써서 그렇다.
  실제로는 1-base 채널 번호다.
- 값이 한 번도 안 쓰인 target 은 **아예 행이 없다**. 행의 원천이 payload 의 값 맵뿐이기 때문이다.
- `SnmpData`/`GenericData`/`DaeyunData` 의 값 맵이 `HashMap` 이라 스냅샷마다 **행 순서가 바뀐다**.

### 2.3 코드

| 구성요소 | 현재 역할 | 이 설계에서 |
|---|---|---|
| `renderRawLegacy(snap)` | 프로토콜별 renderer 로 분기 | Modbus·XGT 를 뺀 나머지는 `renderCards(snap)` 로 대체 |
| `renderDaeyun` / `renderKeyValue` / `renderBACnet` | 카드·표를 손으로 짓는다 | **삭제**. 선언(§5)으로 대체 |
| `renderXGT` / `renderSquareGrid` | XGT 아코디언 / Modbus 격자 | 그대로 |
| `fitEntryCardHeight(table)` | 표 하나를 컨텐츠의 50% 로 | `sdLayout.apply()` 로 대체 |
| `applyDeltaToRowTable(key, ...)` | `tables` 전부를 돌며 `getRow(target)` | **그대로 쓴다**. 표가 9개로 늘어도 target 이 유일하면 자동으로 맞는다 |
| `diffUpdate(key, rows)` | 바뀐 행만 `updateData` | 그대로. 표 key 만 늘어난다 |
| `ServerData.indexedSimulations()` | 이 장비의 Simulation 색인 | **행 원천(registry)의 공급원** (§4) |

---

## 3. 공통 기반 ① — 카드 레이아웃 엔진

### 3.1 선언 형식

카드는 `#sdContainer` 의 **평평한 자식**이고, 배치는 `grid-template-areas` 가 정한다.
가로·세로 두 벌을 적는다 — 숫자는 `fr` 비중이다.

```js
const SD_LAYOUT = {
    DAEYUN_RCU: {
        cards: ['ai', 'di', 'tx'],
        landscape: { columns: '5fr 5fr', rows: '5fr 5fr',     areas: ['ai tx', 'di tx'] },
        portrait:  { columns: '1fr',     rows: '1fr 1fr 2fr', areas: ['ai', 'di', 'tx'] }
    },
    BACNET: {
        cards: ['binary', 'analog', 'multistate'],
        landscape: { columns: '1fr 1fr 1fr', rows: '1fr',         areas: ['binary analog multistate'] },
        portrait:  { columns: '1fr',         rows: '1fr 1fr 1fr', areas: ['binary', 'analog', 'multistate'] }
    },
    SNMP:        single('oids'),      // 카드 하나. 가로·세로가 같다
    ETHERNET_IP: single('tags'),
    GENERIC:     single('fields'),
    OPC_AGENT:   single('values')
};
```

Daeyun 가로의 `['ai tx', 'di tx']` 가 요청한 5:5 다 — 왼쪽은 AI/DI 가 5:5 로 나눠 갖고,
오른쪽 Generic 은 두 행에 걸쳐 10fr 을 혼자 쓴다.

### 3.2 DOM 과 CSS

```
#sdContainer.is-cardgrid        display:grid; gap:12px
  └ .card[style="grid-area: ai"]
      ├ .card-header            카드 제목 + 우측 배지(행 수·잠금 수)
      └ .card-body              display:flex; flex-direction:column; gap:8px
          └ .sd-subtable        표가 여럿인 카드(BACnet)에서만. 소제목 줄 + 표
```

JS 는 선언을 **CSS 변수 여섯 개**로 옮기기만 한다. 가로/세로 전환은 CSS 가 한다.

```css
.sd-main.is-cardgrid {
    display: grid; gap: 12px;
    grid-template-columns: var(--sd-cols-l);
    grid-template-rows:    var(--sd-rows-l);
    grid-template-areas:   var(--sd-areas-l);
}
@media (orientation: portrait) {
    .sd-main.is-cardgrid {
        grid-template-columns: var(--sd-cols-p);
        grid-template-rows:    var(--sd-rows-p);
        grid-template-areas:   var(--sd-areas-p);
    }
}
```

### 3.3 가로/세로 판정

**`@media (orientation: portrait)`.** 목록 화면 네 곳(devices·simulations·errors·testplans)의
좌우 분할이 이미 이 기준을 쓴다 — `static/css/cts-table.css` 의 `.cts-split`:

```css
@media (orientation: portrait) { .cts-split { flex-direction: column !important; } }
```

같은 기준을 그대로 따른다. **뷰포트 기준이고, JS 임계값도 하한도 두지 않는다.**
화면이 바뀌면 카드 높이가 바뀌고, 카드마다 붙은 `ResizeObserver` 가 표 높이를 다시 계산한다
(§3.4). 즉 전환을 감지하는 코드는 따로 없다 — 높이 계산이 가로/세로를 몰라도 된다.

### 3.4 높이 — 공통 표 규칙을 지키는 방법

표 규칙(행 영역 = k × h, 고정 높이 표는 전부 페이지네이션)은 `CtsTable.attach(table, {rowH})` 가 이미 지킨다.
레이아웃 엔진이 할 일은 **각 표에 정수 px 높이를 주는 것**뿐이다.

```
카드 높이(그리드가 정함)
  − 카드 머리글 높이 − 테두리 2px
  − (표가 여럿이면) 소제목 줄 × n + gap × (n−1)
  ÷ (표 n개의 비중)
  → floor 해서 table.setHeight(h)
```

나눠떨어지지 않는 몇 px 은 `CtsTable` 이 footer(쪽 넘김 줄)에 흘린다. 행은 잘리지 않는다.

**최소 높이와 넘침**

```
--sd-row-h:        33px                     (SD_ROW_H)
--sd-table-min:    33*3 + 34 + 40 = 173px   (3행 + 머리글 + 쪽 넘김 줄)
--sd-card-min:     카드 안 표들의 --sd-table-min 합 + 소제목·gap + 카드 머리글
```

카드에 `min-height` 가 걸려 있으므로, 합이 쓸 수 있는 높이를 넘으면 그리드가 그만큼 늘어나고
**`.sd-main` 이 세로로 스크롤**한다(`.is-focus.is-overflowing` 과 같은 방식).
`fr` 비율은 남는 높이에만 적용된다 — 남는 높이가 0 이면 비율은 의미가 없다.
세로 화면의 BACnet 이 바로 이 경우다: 카드 3개 × 최소 ~570px = 1710px 라 거의 항상 스크롤한다.
**"비율대로 나눈다"는 약속은 자리가 있을 때만 성립한다** — 이걸 숨기지 않고 스크롤로 드러낸다.

---

## 4. 공통 기반 ② — 행의 원천과 공통 열

### 4.1 행은 두 곳에서 온다

지금은 payload 의 값 맵만 본다. 그래서 **등록은 됐지만 아직 한 번도 안 쓰인 target 은 보이지 않는다.**
Simulation 이 꺼져 있거나 주기가 길면 화면이 비어 있고, 사용자는 등록이 잘못된 건지 아직 안 돈 건지 알 수 없다.

```
행 = (payload 의 값 맵 키)  ∪  (이 장비에 등록된 Simulation 의 target)  ∪  (선언된 TX 필드 id)
                                                                          └ Generic / Daeyun 만
```

값이 없는 행의 Value 는 **`—`** 로 둔다. `0` 으로 두면 "0 을 썼다"와 구분되지 않는다.

### 4.2 스냅샷에 싣는 registry

`ServerData` 가 이미 장비의 Simulation 색인을 들고 있다(`indexedSimulations()`). DB 를 다시 보지 않는다.

```java
// ServerDataSnapshot
public List<TargetInfo> registry;   // tag 계열 프로토콜에서만 채운다

public static class TargetInfo {
    public String  target;
    public Integer simulationId;
    public String  name;        // Simulation 이름
    public String  dataType;    // policies[0].data_type, 없으면 null
    public String  wireType;    // 프로토콜이 실제로 내보내는 타입. EIP 는 CIP 타입, 나머지는 null
    public boolean enabled;
}
```

- 채우는 프로토콜: `SNMP`, `ETHERNET_IP`, `OPC_AGENT`, `GENERIC`, `DAEYUN_RCU`.
- **BACnet 은 채우지 않는다** — `payload.objects` 가 이미 같은 일을 하고, 요청한 열에 Type 이 없다.
- **Modbus·XGT 도 채우지 않는다** — 격자 뷰가 쓰지 않는데 장비당 수천 건이 30초마다 오간다.
- 크기: 스냅샷은 30초에 한 번(`SNAPSHOT_INTERVAL_MS`) 나간다. 항목당 ~60B 이므로
  3400건짜리 장비도 ~200KB 다. OPC 의 `values`(3000칸)와 같은 자릿수라 새 부담이 아니다.

### 4.3 공통 열

행 하나가 target 하나인 표는 전부 같은 열을 쓴다. 폭은 균등 분배이고 가중치는 두지 않는다(표 공통 규칙).

| 열 | kind | 내용 |
|---|---|---|
| Simulation | text | Simulation 이름. 등록 없이 API 로만 쓴 target 은 `—` |
| *(주소)* | number 또는 text | 프로토콜마다 이름이 다르다 — Channel / OID / Tag / Field / Index / Instance |
| Type | text | `dataType`. EIP 는 `SI32DA · DINT` 처럼 wireType 을 덧붙인다 |
| Value | number | 늘 숫자, 오른쪽. 잠금이면 자물쇠 아이콘. 값 없으면 `—` |
| Source | text | `VALUE_SIMULATION` / `ERROR_SIMULATION` / `PROTOCOL_WRITE` / `API_WRITE` 배지 |

- **Daeyun 의 AI/DI 표만 예외**다. Simulation 열과 Type 열을 두지 않는다 (§5.1).
- **정렬 순서는 화면이 정한다.** OID 는 마디(arc)별 숫자 비교, 채널·인덱스·Instance 는 수 비교,
  TX 필드는 **선언 순서**(tx frame 의 fields 순), 그 밖은 문자열 비교. 백엔드 `HashMap` 순서에 기대지 않는다.
- 편집 모달은 지금 `mode:'free'` 다. Type 을 알게 되므로 `DataTypeRules`/`cts-datatypes.js` 의
  허용 타입이 하나면 `mode:'datatype'` 으로 올려 검증을 붙인다.

---

## 5. 프로토콜별 설계

### 5.1 DAEYUN_RCU (MK RCU)

```
가로 (wide)                                 세로 (narrow)
┌────────────────┬────────────────┐         ┌──────────────────┐
│ Analog Inputs  │                │         │  Analog Inputs   │ 1
│      5fr       │                │         ├──────────────────┤
├────────────────┤  Generic Tags  │         │ Digital Inputs   │ 1
│ Digital Inputs │     10fr       │         ├──────────────────┤
│      5fr       │                │         │                  │
│                │                │         │  Generic Tags    │ 2
└────────────────┴────────────────┘         │                  │
      5fr               5fr                 └──────────────────┘
```

| 카드 | key | 제목 | 행 |
|---|---|---|---|
| AI | `ai` | `Analog Inputs (@RCUAI_)` | 채널 1..min(`aiCount`, 32) |
| DI | `di` | `Digital Inputs (@RCUDI_)` | 채널 1..min(`diCount`, 32) |
| Generic | `tx` | `Generic TX Fields` | 선언된 TX 필드 ∪ `txFields` 키 ∪ 채널이 아닌 등록 target |

- **채널 수**: 스냅샷은 늘 32칸을 보내지만, 장비 설정이 그보다 적으면 그 뒤는 쓸 수 없는 칸이다
  (`SimulationTargetValidator` 도 `min(count, 32)` 로 막는다). 화면이 `device.communicationSettings`
  를 이미 들고 있으므로 **잘라서 그린다. 백엔드 변경 없음.**
- **주소 열 이름을 고친다**: `Byte addr` → `Channel`. XGT 열 정의 재사용을 끊는다.
- **AI/DI 의 열은 `Channel` / `Value` / `Source` 셋이다.** Simulation 열도 Type 열도 두지 않는다.
  Type 은 채널마다 `INTEGER`(AI) / `BINARY`(DI) 로 고정이라 정보량이 없고, Simulation 이름은
  채널 번호가 이미 자리를 가리키므로 열을 하나 더 쓸 값이 없다 (§8-③).
  Generic TX 카드는 다르다 — 거기는 공통 열을 그대로 쓴다.

**Generic 카드의 추가 정보** — 요청 3 의 "여기도 고려한다"에 대한 답:

TX 프레임 정의(`device.communicationSettings.tx`)가 이미 화면에 들어와 있다. 거기엔 값 맵에 없는 것이 있다.

```json
{"tx_id":"req","fields":[{"field_id":"1","field_type":"SIMULATION","data_type":"UI16AB"},
                         {"field_id":"2","field_type":"SIMULATION","data_type":"FP32AD"}]}
```

- `field_type` 이 `SIMULATION` 인 필드만 시뮬레이션이 값을 넣을 수 있다.
  `STATIC`/`REF`/`ITERATION` 은 프레임이 스스로 만든다 — **값을 쓸 수 없는 필드**다.
  그래서 Generic 표는 `SIMULATION` 필드를 행으로 쓰고, 나머지는 카드 머리글에
  `3 computed field(s) hidden` 배지로만 알린다.
- `data_type` 은 Type 열의 원천이 된다(등록된 Simulation 이 없어도 알 수 있다).
- 프레임이 둘 이상이면 `Frame` 열을 하나 더 붙인다(하나뿐이면 붙이지 않는다).

### 5.2 BACNET

```
가로 (wide)                                        세로 (narrow)
┌───────────┬───────────┬─────────────┐           ┌─────────────────┐
│  Binary   │  Analog   │ Multi-state │           │     Binary      │ 1
│ ┌───────┐ │ ┌───────┐ │ ┌─────────┐ │           ├─────────────────┤
│ │Input  │1│ │Input  │1│ │Input    │1│           │     Analog      │ 1
│ ├───────┤ │ ├───────┤ │ ├─────────┤ │           ├─────────────────┤
│ │Output │1│ │Output │1│ │Output   │1│           │   Multi-state   │ 1
│ ├───────┤ │ ├───────┤ │ ├─────────┤ │           └─────────────────┘
│ │Value  │1│ │Value  │1│ │Value    │1│
│ └───────┘ │ └───────┘ │ └─────────┘ │           (카드 안의 3표는 가로와 같게 1:1:1)
└───────────┴───────────┴─────────────┘
    1fr         1fr          1fr
```

**표 하나의 열** (요청대로 Type 은 뺀다 — 자리가 곧 타입이다)

| 열 | kind | 내용 |
|---|---|---|
| Simulation | text | `objects[].name` |
| Instance | number | `objects[].target` 의 instance 부분 |
| Value | number | `objects[].value`. 아직 안 돌았으면 `—` |
| Source | text | 배지 |

- 행 분류는 `target`(`"<object_type> <instance>"`)을 쪼개서 한다. `BACnetConstants` 에 byte 로 선언된
  object type 은 **정확히 9개**(analog/binary/multi_state × input/output/value)이고
  `SimulationTargetValidator` 가 그 밖을 거절하므로, **3×3 이 등록 가능한 전부를 덮는다.**
- 다만 검증이 생기기 전에 들어온 옛 데이터는 `object` 가 `null` 로 올 수 있다. 이런 행은
  **화면에 표기하지 않고 조용히 뺀다** — 어느 카드에도 속하지 않는다 (§8-②).
  추적이 필요하면 서버 로그(`serverdata`)로 가린다. 화면은 등록 가능한 9종만 보여 준다.
- 표 key 는 `bac_binary_input` 처럼 짓는다. `tables`/`allRows`/`displayFormatters` 에 그대로 등록하면
  `applyDeltaToRowTable` 가 9개를 다 훑으므로 **실시간 갱신 코드는 손댈 것이 없다.**
- 빈 표는 가운데 정렬 안내문(`No objects`)을 쓴다. 9개 중 여럿이 비는 것이 보통이다.

### 5.3 SNMP / ETHERNET_IP / OPC_AGENT / GENERIC — 줄 만한 추가 정보

카드 구성은 바꿀 것이 없다(자연스러운 묶음이 하나뿐이다). 바뀌는 것은 **카드가 높이를 다 쓴다는 것**과
**행·열의 내용**이다. 검토 결과는 다음과 같다.

| 후보 | 지금 있나 | 넣나 | 비고 |
|---|---|---|---|
| Simulation 이름 | 있음 (`indexedSimulations()`) | **넣는다** | "이 OID 를 누가 흔드나"가 화면에서 바로 보인다 |
| Data type | 있음 (policies[0]) | **넣는다** | Generic 은 TX 선언에서도 온다 |
| 등록만 되고 값이 없는 target | 있음 | **넣는다** | 값은 `—`. 등록 실수와 "아직 안 돎"이 구분된다 |
| 마지막 변경 시각 | 화면이 이미 센다(`recordChange`) | **넣는다** | 백엔드 변경 없음. 상대시간(`12s ago`) |
| EIP 의 CIP 타입 | 있음 (`EthernetIPTypes.cipTypeOf`) | **넣는다** | Type 열에 `SI32DA · DINT` |
| Generic 의 TX 필드 종류 | 있음 (`field_type`) | **넣는다** | §5.1 과 같은 규칙 |
| OPC 의 "쓰이는 인덱스" 구분 | 있음 | **안 넣는다** | 전부 보여 준다. 아래 참조 |
| 잠금의 주체·만료 | 백엔드엔 있고 스냅샷엔 없음 | **2단계** | `locks` 를 `[{target, authority, until}]` 로. Modbus 격자도 같이 덕을 본다 |
| 프로토콜이 이 target 을 몇 번 읽어 갔나 | **없다** | **안 넣는다** | 핸들러마다 계수기를 심어야 한다. 값에 비해 품이 크다 |
| SNMP MIB 이름 | **없다** | **안 넣는다** | 장비 설정에 MIB 이 없다. 넣으려면 MIB 등록 기능이 먼저다 |

**OPC_AGENT** 는 `values` 의 **모든 인덱스를 행으로 둔다**(§8-④). `values` 는 `registers` 길이라
이 인스턴스는 3000행, 최대 65536행이 된다. 쪽 수가 많아지는 것은 감수한다 — 저장 칸이 거기 있다는 것이
그대로 보이는 편이 낫다. 다른 프로토콜과 달리 "등록만 되고 값 없는 행"을 따로 찾을 필요도 없다
(인덱스가 곧 칸이라 전부 이미 있다). registry 는 Simulation·Type 열을 채우는 데만 쓴다.

**정렬**도 여기서 고친다(§4.3). SNMP 는 `.1.3.6.1.4.1.2` < `.1.3.6.1.4.1.3` < `.1.3.6.1.4.1.10` 이
되도록 마디별 수 비교를 쓴다. 문자열 비교로는 `.10` 이 `.2` 앞에 온다.

---

## 6. 백엔드 변경

딱 하나다.

1. **`ServerDataSnapshot`** — `registry: List<TargetInfo>` 추가(§4.2). `sd.indexedSimulations()` 에서 만든다.
   `ETHERNET_IP` 는 `wireType` 에 `EthernetIPTypes.cipTypeOf(dataType)` 의 이름을 채운다.
   나머지 프로토콜(MODBUS/XGT/BACNET)에서는 `null` 로 둔다.

안 하는 것:
- 값 맵을 `LinkedHashMap` 으로 바꾸지 않는다 — 순서는 화면의 몫이다(§4.3).
- Daeyun 채널 수를 스냅샷에 싣지 않는다 — 화면이 `device.communicationSettings` 를 이미 갖고 있다.
- 2단계로 미룬 것: `locks` 를 상세 구조로 바꾸는 일.

---

## 7. 프런트 변경 (`serverdata.html`)

1. **레이아웃 엔진** `sdLayout` — §3. `SD_LAYOUT` 선언을 CSS 변수로 옮기고, 카드마다 `ResizeObserver` 를
   달아 표 높이를 다시 준다. 가로/세로 전환은 CSS 가 하므로 JS 에 그 분기가 없다.
2. **표 선언** `SD_CARDS[protocol]` — 카드 제목, 표 제목, 열 정의(`CtsTable.columns`), 행 만드는 함수.
3. `renderCards(snap)` 하나가 `renderDaeyun` / `renderKeyValue` / `renderBACnet` / `renderOpcAgent` 를 **대체**한다.
   `renderRawLegacy` 는 XGT 만 남는다.
4. `fitEntryCardHeight` 삭제 (엔진이 대신한다). `fitOpenCardHeight`(XGT 아코디언)는 그대로.
5. 행 만들기 공통 함수: `payload 값 맵 + registry + TX 선언` → 행 배열 (§4.1), 정렬 규칙 포함.
6. 편집 모달 `mode` 를 target 의 허용 타입에서 정한다(`free` → `datatype`).
7. CSS: `.sd-main.is-cardgrid`, `.sd-col`, `.sd-subtable`, `--sd-card-min` 등.

**건드리지 않는 것**: Focus 뷰, Modbus 어댑터, Watch/Inspection, WebSocket·이벤트 처리
(`applyEventDelta` → `applyDeltaToRowTable` 경로는 표가 9개가 되어도 그대로 맞는다).

---

## 8. 정해진 것 (2026-11-04)

설계 검토에서 물은 여섯 가지의 답이다. 위 본문은 이미 이 답을 반영했다.

| # | 물은 것 | 정해진 답 |
|---|---|---|
| ① | 가로/세로 판정 기준 | **여태 쓰던 기준을 따른다** — `@media (orientation: portrait)`. 목록 화면의 `.cts-split` 와 같다. 새 임계값·하한을 두지 않는다 (§3.3) |
| ② | BACnet 에서 해석 안 되는 target | **표기하지 않는다.** 화면에서 빼고, 추적은 사용자가 로그로 한다 (§5.2) |
| ③ | Daeyun AI/DI 에 Simulation 열 | **필요 없다.** AI/DI 는 `Channel` / `Value` / `Source` 세 열 (§5.1) |
| ④ | OPC 기본 행 | **전부 표시한다.** 쓰이는 인덱스 필터·토글을 두지 않는다 (§5.3) |
| ⑤ | EIP Type 열 표기 | **`SI32DA · DINT` 한 열로 해 본다.** 써 보고 불편하면 그때 나눈다 (§4.3) |
| ⑥ | Focus 탭 | **Modbus 전용을 유지한다.** 새 카드 레이아웃은 Raw 에만 둔다 |

---

## 9. 단계와 검증

| 단계 | 내용 | 요청 |
|---|---|---|
| 1 | 레이아웃 엔진 + Daeyun 3카드 + BACnet 3카드×3표 | 3, 4 |
| 2 | registry·공통 열(Simulation/Type/Source/Last update), 행 정렬 규칙 | 2 |
| 3 | 잠금 상세(주체·만료), 필요하면 Watch/Inspection 어댑터 확장 | — |

**검증**

- `tools/testkit/s19_snapshot_shapes` 에 `registry` 키 확인을 더한다.
- `tools/testkit/s16_serverdata_page` 에 카드 수·표 수 확인을 더한다
  (Daeyun 카드 3, BACnet 카드 3 · 표 9).
- 레이아웃은 **CDP 로 찍어서 본다**. Server Data 는 WebSocket 때문에 `--virtual-time-budget` 이 끝나지 않는다
  (`tools/testkit/cdp.py`). 가로(1600×1000)·세로(900×1200) 두 벌을 Daeyun·BACnet 에 대해 찍는다.
- 표 공통 규칙 점검: 행이 잘리지 않는가, 한 행 미만의 빈 여백이 없는가, 열 폭 합이 표 폭과 같은가,
  빈 표 안내문이 가운데 있는가.

---

## 10. 구현 메모 (2026-11-04)

설계대로 넣었고, 쓰면서 달라진 것만 적는다.

- **Updated 열은 상대시간이 아니라 시:분:초**다. `12s ago` 로 하면 1초마다 보이는 행을 다시 그려야 하는데,
  OPC 처럼 행이 3000개인 표에서 그 값이 비용에 못 미친다. 바뀔 때만 고쳐 쓰면 되는 절대 시각으로 뒀다.
- **Source 열만 폭을 고정했다(156px).** `VALUE_SIMULATION` 배지가 135px + 칸 여백 20px 이라 균등 분배
  폭(좁은 카드에서 ~100px)에서 잘렸다. 표 공통 규칙이 허용하는 예외(`resizable:false`)를 쓴다.
  나머지 열은 그대로 균등 분배다.
- **`writeSnapshotValue` 를 모든 프로토콜로 넓혔다.** 설계에 없던 버그다. 이 함수가 MODBUS·BACNET 만
  반영하고 있어서, lock 이 바뀔 때 `applyLocks` 가 옛 스냅샷으로 전체를 다시 그리면 **방금 쓴 값이
  이전 값으로 되돌아갔다**. XGT 도 같은 결함이 있어 함께 고쳤다(표시 경로만 손댔고 XGT 화면은 그대로다).
- **편집창의 data type.** 예전에는 늘 `mode:'free'` 라 타입 없이 보냈다. 이제 target 이 받는 타입을
  골라 함께 보낸다. 그러면서 `validateEditValue` 에 `INTEGER`/`FLOAT`/`BINARY` 가지를 더했다 —
  이 셋은 비트 수가 없어 기존 `datatype` 가지가 조용히 `undefined` 를 돌려주던 자리다.
- **Daeyun AI/DI 의 행 수**는 `communicationSettings.aiCount`/`diCount` 로 자른다(최대 32).
  이 장비는 AI 가 4채널이라 표에 4행만 나온다.

**확인한 것**: 프로토콜 8종 화면이 JS 오류 없이 그려진다(Modbus Focus·XGT 아코디언 포함).
Daeyun·BACnet 의 가로/세로 두 벌을 CDP 로 찍어 비율을 확인했고, 다섯 프로토콜에 REST 쓰기를 넣어
값·잠금·출처·시각이 행에 반영되는 것을 확인했다. 열 폭 합이 표 폭과 같다(647/648, 426/427 — 1px 여유).

**남은 testkit 실패 2건은 이 작업과 무관하다** — 둘 다 먼저 들어온 미커밋 변경 때문이다.
`s16` 의 "두 번째 창 거절" 은 `cts.serverdata.multi-session.enabled=true` 가 켜져 있어 거절되지 않는 것이고,
`s19` 는 OPC_AGENT 에 `UI16AB` 를 등록하다 새 `DataTypeRules` 에 막힌다. 두 시나리오가 각각의 변경에
맞춰 갱신되어야 한다.
