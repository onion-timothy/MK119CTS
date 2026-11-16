# Protocol Definitions — 엔진·저장·장비 연결 설계

> 상태: **구현됨** · 2026-11-11. 이 문서대로 만들었다. 다른 점과 시험 결과는 맨 끝 17절.
> 형식 자체(필드·타입·식·타겟·체크섬)는 [PROTOCOL_DEFINITION_FORMAT.md](PROTOCOL_DEFINITION_FORMAT.md) 를 따른다.
> 이 문서는 그 형식을 **저장하고, 실행하고, 장비에 붙이는** 방법을 정한다.

---

## 0. 확정된 것

| 항목 | 결정 |
| --- | --- |
| 이름 | **Protocol Definitions**. 하나는 *protocol definition*. 형식 이름은 `"format": "cts-protocol", "version": 1` |
| 저장 | 장비와 따로, DB 테이블 `protocol_definitions` |
| GENERIC | PPD/PGD 를 **완전히 대치**한다. 버전 공존·마이그레이션 없음. 기존 GENERIC 장비는 정의를 새로 지정한다 |
| DAEYUN_RCU | RS(0x0A, 시리얼 중계) 본문을 해석하던 PPD/PGD 부분을 같은 엔진으로 대치 |
| 저장 검사 | 편집기가 오류 있는 문서의 Save 를 막는다. 서버는 엔진 컴파일러로 다시 거른다 (컴파일 안 되는 문서는 장비가 쓸 수 없으므로) |
| 순서 | 엔진과 장비 연결을 한 번에 넣는다. 그 사이 GENERIC·RCU 는 지금처럼 돈다 |

---

## 1. 한눈에

```
 DB protocol_definitions ──(저장 시 컴파일·시험)──▶ DefinitionRegistry ── id → CompiledDefinition (불변, 공유)
                                                         │ 저장되면 새 판으로 바꿔 끼움
 Device (GENERIC)  { definitionId, params } ─────────────┤
 Device (DAEYUN_RCU) { …, definitionId?, params } ───────┤
                                                         ▼
 Netty  ──▶ GenericStreamDecoder (TCP)   ─┐
        ──▶ GenericDatagramDecoder (UDP) ─┼─▶ ProtocolEngine.match ─▶ GenericHandler ─▶ ProtocolEngine.respond
        ──▶ DaeyunDecoder (RS 본문)       ─┘                          └─ DaeyunHandler (RS 응답을 RCU 껍데기에)
                                                         │
                         GenericData (target → 물리값)  ◀─┴─ 쓰기는 ServerData.setServerData(…, PROTOCOL_WRITE)
```

- **CompiledDefinition** 은 문서를 한 번 해석해 만든 실행 구조다. 식은 미리 파싱되어 있고, 이름은 모두 풀려 있다. 여러 장비·연결이 함께 쓴다.
- **ProtocolEngine** 은 `정의 + 장비 params + 저장소` 를 묶은 것이다. 장비마다 하나다.
- 연결마다 상태는 **TCP 버퍼와 대기 타이머뿐**이다. 엔진 호출 자체는 상태가 없다.

패키지: `com.onionsoftware.mk119.commtestsuite.protocol`

| 하위 | 내용 |
| --- | --- |
| `compile` | JSON → CompiledDefinition. 이름 풀기, 식 컴파일, 길이·체크섬 순서 계산, 오류 위치 |
| `expr` | 식 파서(Pratt)·타입 검사·실행 |
| `codec` | 타입별 읽기/쓰기 (숫자형·BCD·TEXT·TEXTNUM·HEXNUM·BYTES·PAD·BITS·구조체·배열), 변환 |
| `checksum` | CRC(Rocksoft, 표 방식)·SUM·XOR8·LRC8·EXPR |
| `engine` | 매칭·응답 규칙·송신 인코딩·타겟 맵 |
| `test` | 문서의 `tests` 실행기 |

Netty 와 맞물리는 클래스는 지금처럼 `io.netty.decoder`·`io.netty.handler` 에 둔다.

---

## 2. 저장과 API

### 2.1 테이블 `protocol_definitions`

| 칸 | 내용 |
| --- | --- |
| `id` | |
| `name` | 고유, 100자. 문서의 `name` 과 같다 (저장 때 서버가 문서 쪽 값을 쓴다) |
| `description` | 문서의 `doc` (목록용) |
| `content` | 문서 JSON 전체 (LONGTEXT) |
| `version` | 낙관적 잠금. 저장할 때 보낸 값과 다르면 409 — 두 창에서 고쳐도 나중 것이 앞 것을 덮지 않는다 |
| `createdAt`, `updatedAt` | |

### 2.2 API `/api/protocols`

| 요청 | 동작 |
| --- | --- |
| `GET /` | 목록: id·이름·설명·메시지 수(RX/TX)·쓰는 장비 수·수정 시각 |
| `GET /{id}` | 문서·`version`·쓰는 장비(id·이름·프로토콜) |
| `POST /` | 새로 만들기. 본문 = 문서 |
| `PUT /{id}` | 저장. `version` 동봉 |
| `DELETE /{id}` | 쓰는 장비가 있으면 409 + 장비 이름 |
| `POST /{id}/duplicate` | 복제 (`이름 copy`, 겹치면 `copy 2` …) |
| `POST /check` | 저장하지 않고 컴파일·시험만. 편집기의 **Run tests** 와 저장 전 확인에 쓴다 |
| `GET /{id}/targets` | 타겟 목록 (9.1). 타겟 입력 보조가 쓴다 |

저장(`POST`·`PUT`)은 **컴파일 → tests 실행 → 저장** 순서다. 실패하면 400 과 함께 문제 목록을 돌려준다:

```json
{ "problems": [ { "level": "error", "at": ["messages", 0, "body", 2, "value"], "text": "…" } ],
  "tests":    [ { "name": "read 2 registers", "ok": false, "expected": "01 03 04 …", "actual": "01 83 02 …" } ] }
```

`at` 은 편집기의 문제 목록과 같은 경로다 — 편집기가 그 줄로 바로 간다. 문구는 편집기 검사와 같게 맞춘다.

---

## 3. 컴파일

저장할 때와 장비가 정의를 처음 쓸 때 한 번 한다. 결과는 불변이다.

1. **JSON 모양** — 키 이름·값 종류. 모르는 키는 오류 (오타를 조용히 무시하지 않는다)
2. **이름 풀기** — 프레임·타입·체크섬·타겟 묶음·`$이름`(consts → params 순서)·`reply`·경로
3. **식 컴파일** — 각 식을 그 자리의 **보이는 이름**과 함께 파싱한다 (8.3). 조건 자리는 참거짓인지 검사
4. **길이 판정** — rx 메시지마다 가변 필드가 `size`·`count`·`terminator`·둘러싼 크기 중 하나로 끝을 정할 수 있는지
5. **값 출처** — tx 메시지의 모든 필드가 `const`·`set`·`value`·`target`·`checksum` 중 하나를 가졌는지
6. **체크섬 순서** — 범위가 겹치는 체크섬은 안쪽부터. 순환·자기 포함은 오류
7. **타겟 맵** — 순차 타겟의 번지표(항목 → 시작 번지·칸 수), 겹침 검사
8. **타겟 목록** — 선언된 타겟 + 문서에 글자로 나온 타겟 + 패턴 (9.1)

오류는 위치(`at`)와 함께 모은다. 하나라도 있으면 CompiledDefinition 을 만들지 않는다.
경고(선언에 없는 타겟을 글자로 씀 등)는 저장을 막지 않는다.

---

## 4. 실행 모델

### 4.1 장비와 연결

| | 단위 | 들고 있는 것 |
| --- | --- | --- |
| DefinitionRegistry | 앱 | id → 최신 CompiledDefinition |
| ProtocolEngine | 장비 | 정의 참조, 장비 `params`(기본값을 채운 값), 저장소(GenericData), 쓰기 창구 |
| GenericStreamDecoder | TCP 연결 | 쌓인 바이트, 대기 타이머 |

장비마다 이벤트 루프 스레드가 하나이므로(지금 IOServer 구조) 한 장비의 요청은 차례로 처리된다.
저장소는 Value Simulation 스레드도 쓰므로 `ConcurrentHashMap` 으로 바꾼다.

### 4.2 엔진 호출 모양

```java
MatchResult match(ByteBuf in, Mode mode);   // STREAM(TCP) / EXACT(UDP 데이터그램, RCU 본문)
Reply respond(RxMessage msg);               // 실행한 규칙, 쓰기 결과, 보낼 바이트(또는 없음), delayMs
```

`MatchResult` 는 셋 중 하나다.

| 결과 | 뜻 |
| --- | --- |
| `MATCH(n, msg)` | 앞에서 n 바이트가 메시지 `msg` 다 |
| `NEED_MORE` | 지금까지는 어떤 메시지와 맞는데 바이트가 모자라다 |
| `NONE` | 맞는 메시지가 없다 |

---

## 5. 받기 — 매칭

### 5.1 메시지 하나 시도

rx 메시지를 **문서 순서대로** 시도한다. 한 메시지는 필드를 앞에서부터 읽는다.

1. 필드 `when` 이 거짓이면 건너뛴다
2. 길이 결정: 타입 고정 폭 / `size` / `count` / `terminator` / 둘러싼 크기(`bodySize`·메시지 `size`) 의 남은 부분
3. 바이트가 모자라면 → `NEED_MORE`
4. 읽고(변환이 있으면 되돌린 뒤) 값으로 만든다: `물리값 = raw × scale + offset`
5. `const` 가 다르거나 `expect` 가 거짓이면 → `NONE` (오류가 아니라 "이 메시지가 아님")
6. 끝까지 읽은 뒤: 남는 바이트(EXACT 일 때)·`size` 불일치 → `NONE`, `match` 거짓 → `NONE`
7. 체크섬: 범위를 계산해 받은 값과 비교. `drop` 은 틀리면 `NONE`, `flag` 는 받아들이고 `valid(이름)` 을 거짓으로

### 5.2 여러 메시지 중 고르기 (STREAM)

| 상황 | 처리 |
| --- | --- |
| 어떤 메시지가 `MATCH` 이고 그보다 앞 메시지는 모두 `NONE` | 그 메시지. n 바이트를 소비 |
| 앞 메시지가 `NEED_MORE` | **기다린다** — 뒤 메시지가 맞아도 고르지 않는다 (긴 프레임의 앞부분을 짧은 메시지로 잘못 먹지 않게) |
| 모두 `NONE` | **다시 맞춤** (5.3) |

기다림은 둘 중 하나로 끝난다.

- 바이트가 더 와서 다시 시도
- `idleFlushMs` 가 지나면 `NEED_MORE` 를 `NONE` 으로 보고 한 번 더 시도한다. 그래도 없으면 다시 맞춤

`idleFlushMs` 가 없는 문서는 TCP 에서 **1000 ms** 를 쓴다 (모자란 프레임이 연결을 영원히 막지 않게). 편집기 Framing 칸의 안내 글에 적는다.

### 5.3 다시 맞춤

- `syncOn` 이 있으면 그 바이트(들)가 다음에 나오는 곳 **앞까지** 버린다. 없으면 한 바이트 버린다
- 버린 뒤 남은 바이트로 처음부터 다시 시도
- 버린 바이트는 패킷 로그에 `discarded` 로 남긴다
- 쌓인 바이트가 `maxLength`(기본 1024) 를 넘어도 `NEED_MORE` 면 같은 방식으로 버린다

### 5.4 EXACT (UDP·RCU 본문)

데이터그램(본문) 하나가 프레임 하나다. `NEED_MORE` 와 남는 바이트는 `NONE` 이다. `NONE` 이면 로그를 남기고 버린다.

### 5.5 받은 값의 모양

`RxMessage` = 메시지 이름 + 영역(`header`·`body`·`footer`)별 값 나무 + 받은 바이트 + 체크섬 결과.
값은 정수(64비트)·실수·참거짓·글자·바이트·목록·구조체 중 하나다 (형식 7.3).
`scale`·`offset` 이 없는 정수 타입은 정수로 남겨 비트 연산을 쓸 수 있게 한다.

---

## 6. 응답 규칙

`respond` 를 위에서부터 보고 **처음으로 `when` 이 참인 규칙 하나**만 실행한다. 맞는 규칙이 없으면 응답하지 않는다.

1. **`write`** — 응답보다 먼저. 차례대로:
   - 타겟이 `access: rw` 가 아니거나, 순차 범위가 `fits`/`writable` 하지 않으면 쓰지 않고 로그
   - `ServerData.setServerData(target, 물리값, dataType, PROTOCOL_WRITE)` — 지금의 잠금 규칙(API_WRITE 잠금에 막힘)이 그대로 적용된다
   - 쓴 타겟에 Simulation 이 있으면 `stopOnControl` 적용 (ModbusHandler 와 같은 경로)
   - `dataType` 은 타겟 선언 타입에서: 1비트(`BIT`) → BINARY, 정수형 → INTEGER, 그 밖 → FLOAT
2. **`args`** — rx 문맥에서 계산해 tx 의 `args.*` 로 넘긴다
3. **`reply`**
   - `none` → 보내지 않음
   - `echo` → 받은 바이트 그대로
   - tx 메시지 이름 → 7절로 인코딩
4. **`delayMs`** — 이벤트 루프를 막지 않고 `ctx.executor().schedule` 로 보낸다

보낼 바이트는 지금의 `MkHandler` 송신 경로를 그대로 탄다. 그래서 시스템 오류(DISCARD·TIMEOUT·GARBAGE·DISCONNECT)와 패킷 로그가 바뀌지 않고 적용된다.
이를 위해 `MkHandler` 의 송신부를 `send(ctx, buf)` 로 떼어 내고, 지연 송신도 그 함수를 부른다.

---

## 7. 보내기 — 인코딩

tx 메시지 하나를 바이트로 만드는 순서다.

1. **값 정하기** — 필드마다 `const` → 메시지 `set` → 필드 `value` → 필드 `target` (형식 5.2). 배열 `count`, 조건 `when` 도 여기서
2. **인코딩** — 체크섬 자리는 0 으로 채운다. `raw = round((물리값 − offset) / scale)` 를 타입으로 바꾼다
3. **범위 넘침** — `onOverflow`: `error`(기본) 는 이 응답을 보내지 않고 메시지·필드·값을 로그. `clamp`·`wrap`
4. **길이 값** — `sizeof(…)`·`len(…)` 처럼 다른 영역 크기에 기대는 값은, 그 영역을 먼저 인코딩한 뒤 정한다.
   길이 필드가 고정 폭이면 한 번에 끝난다. 가변 폭(끝 글자 TEXTNUM 등)이면 크기가 바뀌지 않을 때까지 최대 3번 되풀이하고, 그래도 바뀌면 오류
5. **변환** — 구조체에 단 `transform` 을 적용 (`ascii-hex`·`add`·`xor`)
6. **체크섬** — 3절에서 정한 순서대로. `over: value` 는 변환 전 바이트, `wire` 는 변환 뒤 바이트로 계산해 자리를 덮어쓴다.
   변환된 구조체 안에 든 `value` 체크섬(Modbus ASCII 의 LRC)은 변환 **전에** 채운다

결과가 `MkHandler.sendBuffer` 가 된다.

---

## 8. 식

### 8.1 파서와 실행

- 직접 만든 Pratt 파서. 연산자 순위·리터럴·함수는 형식 7절 그대로
- 컴파일 때 노드마다 결과 종류(정수·실수·참거짓·글자·바이트·목록)를 정한다. 섞일 수 있으면 실행 때 맞춘다
- 실행은 컴파일된 노드 나무를 그대로 돈다. 문서를 다시 해석하지 않는다

### 8.2 실행 중 오류

0 으로 나눔, 배열 범위 밖, 없는 경로(조건부 필드가 빠짐), 범위 넘침 →
**그 요청에 응답하지 않고** 로그에 메시지·규칙·식·값을 남긴다. 연결은 끊지 않는다.

### 8.3 보이는 이름

| 자리 | 보이는 것 |
| --- | --- |
| rx 필드의 `when`·`size`·`count`·`expect` | 이 메시지에서 **앞에 읽은** 필드, `this`(expect), `$`, 함수 |
| rx `match`, respond `when`·`args`, `write` | 이 메시지 전체, `$`, 함수 |
| tx 필드 `value`, `set` | 이 메시지 전체, `req.`, `args.`, `i`(배열 위치), `$`, 함수 |
| 체크섬 `EXPR` | `acc`, `b` |

---

## 9. 타겟과 저장소

### 9.1 타겟 목록

장비의 타겟 목록 = 정의의 **선언된 타겟**(순차·이름 묶음의 항목) + **문서에 글자로 나온 타겟**(`target: "…"`, `data('…')`) + **패턴**.
`GET /api/protocols/{id}/targets` 가 돌려주는 모양:

```json
{ "targets": [ { "target": "전압1", "group": "holding", "type": "UI16AB", "scale": 0.1, "access": "r", "dataTypes": ["INTEGER", "FLOAT"] } ],
  "patterns": [ { "group": "channels", "pattern": "^ch[0-9]{1,2}$", "dataTypes": ["INTEGER", "FLOAT"] } ] }
```

`dataTypes` 는 형식 6.9 의 표다 (그 타겟에 등록할 수 있는 Value Simulation data type).

### 9.2 저장소

`GenericData` 를 그대로 쓴다: **target → 물리값(double)**, 동시 접근 안전한 맵으로 바꾼다.
순차 타겟도 항목 하나 = 키 하나다 (32비트 값을 칸으로 쪼개 저장하지 않는다).
없는 키를 읽으면 0. RCU 의 `DaeyunData` 는 채널 배열은 그대로 두고, RS 본문용 맵을 같은 방식으로 쓴다.

### 9.3 바뀌는 주변 규칙

지금 GENERIC 은 target 이 자유 형식이고, "그 target 의 Simulation 이 있어야 쓸 수 있다(501)" 는 규칙으로 막는다. 정의가 생기면 기준이 **타겟 목록**으로 바뀐다.

| 곳 | 지금 | 바뀐 뒤 |
| --- | --- | --- |
| `SimulationTargetValidator` (Simulation 등록) | GENERIC 은 검사 안 함 | 타겟 목록에 있거나 패턴에 맞아야. 아니면 `No target "X" in protocol definition "power-meter".` |
| `DataTypeRules.allowed` | 프로토콜만 보고 BINARY·INTEGER·FLOAT | GENERIC·RCU(채널 밖)는 장비의 정의에서 그 타겟의 `dataTypes`. 장비를 받는 판을 더한다 |
| `ServerDataWriteService` (REST·편집 세션) | Simulation 이 있어야(501) | 타겟 목록에 있어야 (Simulation 없어도 쓴다) |
| `TestPlanValidator` A4 | Simulation target 이어야 | 타겟 목록에 있어야 |
| `ServerDataSnapshot` (Server Data 화면) | 맵에 있는 것만 | 타겟 목록 순서대로 전부(값 없으면 0) + 목록 밖 맵 항목 |
| 타겟 입력 보조 (Simulation 편집기·Test Plan 팝오버) | GENERIC 은 자유 입력 | 타겟 목록에서 고르기 (패턴은 안내만) |

RCU 의 `@RCUDI_n`·`@RCUAI_n` 규칙은 그대로다.

---

## 10. 장비 연결

### 10.1 설정 모양

```json
// GENERIC
{ "definitionId": 12, "params": { "unitId": 3 } }
// DAEYUN_RCU — RS 본문만 정의로. 정의가 없으면 RS 요청에 답하지 않는다(지금 tx 가 없을 때와 같다)
{ "cpuAddr": 1, "diCount": 16, "aiCount": 8, "doCount": 8, "definitionId": 12, "params": {} }
```

- `params` 는 정의의 `params` 에 있는 이름만. 비우면 기본값. 타입·범위는 정의에서
- `DeviceSettingsValidator`: GENERIC 은 `definitionId` 필수·존재, `params` 타입·범위. RCU 는 `definitionId` 선택. **JS(편집기·일괄 등록)·Java 규칙을 같이 고친다**
- 지금의 `rx`·`tx` 키는 GENERIC·RCU 모두에서 없앤다. 남아 있어도 읽지 않는다 (마이그레이션 없음)

### 10.2 Netty 연결

| 프로토콜·전송 | decoder | handler |
| --- | --- | --- |
| GENERIC TCP | `GenericStreamDecoder` — 5.2·5.3, 대기 타이머 | `GenericHandler` — 6절, 지연 송신 |
| GENERIC UDP | `GenericDatagramDecoder` — 5.4 | `GenericHandler` |
| DAEYUN_RCU | `DaeyunDecoder` 의 RS 분기가 본문을 `match(EXACT)` | `DaeyunHandler` 의 RS 분기가 `respond` 결과를 포트·길이·CRC 껍데기에 담는다 |

정의가 지정되지 않았거나 컴파일되지 않는 장비는 포트는 열되, 받은 요청마다 응답하지 않고 `Device "X" has no usable protocol definition` 를 로그에 남긴다.

### 10.3 정의를 고쳐 저장하면

**연결을 끊지 않고 다음 요청부터** 새 판을 쓴다.

- Registry 가 새 CompiledDefinition 으로 바꿔 끼운다 (id 별 참조 하나를 바꾸는 것뿐)
- 엔진은 요청마다 최신 판을 읽는다. 저장소 값은 그대로 남는다
- TCP 버퍼에 걸려 있던 모자란 프레임은 새 판으로 다시 시도된다
- 타겟이 빠진 경우: 그 타겟의 Simulation 은 계속 돌지만(맵에 쓸 뿐), 등록·REST 쓰기 검사는 새 목록을 따른다. 목록 화면 상세 카드에 "정의에 없는 타겟을 쓰는 Simulation" 을 경고로 보인다

---

## 11. 시험 사례 (tests) 실행

- 빈 저장소 + 문서의 `params` 기본값(사례에 `params` 가 있으면 그 값)으로 엔진을 만든다
- 사례마다: `data` 를 저장소에 넣음 → `send` 를 `match(EXACT)` → `respond` → 보낸 바이트를 `expect` 와 비교, `expectWrites` 를 저장소와 비교
- 사례는 순서대로, 앞 사례의 값·쓰기가 남는다 (형식 12)
- 쓰기 창구는 시험용(메모리만)을 쓴다 — 실제 장비·잠금·이벤트에 닿지 않는다
- **저장 때 모두 돌리고, 하나라도 실패하면 저장을 거부**한다. 편집기의 Tests 화면에 **Run tests** 를 두어 저장 전에 돌려 볼 수 있게 한다 (`POST /check`)

---

## 12. 로그

- 패킷 로그(RX·TX): 지금 그대로
- `io` 로거에 장비 이름과 함께:
  - 매칭 실패·다시 맞춤으로 버린 바이트 (warn)
  - 고른 메시지·규칙 이름 (debug)
  - 쓰기가 막히거나 건너뛴 이유 (warn)
  - 실행 중 식 오류·범위 넘침 (warn)

---

## 13. 이번에 넣지 않는 것

| 항목 | 이유 |
| --- | --- |
| 바이트 스터핑 `escape` | 스트림에서 "날 바이트"와 "벗긴 바이트"를 구분해 매칭해야 해서 따로 설계가 필요. 편집기의 Byte stuffing 칸은 숨긴다 |
| 명령별 오류(respond `name` 을 Error 의 target 으로), 응답 변형 | Error 쪽 모델을 바꿔야 한다. 시스템 오류 4종은 그대로 적용된다 |
| 글자 타겟 | 저장소가 수만 담는다. TEXT 는 `const`·`value` 로만 |

---

## 14. 지우는 것 (PPD/PGD)

- Java: `io.netty.generic.*`(RXField·RXFrame·TXField·TXFieldType·TXFrame), `GenericTCPDecoder`·`GenericUDPDecoder`·`GenericTCPUDPHandler`·`GenericTCPUDPPacket`, `ByteBufUtils` 의 PPD/PGD 함수(`parsePacket`·`_writeTXPacketByFrame`·`calulateTXLength` 등), `DeviceSettingsValidator.packetProblems`
- 화면: 장비 편집기의 PPD/PGD 카드, 일괄 등록의 패킷 모달(`packetsModal`), `js/cts-packets.js`, `/packets/editor`
- RCU 의 CRC 껍데기용 `Checksum.getCRC16` 은 남긴다 (Modbus 도 쓴다)
- 매뉴얼의 PPD/PGD 절은 따로 고친다

---

## 15. 검증

**단위 시험 (JUnit)**
- CRC 카탈로그 check 값, SUM·XOR8·LRC8·EXPR
- 타입별 왕복 (보내고 다시 읽으면 같은 값), 범위 넘침 3가지
- 식: 연산자 순위, 종류 검사, 실행 오류
- 컴파일 오류 위치 (`at`)
- 예시 문서 셋(power-meter·thermo-controller·ascii-meter)의 `tests` 를 엔진으로 실행
- 스트림 매칭: 쪼개져 오는 프레임, 붙어 오는 프레임, 앞에 쓰레기, `NEED_MORE` 대기와 대기 만료, `maxLength`

**실제 장비 (testkit, `ZZTMP_` 장비)**
- GENERIC TCP·UDP 에 power-meter 를 붙여 소켓으로 요청 → 응답 비교
- Value Simulation 값이 응답에 실리는지, 패킷 쓰기가 `PROTOCOL_WRITE` 이벤트·잠금 규칙을 따르는지
- 시스템 오류 4종, `delayMs`
- 정의를 고쳐 저장 → 연결 유지한 채 다음 요청부터 바뀌는지
- RCU RS 경로

**화면 (헤드리스 Chrome)**
- 목록·편집기 저장·충돌(409)·삭제 막힘, 장비 편집기의 정의 선택·params, 타겟 입력 보조

---

## 16. 작업 순서

1. **저장·화면** — 엔티티·API(컴파일 전 단계는 JSON 모양 검사만), 사이드바·목록 화면, 편집기 이름·저장·불러오기, 형식 이름 바꾸기
2. **엔진 코어** — 식·코덱·체크섬·컴파일·매칭·응답·인코딩·tests 실행기 + 단위 시험. 저장 API 에 컴파일·시험 연결
3. **장비 연결** — Netty decoder·handler, RCU RS, 장비 설정·검사·장비 편집기·일괄 등록, 주변 규칙 교체(9.3), 타겟 입력 보조
4. **정리** — PPD/PGD 삭제, 형식 설계서 갱신(v1 변환 절 삭제·미결 사항 정리), testkit 시나리오

1~2 동안 GENERIC·RCU 는 지금처럼 PPD/PGD 로 돈다. 3 에서 한 번에 바뀐다.

---

## 17. 구현 기록

**설계와 다르게 한 것**
- 서버 검사는 JSON 모양만 따로 보지 않고 처음부터 엔진 컴파일러로 거른다 (1단계 API 도 2단계에서 컴파일러로 바꿨다).
- 시험 사례는 스트림처럼 넣는다 — 잡음 뒤 다시 맞춤(thermo-controller "앞에 잡음")까지 시험 사례로 확인할 수 있게.
- thermo-controller 예시의 "앞에 잡음" 사례에 `data` 를 더했다. 사례는 앞 사례의 쓰기를 물려받으므로(11절) 그 전 사례가 쓴 26.5 가 남아 있었다.
- 패키지: 엔진은 `protocol`(Compiler·Definition·RxReader·TxWriter·Codec·Checksums·ProtocolEngine·DefinitionTests·DefinitionRegistry·expr),
  Netty 연결은 `io.netty.decoder.GenericStreamDecoder`·`udp.GenericDatagramDecoder`·`handler.GenericHandler`·`io.netty.protocol.ProtocolRuntime`.

**시험**
- JUnit `ProtocolEngineTest` — 예시 셋의 시험 사례 17건, CRC 카탈로그 check 값, 식, 컴파일 오류 위치, 스트림 대기·붙은 프레임, 쓰기
- testkit `s15_protocol_definition` — 실제 소켓 17건 (TCP·UDP·RCU RS, 쪼개진·붙은 프레임, 다시 맞춤, 저장 즉시 반영, 패킷 쓰기와 잠금, 타겟 목록 규칙, 삭제 막힘)
- 장비 설정 사례표 `device-settings-cases.json` — JUnit·JXA 31/31
- 화면(헤드리스 Chrome) — 목록·편집기 저장 흐름, Run tests, 장비 편집기 정의 카드, Simulation 타겟 보조, 일괄 등록

**2026-11-11 추가 — 비트 집합 항목, 편집기 표·셀렉터**
- 순차 타겟 항목에 `type: "BITS"`(형식 8.2). `Compiler.sequence` 가 비트 필드마다 타겟을 선언하고(`declare`),
  `ProtocolEngine.read/write/writable` 이 `SeqItem.isBits()·hasTargets()` 로 비트를 모으고 푼다. 여러 비트짜리 비트 타겟은 INTEGER.
  JUnit `bitSetItemsDeclareReadAndWriteEachBit`·`bitSetItemRejectsItsOwnTarget`.
- 편집기의 표는 모두 Tabulator + `CtsTable`(공통 표 규칙). 행 데이터는 doc 에서 매번 만들고(`tableSlot`·`refreshTables`),
  표에서 고치면 속성 패널을, 속성 패널에서 고치면 표의 행을 바로 다시 그린다.
- 열리는 목록은 모두 `CtsSelect`(Tom Select) — 선택지가 5개를 넘으면 검색칸. 표 칸은 `CtsSelect.cellEditor`.
- 타겟 항목 속성 패널에 타입별 형식 칸(인코딩·채움·정렬·소수·바이트 순서·scale 등)을 필드와 같은 `formatCells` 로 둔다.

**2026-11-11 추가 — 값은 선언에, 표현은 필드에 (형식 8.3)**
- 이름·패턴 타겟은 `value`(binary / integer / float)·access·doc 만 갖는다. 표현 속성을 적으면 컴파일 오류.
  값을 비우면 처음 실은 필드의 표현에서 추론(`Definition.valueOf`), 필드마다 다르면 경고. `TargetInfo.value`·`valueKind()`.
- 필드가 표현을 선언에서 가져오는 것(From target)은 순차 타겟만. 이름 타겟·BITS 의 비트를 표현 없이 실으면 컴파일 오류
  (전에는 BIT 타겟이 저장은 되고 응답할 때 "cannot be encoded on its own" 으로 실패했다).
- `target` 을 실은 필드는 `name` 을 생략할 수 있다. integer·binary 타겟에 소수 표현을 실으면 오류, TEXT·BYTES 에 실으면 오류(`Compiler.targetValues`).
- 예시 thermo-controller, 형식 문서·매뉴얼의 예를 바꿨다. JUnit `namedTargetsDeclareTheValueAndFieldsCarryTheRepresentation`·`aBitOfABitSetCannotBeSentOnItsOwn`.

**2026-11-11 추가 — BITS 의 비트 자리를 직접 (형식 6.7)**
- 비트 필드에 `bit`(시작 비트, 값의 맨 아래 = 0)·`bits`(폭, 기본 1). 순서는 자리와 무관하고, 빈 비트는 0. 겹침·범위 밖은 컴파일 오류,
  "합이 size × 8" 규칙은 없앴다. `bit` 없는 필드는 앞 필드 다음 자리(예전 문서 호환, bitOrder 는 이때만 뜻이 있다).
- 엔진: `FieldSpec.bitAt` 을 컴파일러가 정하고 `Codec.encodeBits/decodeBits` 는 그 자리만 본다. BITS size 는 1–8.
- 편집기: 비트 표 첫 칸이 Bit, 자리 순으로 보인다. 표를 고치면 모든 필드에 `bit` 를 적고(pinBits) bitOrder 를 지운다. 새 필드는 비어 있는 가장 낮은 비트에.
- 예시 thermo-controller·AR56_claude 를 `bit` 로 바꾸고 채움 필드(`{bits:n, const:0}`)를 지웠다. JUnit `bitFieldsSitAtTheirOwnBitNotTheirOrder`.

**2026-11-11 추가 — 숫자형은 형식 + 속성 (형식 6.1)**
- 필드의 숫자 `type` 은 `UINT` / `INT` / `FLOAT` 만. 바이트 수는 `size`(정수 1·2·3·4·8, 실수 4·8), 순서는 `byteOrder`(big·little·word-swap·byte-swap,
  2·3 바이트는 big·little 만). CTS data type 코드(`UI16AB` 등)는 필드에서 받지 않는다 — 파라미터 `type` 만 CTS 코드다.
- 엔진: `FieldSpec.num`·`byteOrder`·`isReal()`·`isSigned()`·`numText()`. `Codec` 이 DataType 없이 크기·순서로 인코딩한다(24비트·UINT 8 가능).
  홀수 바이트의 word-swap 이 바이트를 잃던 문제는 그 조합을 컴파일 오류로 막아 없앴다.
- 편집기: Type 목록의 Number 는 세 줄. Size·Byte order 는 속성 패널의 선택칸(크기에 맞는 순서만). 표에는 `UINT 2`, `INT 4 word-swap`.
- 예시 3종·형식 문서·매뉴얼·JUnit 의 코드를 바꾸고, 저장된 AR56(35)·AR56_claude(36)도 바꿨다. JUnit `numbersAreAFormWithSizeAndByteOrder`.

**2026-11-11 추가 — 파라미터는 값의 종류와 범위만 (형식 11)**
- `params` 항목은 `value`(integer 기본 / float)·`default`·`min`·`max`·`doc`. 예전 `type`(CTS 코드)은 저장 오류.
  integer 면 default·min·max 정수, `min ≤ max`, default 가 범위 안 — `Compiler.params`·편집기 검사가 같은 규칙.
- 장비 설정 검사(`DeviceSettingsValidator.paramProblem`·`cts-device-form.js`)는 min·max 만 본다. 없으면 범위 검사가 없으므로,
  예시·저장된 정의를 옮길 때 CTS 코드가 뜻하던 범위(UI8 → 0–255)를 min·max 로 적었다.
- 정의 문서에서 CTS data type 코드가 모두 빠졌다. 편집기는 cts-datatypes.js 를 더 읽지 않는다. JUnit `parametersDeclareAValueAndARange`.

**2026-11-11 추가 — 타입 안에서 타입을 쓰지 않는다 (형식 6.10)**
- 타입 정의의 필드(인라인 구조체 안쪽 포함)에 다른 타입 이름을 적으면 저장 오류(`Compiler.fieldSpec`, typeStack 이 비어 있지 않을 때). 편집기는 그 자리의 Type 목록에서 타입을 뺀다.
- 모든 타입을 쓰는 곳과 상관없이 한 번 따로 컴파일한다(`typeDefinitions`) — 안 쓰는 타입의 문제도 보인다. 같은 문제는 `dedupe` 로 한 번만.
- JUnit `aTypeCannotUseAnotherType`.

**2026-11-11 추가 — 구조체는 한 단계(structures), 본문 변환 (형식 6.8, 5.10)**
- `types` → `structures`, 필드는 `"structure": 이름`(`type` 은 표현 형식만). 구조체 안의 구조체·인라인 `fields`·예전 `types` 는 저장 오류.
  편집기: 차례 Structures, Type 목록의 Structure 묶음(값 `struct:이름`), Edit fields·중첩 보기 제거, 구조체 화면에 Used by.
- 메시지 `bodyTransform`: `RxReader.readTransformedBody`(bodySize, 없으면 받은 끝 − 고정 푸터 `MessageSpec.footerWidth`),
  `TxWriter` 는 본문 영역을 변환한 구조체처럼 덩어리로 인코딩. 영역 `body` 의 범위는 전송 바이트.
- JUnit `structuresAreOneLevelAndUsedByName`, `aBodyTransformWrapsTheWholeBody`(YD/T 1363 꼴 — 변환한 본문 안의 레코드 배열).

**2026-11-12 — 형식 문서를 편집기 사용 설명서 초안으로 전환**

`PROTOCOL_DEFINITION_FORMAT.md` 는 사용자용 설명서 초안이다(존댓말, 구현·개발 내용 없음, 화면 칸 이름 + JSON 키).
장 번호를 1 부터 매기면서 예전 장 N(1–14)은 N+1 이 되었고(1 시작하기 추가, PPD/PGD 장 삭제, 16 지원하지 않는 것, 17 문제 해결),
코드 주석과 이 문서의 "형식 N.M" 참조도 모두 새 번호로 고쳤다. 형식 문서에서 뺀 개발 기록은 여기로 옮긴다.

- 서버와 편집기 검사의 차이(설명서는 편집기 기준으로 적었다): BYTES `size` 없음 — 서버는 받음(받을 때 끝까지·보낼 때 값 길이), 편집기는 오류.
  `rw` 아닌 Target 에 `write` — 서버 경고·실행 때 건너뜀, 편집기 오류. respond `reply` 없음 — 서버는 `none`, 편집기 오류.
  비트 필드에 이름·Target·Const 가 모두 없음 — 서버는 rx 에서 받음, 편집기 오류. 사용자 CRC 의 `init`·`xorout` 기본 0·`width` 기본 16, SUM `width` 기본 8 — 편집기는 필수.
- 아직 없는 것: 바이트 스터핑 `escape`(경고 후 무시), 체크섬 `over: "value"`(읽기만), respond `name` 으로 가리키는 명령별 오류·응답 변형(체크섬만 틀리게·잘라서·두 번).
- 미정: TEXTNUM `point: false` — 숨은 소수 자리는 `scale` 과 같은 계산이라 두 표기가 겹친다. 없애고 `scale` 로만 적는 안이 있다 (형식 6.4).

**2026-11-12 — From target 삭제**
- 필드 Type 의 Other › From target(타입을 비우고 순차 타겟 선언의 표현을 가져오기)을 없앴다. 순차 타겟도 다른 타겟처럼 필드에 Type 과 인코딩을 적어 싣는다.
- `Compiler.fieldSpec` 의 `type == null && target != null` 분기·`copyEncoding`·`declared` 맵, JS 모델의 `kindOf` 'target' 종류·`KIND_ATTRS.target`·`declaredItem` 을 지웠다. 타입 없이 target 만 적은 필드는 "needs a type" 오류.
- 형식 문서 6.11 절을 지우고 6.12·6.13 을 6.11·6.12 로 당겼다.

**2026-11-13 — power-meter 예시 확장**
- 코일 10(FC01 읽기·FC05 쓰기), 접점 입력 10(FC02), 보유 레지스터 10(FC03·FC06·FC16)으로 넓혔다. 보유 레지스터는 측정값 8(읽기 전용, 전력량 7~8) + CT비·경보전력(rw).
- FC01·02 응답은 바이트 배열의 Value 로 비트 8개를 묶는다(`data(args.prefix + text(번지 + 1))`). Write 의 Target 은 고정 이름이라 FC05 는 코일을 slotSize 1 순차 Target 으로 두고 map/bytes 쓰기.
- 시험 25건의 기대 바이트는 엔진과 별도로 계산(Modbus 동작·CRC)해 대조했다. 형식 문서 15장 예시 A, 매뉴얼, testkit s15(전력량 7~8, 메시지를 이름으로)도 같이 고쳤다.

**2026-11-13 — XGT FEnet 예시와 식·쓰기 확장**
- 예시 `xgt-fenet`(LS ELECTRIC XGT 전용 프로토콜, XGK): 출처는 XGL-EFMTB 사용설명서 V3.62 7.1절(프레임)·9.3절(오류 코드). 저장 id 58 로 사용자 DB 에도 넣었다.
  디바이스 P M K F T C Z L N D R 을 순차 Target `memory` 하나(slotSize 2, 워드 UINT 2 little)에 영역마다 `at` = k×65536 으로 놓았다(영역 번호 = `find($AREAS, 글자)`).
  워드 수: P K F T C Z L N 32, M R 128, D 512 (Target 1024 + 상태 플래그 2). 비트·바이트·DWord·LWord·연속은 바이트 위치 b0 와 길이 L 로 걸친 워드를 `read` 해 `substr` 로 자른다.
  쓰기는 걸친 워드를 읽어 고친 뒤(`substr` + 새 바이트 + `substr`, 비트는 `pack(unpack(…) & ~mask | v)`) 통째로 쓴다 — Repeat(`count: body.blockCount`).
  읽을 수 없는 요청(XGI 꼴 `%MX0.1`, `%ZR…`, 블록 수·타입 오류)은 마지막 rx `other` 가 받아 NAK. T·C 의 비트(접점)는 현재값 워드와 다른 메모리라 NAK 03 으로 둔다.
  매뉴얼에 없는 오류 코드 선택: 데이터 크기가 타입과 다름·읽기 전용(F) 쓰기 → 04. 헤더 BCC 는 파라미터 `checkBcc`(기본 0 = 보지 않음).
- 정의 JSON 은 `tools/gen_xgt_fenet.py` 로 만든다(식 조립·기준 모델 RefPlc 로 시험 35건의 기대 바이트 계산). 영역 크기는 스크립트의 `AREAS` 표.
  `python3 tools/gen_xgt_fenet.py MK119CommunicationTestSuite/src/main/resources/static/protocol-examples/xgt-fenet.json`
- 식 함수 추가(`Parser.FUNCTIONS`, `Expr.Call`): `find(t, s)`, `pack(x, size[, order])`, `unpack(b[, order])`, `any(list, cond)`, `all(list, cond)`; `substr` 이 바이트도 자른다.
  `any`/`all` 은 둘째 인자를 원소마다 `Expr.Bound`(이름 하나를 값에 묶는 Scope)로 다시 계산한다(`it`).
- Write 반복: `WriteSpec.count`(JSON `count`, 편집기 Repeat 칸). `ProtocolEngine.write` 가 i 마다 `Expr.Bound(rx, "i", k)` 로 식을 계산하고 바로 쓴다(뒤 번 `read` 가 앞 번 결과를 본다).
- 시험: JUnit `textBytesAndListFunctions`, `xgtFenetExampleTestsPass`; testkit `s25_xgt_fenet`(TCP·UDP 소켓 17건 — REST·Simulation 값 읽기, 패킷 쓰기·잠금, 쪼개진·붙은 프레임, NAK).
