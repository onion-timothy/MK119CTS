# Protocol Definition 편집기 사용 설명서 (초안)

이 설명서는 CTS 가 시뮬레이션하는 장비의 통신 규약을 Protocol Definition(이하 정의)으로 작성하는 방법을 설명합니다.
1장에서 화면과 작업 순서를 소개하고, 2장과 3장에서 문서의 구성과 이름 규칙을 설명합니다.
4장부터 9장까지는 편집기 차례(General, Targets, Structures, Frames, Messages)의 순서를 따르고, 10장부터 12장까지는 여러 화면에서 함께 쓰는 필드, 타입, 식을 설명합니다.
13장부터 16장까지는 시험 사례, 저장할 때의 검사, Device 연결을 다룹니다. 17장에는 완성된 예시가 있고, 19장에는 문제가 생겼을 때 확인할 내용을 모았습니다.

각 항목은 편집기 화면에 보이는 이름과 JSON 키를 함께 적었습니다. 편집기에서 무엇을 고치든 결국 JSON 문서 하나가 바뀌며,
차례 맨 아래의 **JSON** 화면에서 그 문서를 직접 확인할 수 있습니다.

---

## 1. 시작하기

### 1.1 Protocol Definition 이란

Generic Device 에는 정해진 프로토콜이 없습니다. 장비가 어떤 요청을 받고 무엇으로 응답하는지를 정의에 적어 두면, Generic Device 는 그 내용대로 동작합니다.

```
상위 시스템 ──요청──▶ CTS Device ──응답──▶ 상위 시스템
                       │
                       ├ 받은 바이트를 정의의 받는 메시지(rx)와 맞춰 봅니다
                       ├ 응답 규칙에 따라 보낼 메시지(tx)를 고릅니다
                       └ 보낼 메시지의 값을 Server Data(Target)에서 읽어 바이트로 만듭니다
```

정의 하나를 여러 Device 가 함께 쓸 수 있습니다.

**할 수 있는 것**

- 요청 하나에 응답 하나로 동작하는 장비. 조건에 따라 응답하지 않을 수도 있습니다.
- 이진 프로토콜과 텍스트(ASCII) 프로토콜, TCP 와 UDP
- 헤더·본문·푸터로 나뉜 패킷, 길이 필드, 체크섬(CRC, 바이트 합, XOR 등)
- 조건에 따라 있거나 없는 필드, 반복되는 필드
- 레지스터 맵처럼 번지로 값을 읽고 쓰기, 이름으로 값 읽기
- 받은 값으로 Server Data 를 바꾸는 설정 명령
- 요청 내용에 따라 정상 응답·예외 응답·무응답 중 하나를 고르기, 응답 지연

**할 수 없는 것**

- 장비가 먼저 보내는 통신(이벤트 통보, 주기 송신)
- 여러 프레임으로 나뉘어 오가는 응답
- 로그인처럼 앞선 요청을 기억해야 하는 세션 상태
- 압축, 암호화, 바이트 스터핑
- 글자 값을 Server Data 에 저장하기

자세한 제약은 18장에 정리했습니다.

### 1.2 작업 순서

1. 사이드바의 **Protocol Definitions** 에서 정의를 새로 만듭니다. 빈 문서, 예시(17장), JSON 파일 중 하나로 시작할 수 있습니다.
2. 편집기 차례의 순서대로 일반 설정과 파라미터(4장), Target(5장), 프레임과 체크섬(7장, 8장), 메시지(9장)를 작성합니다. 필드와 타입은 10장과 11장에서 설명합니다.
3. 시험 사례(13장)를 작성하고 **Run tests** 로 실행해 봅니다.
4. **Save** 로 저장합니다. 오류가 남아 있거나 시험 사례가 하나라도 실패하면 저장되지 않습니다(14장).
5. Device 편집기에서 Generic Device 에 정의를 지정하고 파라미터 값을 입력합니다(15장).
6. Value Simulation 이나 Server Data 화면에서 Target 에 값을 넣으면 그 값이 응답에 실립니다.

### 1.3 화면

**Protocol Definitions 목록**

| 작업 | 방법 |
| --- | --- |
| 새로 만들기 | 표 아래의 **Add** 를 누르고 Blank definition, Import a JSON file, 예시 중 하나를 고릅니다 |
| 편집 | 오른쪽 요약의 **Edit** 를 누르거나 표의 줄을 두 번 누릅니다 |
| 복제 | **Duplicate** 를 누릅니다. 새 정의의 이름은 `원래이름 copy` 입니다 |
| 파일로 내려받기 | 하나는 **Download**, 여러 개는 표에서 체크한 뒤 **Export** 를 누릅니다 |
| 삭제 | **Delete** 를 누릅니다. 이 정의를 쓰는 Device 가 있으면 삭제되지 않고, 해당 Device 이름을 알려 줍니다 |

**편집기**

| 위치 | 내용 |
| --- | --- |
| 차례 **Document** | 문서의 차례입니다. General, Targets, Structures, Frames, Messages, Tests, JSON 이 있고, 누르면 가운데에 열립니다. Targets, Structures, Frames, Messages 아래에는 만든 항목이 나열됩니다. 문제가 있는 항목에는 빨간 점(오류)이나 주황 점(경고)이 붙습니다 |
| 가운데 | 차례에서 고른 페이지입니다. 성격이 비슷한 설정은 한 페이지에 카드로 모여 있습니다(아래 표) |
| 속성 창 | 표의 줄을 누르면 열리는 창입니다. 그 줄(필드, 응답 규칙, Target 항목, 파라미터, 상수)의 속성을 모두 보여 줍니다 |
| 위 막대 **Problems** | 문서 전체의 상태를 `✓ No problems`, `⚠ n warnings`, `✕ n errors` 로 보여 줍니다. 누르면 목록이 열리고, 항목을 누르면 해당 위치로 이동합니다. 표의 줄에 있는 문제이면 그 줄의 속성 창이 함께 열립니다 |
| 위 막대 **Download** | 지금 문서를 JSON 파일로 내려받습니다. 오류가 있으면 한 번 확인한 뒤 내려받습니다 |
| 위 막대 **Cancel**, **Save** | 목록으로 돌아가거나 저장합니다 |

| 차례 | 페이지에 있는 것 |
| --- | --- |
| General | Document(이름, 설명), Defaults(문서 전체의 기본값), Parameters, Constants 카드(4장) |
| Targets | Target 묶음 목록. 묶음을 고르면 설정 카드와 Items 표가 열립니다(5장) |
| Structures | 구조체 목록. 구조체를 고르면 설정 카드와 Fields 표가 열립니다(6장) |
| Frames | Framing, Frames(프레임 목록), Checksums 카드(7장, 8장). 프레임을 고르면 설정 카드와 Fields 표가 열립니다 |
| Messages | 메시지 목록. 메시지를 고르면 설정 카드와 Fields 표가 열리고, 받는 메시지에는 Respond 표가 더 있습니다(9장) |
| Tests | 시험 사례(13장) |
| JSON | 문서 원문 |

설정 카드의 칸은 그 자리에서 바로 고칩니다. 표는 줄마다 요약만 보여 주며, 줄을 누르면 열리는 속성 창에서 고칩니다.
다만 Targets, Structures, Frames, Messages 의 목록 표는 줄을 누르면 그 항목의 페이지로 이동합니다.

- **속성 창**: 고친 내용은 바로 문서에 들어가므로 창 뒤의 표와 Problems 도 함께 바뀝니다. **OK** 는 고친 내용을 그대로 두고 닫고, **Cancel**(또는 ×, Esc)은 창을 연 때의 상태로 되돌립니다.
  창 머리의 ↑, ↓(Move up, Move down)로 줄의 순서를 바꾸고, 휴지통 단추로 그 줄을 지웁니다. ↑, ↓ 는 순서가 있는 목록(필드, 응답 규칙, Target 항목)에만 있습니다.
- **줄 더하기**: 카드 머리의 **+ Field**, **+ Rule** 같은 단추로 줄을 더하면 새 줄의 속성 창이 바로 열립니다. 이 창에서 Cancel 하면 더한 줄도 없어집니다.
- **줄 지우기**: 표의 줄 오른쪽 끝의 × 를 눌러도 지울 수 있습니다.
- **쪽 나눔**: 표에 줄이 많으면 표가 여러 쪽으로 나뉩니다.
- **선택 목록**: 선택지가 6개 이상이면 목록 맨 위에 검색 칸이 나타납니다. 바로 입력하면 목록이 걸러집니다.
- **JSON 화면**: 문서 원문을 직접 고칠 수 있습니다. 고친 뒤 **Apply** 를 눌러야 반영됩니다. **Format** 은 들여쓰기를 정리하고, **Copy** 는 원문을 복사합니다.
- **저장하지 않은 내용**: 브라우저에 남아 있습니다. 같은 정의를 다시 열면 이어서 작업할지 묻습니다.
- **동시 편집**: 다른 창이나 다른 사용자가 같은 정의를 먼저 저장했다면 나중에 저장하는 쪽은 거부됩니다. 정의를 다시 연 뒤 고쳐서 저장합니다.
- **좁은 화면**: 넓은 가로 화면에서는 차례가 왼쪽에 늘 보입니다. 세로 화면이나 좁은 창에서는 차례가 숨고, 위 막대의 **Document** 단추로 엽니다.
  이 단추에는 지금 보는 페이지 이름이 표시되며, 항목을 고르거나 차례 바깥을 누르면(또는 Esc) 차례가 닫힙니다.

### 1.4 용어

| 용어 | 뜻 |
| --- | --- |
| 정의 (definition) | Protocol Definition 문서 하나 |
| 메시지 (message) | 패킷의 한 종류. 장비가 받는 메시지는 **rx**, 장비가 보내는 메시지는 **tx** 입니다 |
| 영역 | 메시지를 이루는 세 부분인 `header`(헤더), `body`(본문), `footer`(푸터) |
| 필드 (field) | 영역 안의 값 하나. 이름, 타입, 길이를 가집니다 |
| 타입 (type) | 필드의 표현 형식. `UINT`, `TEXT`, `BITS` 등이 있습니다(11장) |
| 구조체 (structure) | 이름을 붙여 둔 필드 묶음. 필드에서 이름으로 불러 씁니다(6장) |
| 프레임 (frame) | 패킷들이 공유하는 헤더와 푸터를 묶어 둔 것. 여러 메시지에서 재사용합니다(7장) |
| 응답 규칙 (respond) | 받는 메시지에 딸린 규칙 목록. 조건에 따라 어떤 메시지로 응답할지 정합니다 |
| Target | Server Data 의 값 하나를 가리키는 이름. Value Simulation, REST API, Test Plan 에서 쓰는 Target 과 같습니다 |
| 물리값과 raw | 물리값은 Server Data 에 있는 값(예: 220.1), raw 는 패킷에 실리는 정수(예: 2201)입니다. Scale 이 둘을 이어 줍니다(10.1절) |
| 파라미터 (parameter) | Device 마다 다른 값. 국번이나 Unit ID 같은 값입니다(4.2절) |
| 식 (expression) | `header.command == 1` 처럼 조건이나 값을 계산하는 한 줄(12장) |

---

## 2. 문서 구성

정의는 JSON 문서 하나입니다. JSON 이므로 주석은 쓸 수 없고, 대신 어느 항목에나 설명(`doc`, 화면의 **Description**)을 달 수 있습니다. 설명은 동작에 영향을 주지 않습니다.

```json
{
  "format": "cts-protocol",
  "version": 1,
  "name": "power-meter",
  "doc": "…",
  "defaults":  { "encoding": "ascii", "onOverflow": "error" },
  "params":    { "unitId": { "value": "integer", "default": 1, "min": 0, "max": 255 } },
  "consts":    { "READ": 16 },
  "checksums": { "crc": { "algorithm": "CRC-16/MODBUS", "covers": { "from": "header", "to": "body" } } },
  "framing":   { "maxLength": 256, "idleFlushMs": 50 },
  "targets":   { "holding": { "kind": "sequence", "items": [ … ] } },
  "structures": { "point": { "fields": [ … ] } },
  "frames":    { "rtu": { "header": [ … ], "footer": [ … ] } },
  "messages":  [ { "name": "readHolding", "direction": "rx", … }, { "name": "readReply", "direction": "tx", … } ],
  "onUnmatched": { "reply": "none" },
  "tests":     [ { "name": "…", "send": "01 03 …", "expect": "01 03 …" } ]
}
```

| 화면 (차례 → 카드) | JSON 키 | 필수 | 내용 | 참고 |
| --- | --- | --- | --- | --- |
| General → Document | `format`, `version` | ✓ | 항상 `"cts-protocol"`, `1` 입니다. 편집기가 채웁니다 | |
| General → Document | `name` | ✓ | 정의 이름. 다른 정의와 겹칠 수 없습니다 | 4.1절 |
| General → Document | `doc` | | 정의 설명 | 4.1절 |
| General → Defaults | `defaults` | | 문서 전체의 기본값. 글자 인코딩과 값이 범위를 넘을 때의 처리 두 가지입니다 | 4.1절 |
| General → Defaults | `onUnmatched` | | 어느 받는 메시지에도 맞지 않는 바이트의 처리 | 4.1절 |
| General → Parameters | `params` | | Device 마다 다른 값(국번 등)의 종류와 범위 | 4.2절 |
| General → Constants | `consts` | | 프로토콜 상수. 값은 수 또는 글자입니다 | 4.3절 |
| Targets | `targets` | | Server Data 와 연결되는 Target 선언 | 5장 |
| Structures | `structures` | | 필드 묶음(구조체) | 6장 |
| Frames → Framing | `framing` | | 프레임 경계를 찾는 데 쓰는 보조 설정 | 7.3절 |
| Frames → Frames | `frames` | | 패킷들이 공유하는 헤더와 푸터 | 7장 |
| Frames → Checksums | `checksums` | | 체크섬 정의(알고리즘과 범위) | 8장 |
| Messages | `messages` | ✓ | 받는 메시지(rx)와 보내는 메시지(tx). 받는 메시지가 하나 이상 있어야 하며, 받는 메시지의 순서에는 의미가 있습니다 | 9장 |
| Tests | `tests` | | 시험 사례. 저장할 때 모두 실행됩니다 | 13장 |

---

## 3. 이름과 경로

정의 안의 이름은 두 종류입니다.

- **식별자**: 메시지, 프레임, 구조체, 체크섬, Target 묶음, 필드, 파라미터, 상수, 응답 규칙의 Args 의 이름입니다. 글자(한글 포함)나 `_` 로 시작하고, 글자·숫자·`_` 만 쓸 수 있습니다. `·`, `-`, `$`, 공백 같은 기호는 쓸 수 없습니다.
- **Target 이름**: Server Data 의 키입니다. 자유로운 문자열이어서 `전압 A/B, #1` 같은 이름도 쓸 수 있습니다.
  필드 이름은 다른 필드나 값의 식에서 그 필드를 참조할 때 쓰는 식별자인데, Target 이름은 식별자 규칙을 따르지 않으므로 Target 이름을 필드 이름으로 그대로 쓸 수 없는 경우가 많습니다.
  식에서 참조할 일이 없으면 Target 을 실은 필드의 이름은 비워 두면 됩니다.

메시지는 `header`, `body`, `footer` 세 영역으로 나뉩니다. 식에서는 아래와 같은 경로로 필드를 가리킵니다.

| 경로 | 뜻 |
| --- | --- |
| `header.unitId` | 이 메시지 헤더의 필드 |
| `body.values[2]` | 배열의 세 번째 원소 |
| `body.flags.run` | 구조체나 비트 묶음 안의 필드 |
| `quantity` | 이름만 쓰면 가까운 범위부터 찾습니다. 같은 구조체, 같은 영역, 다른 영역 순서입니다 |
| `req.body.start` | 보내는 메시지 안에서, 이 응답을 일으킨 받는 메시지의 필드 |
| `args.code` | 응답 규칙의 Args for the reply 로 넘긴 값 |
| `$unitId` | 파라미터 또는 상수. 두 곳에서 같은 이름을 쓸 수 없습니다 |
| `i` | 반복 필드 안에서 원소 번호(0부터). Write to Server Data 의 Repeat 안에서는 되풀이 번호(5.6절) |
| `it` | `any()`, `all()` 의 둘째 인자 안에서 지금 보는 원소. `it.area` 처럼 구조체 안쪽도 부릅니다(12.4절) |
| `this` | Expect 안에서 지금 필드의 값 |

- 같은 영역, 같은 구조체 안에서는 필드 이름이 겹칠 수 없습니다. 영역이 다르면 경로로 구분되므로 겹쳐도 됩니다.
- 이름을 비워도 되는 필드는 Const 필드, PAD, 체크섬 자리, Target 을 실은 필드입니다. 이름이 없는 필드는 식에서 부를 수 없습니다.
- 필드와 비트 필드의 이름으로는 `header`, `body`, `footer`, `req`, `args`, `i`, `this`, `it`, `true`, `false`, `in` 을 쓸 수 없습니다(저장 오류).
  식에서 이름만 쓰면 필드가 아니라 영역, 요청, 원소 번호, 참·거짓 같은 다른 뜻으로 읽히기 때문입니다. `data`, `len` 같은 함수 이름은 필드 이름으로 써도 됩니다.
- 메시지 이름으로는 `none`, `echo` 를 쓸 수 없습니다. 응답 규칙의 Reply 가 따로 쓰는 값입니다.
- 파라미터와 상수는 식에서 항상 `$` 를 붙여 부르므로 필드나 함수와 이름이 같아도 됩니다.
- 체크섬의 Covers 에 적은 경로는 끝부분이 맞는 경로로도 찾습니다(`address` 를 적으면 `header.address`). 같은 이름이 여러 영역에 있으면 영역부터 적습니다.
- 보내는 메시지의 Set frame fields 에는 `header.function` 처럼 `영역.필드` 까지만 적을 수 있습니다. 구조체 안쪽 필드는 가리킬 수 없습니다.

---

## 4. 일반 설정과 파라미터 (General)

차례의 **General** 을 누르면 Document, Defaults, Parameters, Constants 네 카드가 한 페이지에 열립니다.
Document 와 Defaults 카드의 칸은 바로 고치고, Parameters 와 Constants 표는 줄을 눌러 속성 창에서 고칩니다.

### 4.1 일반 설정

| 카드 | 화면 | JSON | 내용 |
| --- | --- | --- | --- |
| Document | Name | `name` | 정의 이름. 다른 정의와 겹칠 수 없습니다. Device 편집기에서는 목록에서 고르고, 엑셀 일괄 등록에서는 이 이름을 적습니다(15장) |
| Document | Format | `format`, `version` | `cts-protocol · version 1` 로 정해져 있으며 고칠 수 없습니다 |
| Document | Description | `doc` | 정의 설명 |
| Defaults | Text encoding | `defaults.encoding` | 글자 인코딩의 기본값. 기본은 `ascii` 입니다(11.3절) |
| Defaults | When a value overflows | `defaults.onOverflow` | 값이 범위를 넘을 때의 기본 처리. `error (default) — no reply`, `clamp to the type range`, `wrap around` 중에서 고릅니다(10.1절) |
| Defaults | Frames no message matches | `onUnmatched.reply` | 어느 받는 메시지에도 맞지 않는 바이트의 처리. `none (default) — no reply`, `echo the received bytes`, `reply with 메시지 이름` 중에서 고릅니다(9.3절) |

Text encoding 과 When a value overflows 는 문서 전체에 적용됩니다. 필드에 같은 속성(Encoding, On overflow)을 적으면 필드의 값이 우선합니다.

### 4.2 파라미터 (Parameters)

```json
"params": { "unitId": { "value": "integer", "default": 1, "min": 1, "max": 247, "doc": "장비 번호" } }
```

파라미터는 국번이나 Unit ID 처럼 Device 마다 다른 **값**입니다. 패킷에 실리는 모양이 아니므로 크기나 바이트 순서 같은 타입은 없습니다.
패킷에 실을 때의 모양은 그 값을 쓰는 필드가 정합니다(예: `{ "name": "unitId", "type": "UINT", "size": 1, "value": "$unitId" }`).

Parameters 카드의 **+ Parameter** 로 더하고, 표의 줄을 누르면 열리는 속성 창에서 고칩니다.

| 화면 (속성 창) | JSON | 기본 | 내용 |
| --- | --- | --- | --- |
| Name | (키) | | 식에서 `$unitId` 처럼 부릅니다. Constants 와 같은 이름을 쓸 수 없습니다 |
| Value | `value` | `integer` | `integer`(정수) 또는 `float`(실수). integer 이면 Device 에서 정수만 입력할 수 있고, 식에서 비트 연산(`&`, `\|`, `<<`)을 쓸 수 있습니다 |
| Default | `default` | 없음 | Device 에서 값을 비워 두었을 때 쓰는 값. 없으면 Device 에서 반드시 입력해야 합니다 |
| Min, Max | `min`, `max` | 없음 | 허용 범위(양쪽 끝 포함). **비워 두면 범위를 검사하지 않으므로**, 국번처럼 범위가 정해진 값은 꼭 적습니다 |
| Description | `doc` | | Device 편집기의 입력 칸에 표시되는 설명 |

아래 규칙을 어기면 저장되지 않습니다.

- Value 가 `integer` 이면 Default, Min, Max 도 정수여야 합니다.
- Min 은 Max 보다 클 수 없습니다.
- Default 는 Min 과 Max 사이에 있어야 합니다.

### 4.3 상수 (Constants)

상수는 프로토콜의 고정된 숫자에 이름을 붙여 둔 것입니다. 식에서 `$READ` 처럼 부르며, 값은 수 또는 글자입니다.
Constants 카드의 **+ Constant** 로 더하고, 줄을 누르면 열리는 속성 창에서 Name 과 Value 를 고칩니다. 파라미터와 같은 이름은 쓸 수 없습니다.

```json
"consts": { "READ": 16, "WRITE": 32, "ACK": 6, "NAK": 21 }
```

---

## 5. Target 과 Server Data

### 5.1 Server Data 와 Target 묶음

Device 마다 Server Data 가 하나 있으며, Target 이름(글자)마다 물리값(수) 하나를 담습니다.
Value Simulation, Server Data 화면, REST API, Test Plan 이 Server Data 에 값을 쓰고, 패킷이 값을 읽거나 씁니다. Target 이름은 **글자 그대로** 비교하므로 대소문자와 공백을 구분합니다.

차례의 **Targets** 에서 **+ Sequence**, **+ Named**, **+ Pattern** 으로 Target 묶음을 만듭니다. 묶음의 Kind(종류)는 나중에 바꿀 수도 있습니다.
묶음을 열면 위쪽 설정 카드에서 Name, Kind 와 종류별 설정을 바로 고치고, 아래 Items 표에서 항목을 관리합니다. 항목은 표의 줄을 눌러 속성 창에서 고칩니다(패턴 Target 에는 Items 표가 없습니다).

| Kind | 값에 접근하는 방법 | 쓰는 경우 |
| --- | --- | --- |
| `sequence — by address` | 번지 | 레지스터 맵(5.2절) |
| `named — by name` | 이름 | 명령마다 정해진 값(5.3절) |
| `pattern — built keys` | 식으로 만든 이름 | 채널 번호 등으로 이름을 만드는 경우(5.4절) |

### 5.2 순차 Target (sequence) — 번지로 접근

레지스터 맵처럼 번지가 이어진 영역입니다.

```json
"holding": {
  "kind": "sequence", "base": 0, "slotSize": 2, "unmapped": "error",
  "items": [
    { "target": "전압1",  "type": "UINT", "size": 2, "scale": 0.1 },
    { "target": "전력량", "type": "UINT", "size": 4, "scale": 0.1 },
    { "skip": 3 },
    { "at": 100, "target": "설정값", "type": "INT", "size": 2, "access": "rw" },
    { "type": "TEXT", "size": 16, "const": "PM-3000" }
  ]
}
```

| 화면 | JSON | 내용 |
| --- | --- | --- |
| First address | `base` | 첫 항목의 번지(기본 0) |
| Slot size | `slotSize` | 칸 하나의 바이트 수(Modbus 레지스터는 2, 바이트 영역은 1) |
| Unmapped addresses | `unmapped` | 항목이 없는 번지의 처리. `error (default)` 이면 `fits` 가 거짓이 되고, `read as zero` 이면 0 으로 채웁니다 |
| Item | `target` / `const` / `skip` / `"type": "BITS"` | 항목의 종류. Target 값, 고정 내용, 빈 칸, 비트 집합(아래) 중 하나입니다 |
| Target, Const | `target`, `const` | 그 항목의 Target 이름 또는 고정값 |
| Slots | `skip` | 빈 칸 항목의 칸 수 |
| Type, Size 와 형식 칸 | `type`, `size`, `scale` … | 인코딩(10장, 11장) |
| At address | `at` | 이 항목의 번지를 직접 지정합니다. 다음 항목은 이 번지에서 이어집니다 |
| Access | `access` | `read only`(`r`, 기본) 또는 `read and write`(`rw`). 패킷으로 값을 쓸 수 있는지를 정합니다. Value Simulation 은 Access 와 관계없이 값을 씁니다 |

Items 표에는 번호, Address(그 항목이 차지하는 번지, 예: `9–10`), Target, Type, Scale, Access 가 표시됩니다.
빈 칸, 고정 내용, 비트 집합 항목은 Target 칸에 `skip 3 slots`, `const …`, 비트 표시로 나타납니다. 줄을 누르면 속성 창에 위의 칸이 모두 열립니다. 카드 머리의 **+ Target**, **+ Bits**, **+ Const**, **+ Skip** 으로 항목을 더합니다.

- 항목은 (타입의 바이트 수 ÷ Slot size)를 올림한 만큼 칸을 차지합니다. 4바이트 UINT 는 두 칸입니다.
- `read(map, address, count)` 는 번지 `address` 부터 `count` 칸을 각 항목의 타입대로 인코딩해 이어 붙입니다.
- 읽으려는 범위가 여러 칸짜리 항목의 **중간에서 시작하거나 끝나면** `fits` 가 거짓입니다(17장 예시 A 에서 전력량의 절반만 읽으면 예외 02 로 응답합니다).
- 항목 하나는 Server Data 의 Target 하나입니다. 32비트 값도 Target 하나에 물리값 하나로 저장되며, 칸 단위로 나누어 저장하지 않습니다. 예외는 아래의 비트 집합입니다.

**비트 집합 항목** (Item 을 bits 로 고른 항목, 순차 Target 에서만) 은 상태 레지스터처럼 한 칸의 비트마다 다른 값이 있을 때 씁니다.

```json
{ "type": "BITS", "size": 2, "access": "rw",
  "fields": [ { "bit": 0, "target": "운전" },
              { "bit": 1, "target": "경보" },
              { "bit": 2, "bits": 3, "target": "모드" } ] }
```

- 모양은 11.7절의 BITS 필드와 같습니다. 비트 필드마다 Bit(가장 아래 비트가 0)로 자리를 정하고, 비어 있는 비트는 0 입니다.
- 항목 자체에는 Target 이 없습니다. 비트 필드마다 Target(Server Data 의 Target 하나씩)이나 Const 를 두며, 둘 다 없으면 0 입니다.
- `read` 는 비트 Target 들의 값(반올림)을 모아 인코딩하고, Write 는 받은 값을 풀어서 비트 Target 마다 씁니다. Access 는 모든 비트 Target 에 함께 적용됩니다.
- 이름 Target 과 패턴 Target 에는 쓸 수 없습니다.

### 5.3 이름 Target (named) — 이름으로 접근

이름 Target 은 **값의 종류만 선언하고, 패킷에서의 모양은 그 값을 싣는 메시지 필드가 정합니다.**
그래서 같은 값을 메시지마다 다른 모양으로 보낼 수 있습니다. 예를 들어 상태 응답에서는 ASCII 로, 설정 명령에서는 이진수로 보낼 수 있고, 모양을 선언과 필드에 두 번 적을 필요도 없습니다.

```json
"points": { "kind": "named",
  "items": [ { "target": "현재온도", "value": "float" },
             { "target": "운전",     "value": "binary" },
             { "target": "설정온도", "value": "float", "access": "rw" } ] }
```
```json
"body": [ { "type": "INT", "size": 2, "scale": 0.1, "target": "현재온도" },
          { "name": "flags", "type": "BITS", "size": 1, "fields": [ { "bit": 0, "target": "운전" } ] } ]
```

| 화면 (Items 표, 속성 창) | JSON | 내용 |
| --- | --- | --- |
| Target | `target` | Server Data 의 Target 이름 |
| Value | `value` | `binary`(0/1), `integer`, `float`. 비워 두면 그 Target 을 처음 실은 필드의 표현으로 정합니다. 속성 창의 Value 칸에 `fields carry float` 처럼 표시되며, 필드마다 다르면 경고가 나옵니다 |
| Access | `access` | `read only`(`r`, 기본) 또는 `read and write`(`rw`) |
| Description | `doc` | 설명 |

선언에는 Type, Size, Scale 같은 표현 속성을 적지 않습니다(오류).

### 5.4 패턴 Target (pattern) — 식으로 만든 이름

```json
"channels": { "kind": "pattern", "pattern": "^ch[0-9]{1,2}$", "value": "float", "access": "rw" }
```

`data('ch' + text(body.channel))` 처럼 동작 중에 Target 이름을 만들어 쓰는 경우에 선언합니다.
이름 Target 과 마찬가지로 Value 와 Access 만 선언하고, Pattern 칸에는 정규식을 적습니다(`^ch[0-9]{1,2}$` 는 `ch0` 부터 `ch99` 까지).
이 정규식에 맞는 이름이 Value Simulation 등록과 쓰기 허용의 기준이 됩니다.

### 5.5 Server Data 의 값을 패킷에 싣기

| 방법 | 예 |
| --- | --- |
| 필드의 Target 칸 | `{ "type": "INT", "size": 2, "scale": 0.1, "target": "현재온도" }` (이름은 비워도 됩니다) |
| 식의 `data(target)` | Value 에 `data('전압1') * 10` |
| 식의 `read(map, …)` | Value 에 `read('holding', req.body.start, req.body.quantity)` |

Server Data 에 값이 없는 Target 은 0 으로 봅니다.
필드의 Target 칸에 적은 Target 이 선언되어 있지 않으면 저장할 때 경고하고 Target 목록에 넣습니다. 식의 `data('…')` 에 적은 이름은 확인하지 않습니다.

### 5.6 받은 값을 Server Data 에 쓰기

받은 값은 응답 규칙의 **Write to Server Data** 로만 씁니다. 필드가 받은 값을 저절로 저장하지는 않습니다. 조건에 따라 쓰기를 거절해야 하는 경우가 많기 때문입니다.

```json
"write": [
  { "target": "설정온도", "value": "body.setpoint" },
  { "map": "holding", "address": "body.start", "bytes": "body.values" }
]
```

- 첫 번째 줄은 Target 하나에 값을 씁니다(화면의 Target, Value).
- 두 번째 줄은 `read` 의 반대입니다(화면의 Sequence, Address, Bytes). 바이트를 항목의 타입대로 풀어서 각 Target 에 씁니다.
- 패킷으로 쓰는 값도 Server Data 의 잠금을 따릅니다. Server Data 화면, REST API, Test Plan 으로 잠근 Target 에는 쓰지 않습니다. 이때 응답은 평소대로 보내고 Realtime Logs 에 기록합니다.
  그 Target 의 Value Simulation 은 **Stop on control** 에 적은 시간(초) 동안 멈춰, 패킷으로 쓴 값을 덮어쓰지 않습니다. 0 이면 멈추지 않습니다.
- Access 가 `rw` 가 아닌 Target, 선언하지 않은 Target, `fits` 나 `writable` 이 거짓인 범위, 칸 단위로 나누어떨어지지 않는 바이트는 쓰지 않고 Realtime Logs 에 기록합니다.
  실제 장비처럼 예외 응답을 보내려면 앞쪽 응답 규칙의 When 에서 `writable(…)` 로 걸러 냅니다.
- 순차 Target 쓰기의 **Repeat**(`count`)에 수나 식을 적으면 그 줄을 그 횟수만큼 되풀이합니다. Address 와 Bytes 의 식에서 `i` 가 0 부터 시작하는 되풀이 번호입니다.
  한 번 쓸 때마다 바로 반영되므로, 뒤 번의 `read()` 는 앞 번에 쓴 값을 봅니다. 요청 하나에 번지와 데이터 블록이 여러 개 오는 프로토콜에서 씁니다.

  ```json
  { "map": "memory", "count": "body.blockCount", "address": "body.vars[i].word", "bytes": "body.blocks[i].data" }
  ```

- Value 는 수여야 합니다. 순차 Target 에 바이트로 쓸 수 있는 항목은 숫자형, BCD, TEXTNUM, HEXNUM, 비트 집합입니다. 고정 내용과 빈 칸 항목은 건너뛰며, Target 이 있는 TEXT 나 BYTES 항목이 범위에 들어 있으면 오류가 나고 응답하지 않습니다.

### 5.7 Device 의 Target 목록

정의를 쓰는 Device 의 Target 목록은 선언한 Target, 필드에 실었지만 선언하지 않은 Target, 패턴으로 이루어집니다.
이 목록이 Value Simulation 의 Target 선택지와 등록 검사, Server Data 화면에 보이는 Target, REST API 와 Test Plan 의 쓰기 허용 기준이 됩니다(15.2절).

### 5.8 Target 과 필드

| | 순차 Target | 이름 Target | 패턴 Target | 선언하지 않은 Target |
| --- | --- | --- | --- | --- |
| 선언에 적는 것 | 항목마다 표현과 Access | Value 와 Access | Pattern, Value, Access | 없음 |
| BITS 의 비트 필드에 싣기 | ○ (비트 집합 항목의 비트는 그 항목을 `read` 할 때 함께 실림) | ○ | ○ | ○ |
| 같은 값을 메시지마다 다른 모양으로 | ✕ (표현이 하나뿐) | ○ | ○ | ○ |
| `read`, `fits`, `writable`, 순차 Write | ○ | ✕ | ✕ | ✕ |
| 응답 규칙의 Write (Target) | 항목이 `rw` 일 때 | `rw` 일 때 | `rw` 일 때 | ✕ (경고 후 건너뜀) |
| `data('이름')` | ○ | ○ | ○ | ○ (없으면 0) |
| 값의 종류(11.9절) | 항목의 표현으로 | Value, 없으면 처음 실은 필드로 | Value(없으면 알 수 없음) | 처음 실은 필드로 |
| Device 의 Target 목록(5.7절) | ○ | ○ | 패턴으로 | 필드의 Target 칸에 실었으면 ○ (경고) |

- Target 이름은 문서 전체에서 한 번만 선언합니다. 묶음이 다르거나 순차 Target 비트 집합의 비트인 경우도 마찬가지입니다.
- Target 을 실을 수 있는 표현은 수를 나르는 것뿐입니다. 숫자형, BCD, TEXTNUM, HEXNUM, BITS 의 비트 필드입니다(11.10절).
  이 검사는 메시지 필드에만 적용됩니다. 순차 Target 의 TEXT 나 BYTES 항목에 Target 을 두면 저장은 되지만 순차 Write 에서 오류가 나므로 두지 않습니다.
- `binary` 나 `integer` 로 선언한 Target 을 소수를 나르는 표현에 실으면 오류입니다. 반대로 `float` Target 을 정수 표현에 싣는 것은 괜찮습니다(11.9절).
- 값의 종류를 선언하지 않은 이름 Target 이 필드마다 다른 종류로 실리면 경고합니다. Value 를 적어서 정합니다.
- 순차 Target 의 비트 집합 항목에는 항목 자체의 Target 을 두지 않습니다(오류). 비트 필드마다 Target 을 둡니다.

---

## 6. 구조체 (Structures)

필드 여러 개를 하나로 묶습니다. 차례의 **Structures** 에서 **+ Structure** 로 이름을 붙여 정의하고, 필드의 Type 목록 중 Structure 묶음에서 그 이름을 고르면 됩니다(JSON 으로는 `"structure": 이름`).
구조체를 열면 위쪽 설정 카드에서 Name 과 Description 을 바로 고치고, 아래 Fields 표에서 안쪽 필드를 관리합니다. 필드는 표의 줄을 눌러 속성 창에서 고칩니다.

```json
"structures": { "point": { "fields": [ { "name": "id", "type": "UINT", "size": 1 },
                                        { "name": "v",  "type": "FLOAT", "size": 4 } ] } }

{ "name": "p",      "structure": "point" }
{ "name": "points", "structure": "point", "count": "body.n" }
```

- **구조체는 한 단계입니다.** 구조체의 필드에는 구조체를 제외한 타입(숫자형, BCD, TEXT, TEXTNUM, HEXNUM, BYTES, PAD, BITS)과 그 배열, 조건부 필드만 쓸 수 있습니다.
  그래서 구조체 정의 화면의 Type 목록에는 Structure 묶음이 없습니다. 구조체는 메시지와 프레임의 필드에서만 씁니다.
- 구조체를 쓰는 필드에는 Name, Count(레코드 배열), When(통째로 있거나 없음), Size(10.5절), Transform(11.8절), Expect, Description 을 둘 수 있습니다.
  값은 안쪽 필드가 가지므로 Const, Value, Target, Checksum 은 두지 않습니다.
- 안쪽 필드는 `body.point.id`, `body.points[2].v` 처럼 부릅니다. 이름만 쓰면 같은 구조체 안에서 먼저 찾습니다(3장).
- 구조체는 쓰는 곳이 없어도 저장할 때 검사합니다. 여러 곳에서 써도 구조체 안의 문제는 한 번만 표시됩니다.
- 설정 카드의 Used by 에는 그 구조체를 쓰는 필드 목록이 표시되며, 누르면 그 메시지나 프레임으로 이동합니다.
- 구조체 안에 레코드 배열이 필요해 보이면 대개 본문 전체의 변환(11.8절)으로 해결됩니다. 레코드 배열은 메시지 본문에 직접 둡니다.

---

## 7. 프레임과 프레이밍

차례의 **Frames** 를 누르면 Framing, Frames, Checksums 세 카드가 한 페이지에 열립니다.
Frames 카드는 프레임 목록이며, 줄을 누르거나 차례에서 프레임 이름을 누르면 그 프레임이 열립니다.
이 장에서는 프레임(7.1절, 7.2절)과 Framing 카드(7.3절)를 설명하고, Checksums 카드는 8장에서 설명합니다.

### 7.1 프레임

프로토콜이 엄격한 헤더나 푸터 형식을 갖추고 있어 모든 패킷이 그 형식을 공유한다면, 헤더와 푸터를 프레임으로 묶어 두고 여러 메시지에서 재사용할 수 있습니다.
프레임을 지정한 메시지는 본문만 작성하면 됩니다.

Frames 카드의 **+ Frame** 으로 프레임을 만듭니다. 프레임을 열면 위쪽 설정 카드에서 Name, Body size, Description 을 바로 고치고, 이 프레임을 쓰는 메시지(Used by)를 확인할 수 있습니다.
그 아래 Fields 표에는 헤더와 푸터의 필드가 Header, Footer 구분 줄로 나뉘어 있으며, 구분 줄의 + 로 그 영역에 필드를 더합니다.

| 화면 | JSON | 내용 |
| --- | --- | --- |
| Name | (키) | 프레임 이름. 메시지의 Frame 목록에 나타납니다 |
| Fields 표의 Header | `header` | 헤더 필드 목록 |
| Fields 표의 Footer | `footer` | 푸터 필드 목록 |
| Body size | `bodySize` | 본문의 바이트 수를 계산하는 식입니다(선택). 예: `header.length`. 받을 때는 본문을 이 크기 안에서만 읽으며, 다 읽지 못하거나 바이트가 남으면 그 메시지로 보지 않습니다. 보낼 때는 쓰이지 않으므로 길이 필드는 `sizeof(body)` 같은 Value 로 채웁니다 |
| Description | `doc` | 설명 |

프레임에는 본문이 없습니다. 본문은 항상 메시지에서 정합니다.

프레임의 필드는 그 프레임을 쓰는 받는 메시지와 보내는 메시지 모두에 들어갑니다. 그래서 같은 필드라도 받을 때와 보낼 때 동작이 다릅니다(10.6절).
Const 는 받을 때 비교하고 보낼 때 그 값을 보냅니다. Value 는 보낼 때만 쓰이므로 `req.header.unitId` 처럼 받은 값을 되돌려 보내는 용도로 알맞습니다. Expect 는 받을 때만 쓰입니다.

### 7.2 프레임을 쓰는 메시지와 쓰지 않는 메시지

| 항목 | 프레임을 고른 메시지 | 프레임이 없는 메시지 |
| --- | --- | --- |
| 헤더·푸터의 구조 | 프레임의 것을 씁니다 | 메시지에서 직접 작성합니다 |
| 본문 | 메시지의 Body | 메시지의 Body |
| Body size | 프레임의 Body size 를 씁니다 | 쓸 수 없습니다. 본문 길이는 필드들이 정하고, 전체 길이는 Total size 로 검사합니다 |
| 보낼 때 헤더·푸터의 값 | Set frame fields 로 바꿉니다. 필드를 더하거나 타입을 바꾸는 등 구조는 바꿀 수 없습니다 | 필드에 직접 적습니다. Set frame fields 도 쓸 수 있습니다 |
| 체크섬 Covers | 프레임 필드 이름을 경로로 쓸 수 있습니다 | 이 메시지의 필드 |
| Body transform | 메시지마다 겁니다. 본문의 끝은 프레임의 Body size 로 정합니다 | 메시지마다 겁니다. 본문의 끝은 받은 바이트에서 고정 크기 푸터를 뺀 곳입니다(11.8절) |

- 프레임의 Const 필드는 Set frame fields 로도 바꿀 수 없습니다. Const 가 우선순위가 가장 높기 때문입니다(10.2절). 메시지마다 달라지는 헤더 값은 Const 대신 Value 로 두고 Set frame fields 로 바꿉니다.
- 프레임 필드에 Const 도 Value 도 없으면, 그 프레임을 쓰는 보내는 메시지마다 Set frame fields 로 값을 채워야 합니다.
  채우지 않으면 `Frame field header.x has no value; set it in this message.` 오류가 나고 저장되지 않습니다.
- 요청과 응답의 모양이 다르면(예: 요청은 주소와 명령, 응답은 데이터와 체크섬) 프레임으로 묶지 않습니다. 프레임은 그것을 쓰는 모든 메시지에 똑같이 적용됩니다.
- 헤더·푸터의 구조가 메시지마다 달라야 한다면 그 메시지는 프레임을 쓰지 않거나, 프레임을 하나 더 만듭니다.
- 프레임이 없는 메시지에서 Header·Footer 에 적은 필드는, 바이트로 보면 Body 에 모두 적은 것과 같습니다.
  그래도 나누어 적으면 `header.x` 같은 경로를 쓸 수 있고, 체크섬 범위를 `body` 처럼 영역 단위로 지정할 수 있습니다.

### 7.3 프레이밍

TCP 로 들어오는 바이트에서 프레임의 경계는 **메시지 정의로 판단**합니다. 쌓인 바이트에 받는 메시지를 맞춰 보고,
맞으면 그만큼 처리하고, 바이트가 모자라 끝에서 멈추면 다음 바이트를 기다리고, 그 밖의 경우에는 경계를 다시 찾습니다.

Frames 페이지의 **Framing** 카드에서 설정합니다. 모두 선택 항목입니다.

| 화면 | JSON (`framing`) | 기본 | 내용 |
| --- | --- | --- | --- |
| Resync on | `syncOn` | 없음 | 경계를 다시 찾을 때 이 바이트가 나오는 곳까지 건너뜁니다. 비워 두면 한 바이트씩 건너뜁니다. `0x02`, `0x0203` 같은 16진 표기나 `#` 같은 글자로 적습니다 |
| Max length | `maxLength` | 1024 | 바이트. 바이트가 모자란 상태로 이 길이를 넘으면 버립니다 |
| Idle flush | `idleFlushMs` | 1000 (TCP) | ms. 이 시간 동안 새 바이트가 없으면 모자란 바이트를 버립니다. RTU 의 3.5문자 침묵에 해당합니다 |

- UDP 는 데이터그램 하나가 프레임 하나입니다. 바이트가 남으면 그 메시지가 아니며, 경계를 다시 찾지 않고 General 의 Frames no message matches 를 따릅니다.
- 경계를 다시 찾는 것은 모든 받는 메시지가 맞지 않을 때뿐입니다. 앞 메시지가 바이트를 기다리는 중이면 다시 찾지 않습니다(9.3절).

---

## 8. 체크섬

체크섬은 Frames 페이지의 **Checksums** 카드에서만 정의합니다(알고리즘, 범위, 검사 방법). **+ Checksum** 으로 더하면 체크섬마다 블록이 하나씩 생기고, 블록의 칸을 바로 고칩니다.
메시지에서는 필드의 **Checksum** 칸에서 "이 자리가 그 체크섬" 이라고 고르기만 하며, 그 필드의 Type 이 체크섬을 보내는 모양을 정합니다(8.3절).

### 8.1 정의

```json
"checksums": {
  "crc": { "algorithm": "CRC-16/MODBUS", "covers": { "from": "header", "to": "body" } },
  "bcc": { "algorithm": "XOR8", "covers": { "from": "header.address", "to": "body" }, "onMismatch": "flag" },
  "lrc": { "algorithm": "LRC8", "covers": { "from": "body", "to": "body" }, "over": "value" }
}
```

| 화면 | JSON | 내용 |
| --- | --- | --- |
| 블록 머리의 이름 칸 | (키) | 체크섬 이름. 필드의 Checksum 목록에 나타납니다 |
| 이름 옆 목록 | `algorithm` | 알고리즘(8.2절). 바꾸면 새 알고리즘에서 쓰지 않는 칸은 지워집니다 |
| Covers from, Covers to | `covers.from`, `covers.to` | 영역 이름(`header`, `body`, `footer`)이나 필드 경로. **양쪽 끝을 포함**합니다. 체크섬 자리는 범위 밖에 있어야 합니다(8.5절) |
| Over | `over` | `sent bytes`(기본, 변환을 거친 전송 바이트) 또는 `before transforms`(변환 전 바이트, Modbus ASCII 의 LRC). **`before transforms` 는 아직 적용되지 않습니다**(18장) |
| On mismatch | `onMismatch` | `drop the frame`(기본, 그 메시지가 아닌 것으로 처리) 또는 `accept · valid() false`(받아들이고 식의 `valid(이름)` 이 거짓) |
| Description | `doc` | 설명 |

### 8.2 알고리즘

CRC 는 width, poly, init, refin, refout, xorout 여섯 매개변수로 정해집니다. 아래 목록에서 이름으로 고르거나, `CRC — custom parameters` 를 골라 매개변수를 직접 적습니다.
check 는 글자 `"123456789"` 를 계산한 결과입니다. 장비 설명서의 CRC 매개변수나 check 값과 비교하면 같은 CRC 인지 확인할 수 있습니다.

| 이름 | width | poly | init | refin/out | xorout | check |
| --- | --- | --- | --- | --- | --- | --- |
| `CRC-8/SMBUS` | 8 | 0x07 | 0x00 | 아니오 | 0x00 | 0xF4 |
| `CRC-16/ARC` | 16 | 0x8005 | 0x0000 | 예 | 0x0000 | 0xBB3D |
| `CRC-16/MODBUS` | 16 | 0x8005 | 0xFFFF | 예 | 0x0000 | 0x4B37 |
| `CRC-16/XMODEM` | 16 | 0x1021 | 0x0000 | 아니오 | 0x0000 | 0x31C3 |
| `CRC-16/IBM-3740` (CCITT-FALSE) | 16 | 0x1021 | 0xFFFF | 아니오 | 0x0000 | 0x29B1 |
| `CRC-16/KERMIT` | 16 | 0x1021 | 0x0000 | 예 | 0x0000 | 0x2189 |
| `CRC-32/ISO-HDLC` | 32 | 0x04C11DB7 | 0xFFFFFFFF | 예 | 0xFFFFFFFF | 0xCBF43926 |

```json
"custom": { "algorithm": "CRC", "width": 16, "poly": "0x1021", "init": "0x1D0F", "refin": false, "refout": false, "xorout": "0x0000" }
```

직접 정하는 CRC 는 Width(1–64), Poly, Init, XOR out 을 모두 적습니다. Reflect in, Reflect out 의 기본은 `no` 입니다.

| 그 밖의 알고리즘 | 칸 | 뜻 |
| --- | --- | --- |
| `SUM — byte sum` | Width(8, 16, 32 bits, 필수), Complement(`none` 기본, `ones`, `twos`) | 바이트 합 |
| `XOR8 — BCC` | | 바이트 XOR |
| `LRC8 — Modbus ASCII` | | 바이트 합의 2의 보수 |
| `EXPR — expression` | Init, Step, Final (식. `acc` 는 지금까지 계산한 값, `b` 는 바이트) | 업체 고유의 계산. 예: Step `(acc + b) & 0xFF`, Final `acc ^ 0xFF` |

### 8.3 체크섬 자리

```json
{ "name": "crc", "type": "UINT", "size": 2, "byteOrder": "little", "checksum": "crc" }
{ "name": "bcc", "type": "UINT", "size": 1,    "checksum": "bcc" }
{ "name": "sum", "type": "HEXNUM", "size": 2, "checksum": "sum" }
```

알고리즘의 결과(정수)를 필드의 Type 으로 인코딩합니다. 첫 줄은 이진 2바이트를 낮은 바이트부터(Modbus), 셋째 줄은 16진 글자 두 개로 보냅니다.
같은 체크섬을 이진으로도, 글자로도 보낼 수 있습니다.

### 8.4 계산 순서

보낼 때는 체크섬 자리를 0 으로 채워 전체를 인코딩한 뒤, 범위의 바이트로 체크섬을 계산해 그 자리를 덮어씁니다.
체크섬 자리가 다른 체크섬의 범위 안에 있으면 안쪽 체크섬부터 계산합니다. 두 체크섬이 서로의 자리를 범위에 품으면 계산할 수 없어 응답하지 않습니다(8.5절).
받을 때는 같은 범위를 계산해 받은 값과 비교합니다.

### 8.5 체크섬 자리의 타입과 범위

| 자리의 Type | 결과를 싣는 모양 | 예 |
| --- | --- | --- |
| `UINT`, `INT` | 정수 raw, Byte order 적용 | Modbus RTU 의 CRC 는 UINT 2바이트 little |
| `HEXNUM` | 16진 글자(Size 글자 수) | 합 체크섬을 `"1A"` 로 |
| `BCD` | BCD | |
| `TEXTNUM` | 10진 글자 | |
| `FLOAT` | 권하지 않습니다(정수 결과를 실수로 싣게 됩니다) | |
| `TEXT`, `BYTES`, `PAD`, `BITS`, 구조체 | 쓸 수 없습니다(응답하지 않거나, 받을 때 비교하지 못합니다) | |

- 자리의 폭이 결과보다 좁으면 보낼 때 그 자리의 On overflow 를 따릅니다. 예를 들어 CRC-16 을 UINT 1바이트 자리에 실으면 `error` 에 따라 응답하지 않습니다.
- 자리에는 Scale, Offset 을 두지 않습니다(11.11절). Const, Value, Target 도 두지 않습니다. 보낼 때 덮어써집니다.
- Covers from 과 Covers to 는 영역 이름(`header`, `body`, `footer`)이나 필드 경로입니다. from 의 시작부터 to 의 끝까지이며 **양쪽 끝을 포함**합니다. 짧은 이름은 끝부분이 맞는 경로로 찾습니다.
- 체크섬 자리는 자기 범위 밖에 있어야 합니다. 범위 안에 다른 체크섬 자리가 있으면 안쪽부터 계산하고, 서로를 품으면 오류입니다(8.4절).
- 범위의 경로가 그 메시지에 없거나(조건부 필드가 빠진 경우 포함), 변환된 구조체나 본문의 안쪽을 가리키면 오류입니다. 받을 때는 그 메시지가 아닌 것으로 처리하고, 보낼 때는 응답하지 않습니다.
- 체크섬 정의 하나를 여러 자리, 여러 메시지에서 쓸 수 있습니다. 메시지마다 범위의 경로가 있어야 합니다.

---

## 9. 메시지

차례의 **Messages** 를 누르면 메시지 목록이 열립니다. **+ Received** 와 **+ Sent** 로 받는 메시지와 보내는 메시지를 더하고,
줄을 누르거나 차례에서 메시지 이름을 누르면 그 메시지가 열립니다. 목록의 Replies 칸에는 받는 메시지가 응답으로 보내는 메시지가 표시됩니다.

### 9.1 메시지

메시지를 열면 위쪽 설정 카드에서 이름, 방향, 프레임 같은 메시지 속성을 바로 고칩니다.
그 아래 Fields 표에는 Header, Body, Footer 의 필드가 구분 줄로 나뉘어 한 표에 있으며, 구분 줄의 + 로 그 영역에 필드를 더합니다(카드 머리의 **+ Field** 는 Body 에 더합니다).
프레임을 고른 메시지에는 Body 표만 있고, 설정 카드 아래에 프레임의 헤더와 푸터 필드가 한 줄로 표시됩니다.
받는 메시지에는 Respond 표가 더 있으며, 화면이 넓으면 Fields 표 옆에 놓입니다.

| 화면 | JSON | rx | tx | 내용 |
| --- | --- | --- | --- | --- |
| Name | `name` | ✓ | ✓ | 메시지 이름. 다른 메시지와 겹칠 수 없고, `none`, `echo` 는 쓸 수 없습니다(3장) |
| Direction | `direction` | ✓ | ✓ | `rx — the device receives`(장비가 받음) 또는 `tx — the device sends`(장비가 보냄) |
| Frame | `frame` | ○ | ○ | 사용할 프레임. 고르면 Fields 표에 Body 만 남고, 헤더와 푸터는 프레임의 것을 씁니다. 비워 두면(`(none — own header and footer)`) 이 메시지의 Header·Footer 를 직접 작성합니다(7.2절) |
| Fields 표의 Header, Footer | `header`, `footer` | ○ | ○ | 필드 목록. 프레임을 고른 메시지에서는 쓰이지 않습니다(경고) |
| Fields 표의 Body | `body` | ○ | ○ | 필드 목록. 프레임을 고른 메시지에서는 Body 표입니다 |
| Body transform | `bodyTransform` | ○ | ○ | 본문 전체에 거는 변환(11.8절). 보낼 때는 본문을 인코딩한 뒤 적용하고, 받을 때는 되돌린 뒤 본문 필드를 읽습니다 |
| Match | `match` | ○ | | 받을 때의 조건 식. 거짓이면 그 메시지로 보지 않습니다. 보내는 메시지에서는 쓰이지 않습니다(경고) |
| Total size | `size` | ○ | | 메시지 전체 바이트 수. 다 읽은 뒤 길이가 다르면 그 메시지로 보지 않습니다. 길이를 정하는 용도는 아닙니다 |
| Set frame fields | `set` | | ○ | 헤더·본문·푸터의 맨 위 필드 값을 바꿉니다. 예: `header.function` = `req.header.function \| 0x80`. 받는 메시지에서는 쓰이지 않습니다(경고) |
| Respond 표 | `respond` | ✓ | | 응답 규칙 목록(9.2절). 비어 있으면 이 메시지에 응답하지 않습니다(경고) |
| Description | `doc` | | | 설명 |

받는 메시지의 순서는 Messages 목록의 순서이며 의미가 있습니다(9.3절). 순서는 메시지 설정 카드 오른쪽 위의 ↑, ↓(Move up, Move down)로 바꿉니다.
보내는 메시지의 순서에는 의미가 없고, 응답 규칙의 Reply 에서 이름으로 불립니다. 필드와 응답 규칙의 순서는 속성 창 머리의 ↑, ↓ 로 바꿉니다.

### 9.2 응답 규칙 (Respond)

응답 규칙은 받는 메시지에 둡니다. 위에서부터 차례로 보다가 **When 이 처음으로 참이 되는 규칙 하나만** 실행합니다. 맞는 규칙이 없으면 응답하지 않습니다.
**+ Rule** 로 규칙을 더하고, Respond 표의 줄을 누르면 열리는 속성 창에서 고칩니다.

| 화면 | JSON | 내용 |
| --- | --- | --- |
| Name | `name` | 규칙을 알아보기 위한 이름(선택) |
| When | `when` | 조건 식. 비워 두면 항상 참입니다 |
| Write to Server Data | `write` | Server Data 에 쓸 값의 목록(5.6절). 응답을 만들기 전에 실행합니다 |
| Reply | `reply` | 보낼 메시지 이름, `none`(응답하지 않음), `echo`(받은 바이트를 그대로 돌려보냄) 중 하나. 비워 둘 수 없습니다 |
| Args for the reply | `args` | 보내는 메시지에 넘길 값. 보내는 메시지의 식에서 `args.이름` 으로 부르므로 이름은 식별자여야 합니다(3장). 값은 식 또는 수입니다 |
| Delay | `delayMs` | 응답 지연(ms). 지연되는 동안에도 다른 연결의 응답은 막히지 않습니다 |

```json
"respond": [
  { "when": "header.unitId != $unitId", "reply": "none" },
  { "when": "body.quantity < 1 || body.quantity > 125", "reply": "exception", "args": { "code": 3 } },
  { "name": "readOk", "reply": "readHoldingReply" }
]
```

규칙은 When 확인, Write 실행(위에서부터), Args 계산, 응답 인코딩 순서로 처리됩니다.
도중에 식 오류나 범위 초과 같은 오류가 나면 응답하지 않습니다. 이때 **이미 끝난 Write 는 되돌리지 않습니다.**

### 9.3 받기와 보내기의 흐름

**받을 때**는 쌓인 바이트에 받는 메시지를 **목록 순서대로** 맞춰 봅니다. 아래 조건을 모두 만족하는 첫 메시지가 선택됩니다.

1. 헤더, 본문(Body size 가 있으면 그 안에서), 푸터의 모든 필드를 끝까지 읽었습니다. 필드마다 When, Const, Expect 를 확인합니다(10.4절).
2. Total size 가 있으면 읽은 길이가 같습니다.
3. Match 가 참입니다.
4. On mismatch 가 `drop the frame` 인 체크섬이 맞습니다(8장).

- 맞춰 보는 도중에 생긴 오류는 식 오류나 없는 필드를 부른 경우까지 포함해 모두 "그 메시지가 아님" 으로 처리하고, 다음 메시지로 넘어갑니다.
  그래서 Match 에 오타가 있으면 그 메시지는 아무 알림 없이 한 번도 선택되지 않습니다. 이런 실수는 시험 사례(13장)로 잡습니다.
- **TCP** 에서는 앞 메시지가 바이트가 모자라 기다리는 중이면, 뒤 메시지가 지금 바이트로 맞더라도 함께 기다립니다.
  그래서 긴 메시지를 앞에 두면 짧은 메시지의 처리가 늦어질 수 있습니다. 첫 몇 바이트(Const 나 Match)로 구분되는 메시지를 앞에 둡니다.
- 어느 메시지에도 맞지 않으면, TCP 는 프레임 경계를 다시 찾습니다(7.3절). UDP 데이터그램과 MK RCU 의 RS 본문은 General 의 Frames no message matches 설정을 따릅니다.
- Frames no message matches(`onUnmatched`)는 `none`(기본), `echo`, 보낼 메시지 이름 중에서 고릅니다. 보낼 메시지로 응답하는 경우에는 받은 요청이 없으므로, 그 메시지의 식에서 `req.` 를 쓰면 오류가 나고 응답하지 않습니다.

**보낼 때**는 보내는 메시지의 필드 값을 정하고(10.2절), 바이트로 인코딩한 뒤, 체크섬을 채웁니다(8.4절). When 이 거짓인 필드는 보내지 않습니다.

---

## 10. 필드

Fields 표의 줄을 누르면 속성 창에 그 필드의 속성이 모두 열립니다. 속성 창은 Field, Length, Value, Format 묶음으로 나뉘며, 고른 Type 에 맞는 칸만 나타납니다.

| 화면 | JSON | 내용 | 쓰는 타입 | 받을 때 | 보낼 때 |
| --- | --- | --- | --- | --- | --- |
| Name | `name` | 필드 이름. Const 필드, PAD, 체크섬 자리, Target 을 실은 필드는 비워도 됩니다 | 모두 | 이 이름으로 값을 남김 | 식에서 부름 |
| Type | `type` | 표현 형식(11장). 비워 둘 수 없습니다 | 모두 | ✓ | ✓ |
| Type → Structure 묶음 | `structure` | 구조체 이름(6장). 메시지와 프레임의 필드에서만 고를 수 있습니다 | 구조체 | ✓ | ✓ |
| Bit fields | `fields` | 비트 필드 목록(11.7절) | BITS | ✓ | ✓ |
| Size (bytes), HEXNUM 은 Size (characters) | `size` | 바이트 수(HEXNUM 은 글자 수). 수 또는 식. 배열이면 원소 하나의 크기입니다 | 10.5절 | 길이 | 길이와 검사 |
| Count (array) | `count` | 배열 원소 수. 수 또는 식(0–65535) | 모두 | ✓ | ✓ |
| Terminator | `terminator` | 끝 글자(예: `\r`, `,`). 끝 글자도 이 필드에 포함됩니다. Size 가 있으면 쓰이지 않습니다 | TEXT, TEXTNUM, HEXNUM | ✓ | ✓ |
| When | `when` | 조건부 필드. 식이 거짓이면 이 필드는 없는 것으로 봅니다 | 모두 | ✓ | ✓ |
| Const | `const` | 고정값(raw) | 11.10절 | 다르면 그 메시지가 아님 | 이 값을 보냄 |
| Expect | `expect` | 조건 식(`this` 가 이 필드의 값) | 모두 | 거짓이면 그 메시지가 아님 | 쓰이지 않음 |
| Value | `value` | 값을 계산하는 식(물리값) | 값 하나를 나르는 타입, 비트 필드 | 쓰이지 않음 | 이 값을 보냄 |
| Target | `target` | 연결할 Target. 그 값을 읽어 보냅니다. 받은 값을 저장할 때는 응답 규칙의 Write 를 씁니다(5.6절) | 수를 나르는 타입(5.8절) | 쓰이지 않음 | Target 값을 보냄 |
| Checksum | `checksum` | 이 자리에 들어갈 체크섬 | 8.5절 | 계산해 비교 | 계산해 채움 |
| Scale, Offset | `scale`, `offset` | 물리값과 raw 의 환산(10.1절). Scale 은 0 일 수 없습니다 | 숫자형, BCD, TEXTNUM, HEXNUM | ✓ | ✓ |
| On overflow | `onOverflow` | 범위를 넘을 때의 처리: `error — no reply`, `clamp`, `wrap`. `document default` 이면 General 의 When a value overflows 를 따릅니다 | 숫자형, BCD, TEXTNUM, HEXNUM, BITS | | ✓ |
| Byte order | `byteOrder` | 바이트 순서(11.1절, 11.2절) | 숫자형, BCD | ✓ | ✓ |
| Encoding | `encoding` | 글자 인코딩(11.3절) | TEXT, TEXTNUM, BYTES | ✓ | ✓ |
| Pad character, Align | `pad`, `align` | 남는 자리의 채움 글자와 정렬(11.3절, 11.4절) | TEXT, TEXTNUM | ✓ | ✓ |
| Decimals, Decimal point, Sign | `decimals`, `point`, `sign` | 소수 자리, 소수점 표기, 부호(11.4절) | TEXTNUM | ✓ | ✓ |
| Letters | `case` | 16진 글자의 대소문자(11.5절) | HEXNUM | ✓ | ✓ |
| Fill byte | `fill` | 채움 바이트(11.6절) | PAD | | ✓ |
| Bit order | `bitOrder` | Bit 를 비운 비트 필드의 배치 순서(11.7절) | BITS | ✓ | ✓ |
| Transform | `transform` | 구조체를 인코딩한 바이트에 거는 변환(11.8절) | 구조체 | ✓ | ✓ |
| Description | `doc` | 설명 | 모두 | | |

Const 값은 타입에 따라 이렇게 적습니다. 숫자형, BCD, TEXTNUM, HEXNUM 은 수 또는 `0x02` 같은 16진 표기, TEXT 는 글자 그대로(`\r` 같은 제어 문자 포함), BYTES 는 `02 03` 같은 16진 바이트열입니다.
구조체와 BITS 필드 자체에는 Const 를 둘 수 없고, 비트 필드에는 둘 수 있습니다.

### 10.1 값의 흐름

```
받을 때   전송 바이트 → (변환 해제) → raw → 물리값 = raw × Scale + Offset
보낼 때   물리값 → raw = round((물리값 − Offset) / Scale) → 범위 검사 → 바이트 → (변환)
```

- 식, Target, Args 가 다루는 값은 모두 **물리값**입니다. Value Simulation 이 220.1 을 넣으면 Scale 이 0.1 인 필드는 2201 을 보냅니다.
- **Const 는 raw 입니다.** Scale 과 Offset 을 거치지 않습니다. 예를 들어 `UINT` 2바이트, Scale 0.1 인 필드에서 Const 100 은 `00 64` 를 보내고, Value `100` 은 `03 E8` 을 보냅니다.
- 반올림은 가장 가까운 정수로 하며, .5 는 0 에서 먼 쪽으로 올립니다.
- 타입의 범위를 넘으면 On overflow 를 따릅니다. `error` 는 그 응답을 보내지 않고 메시지 이름, 필드, 값을 Realtime Logs 에 남깁니다.
  `clamp` 는 범위의 끝 값을 보내고, `wrap` 은 아래쪽 비트(TEXTNUM 은 아래쪽 자리)만 남깁니다.
- Scale 과 Offset 이 없는 정수 필드는 식에서 정수로 다뤄지므로 비트 연산을 쓸 수 있습니다. Scale 이나 Offset 이 있으면 실수로 다뤄집니다.

### 10.2 보낼 때 값의 우선순위

보낼 때 필드의 값은 아래 우선순위로 정해집니다. 앞 순위의 값이 있으면 뒤 순위는 보지 않습니다.

1. Const
2. 메시지의 Set frame fields
3. Value
4. Target
5. 체크섬 자리이면 0
6. PAD 이면 Fill byte

여기까지 값이 정해지지 않는 필드가 있으면 저장할 때 오류가 납니다.
예를 들어 한 필드에 Const 와 Target 을 함께 적으면 Const 값만 보내고 Target 은 쓰이지 않습니다(10.7절).
체크섬 자리는 예외입니다. 어느 값으로 정해졌든, 인코딩이 끝난 뒤 계산한 체크섬으로 덮어씁니다(8.4절).

### 10.3 배열, 조건부 필드, 구조체 필드

```json
{ "name": "values", "type": "UINT", "size": 2, "count": "req.body.quantity", "value": "data('hr' + text(req.body.start + i))" }
{ "name": "extra",  "type": "UINT", "size": 1, "when": "header.flags & 0x80 != 0" }
{ "name": "point",  "structure": "point", "count": 4 }
```

- 배열(Count)의 Value 는 원소마다 다시 계산하며, 그 안에서 `i` 가 원소 번호입니다. 배열의 값은 식에서 `body.values[2]` 처럼 부릅니다.
- 배열에 Size 를 적으면 원소 하나의 크기입니다. 원소 수는 항상 Count 로 정하며, "몇 바이트가 찰 때까지 읽기" 같은 방식은 없습니다.
- When 이 거짓인 필드는 받을 때 읽지 않고 보낼 때 보내지 않습니다. 필드가 있는지는 식에서 `has(경로)` 로 확인합니다.
- 세 번째 줄은 구조체 `point`(예: `id` UINT 1바이트, `v` FLOAT 4바이트)를 네 개 반복하는 필드입니다(6장).

### 10.4 받을 때의 검사

필드마다 When, 읽기, Const 비교, Expect 순서로 확인하고, 모든 필드를 읽은 뒤 Total size, Match, 체크섬을 확인합니다.
Const 가 다르거나, Expect 나 Match 가 거짓이거나, `drop the frame` 체크섬이 맞지 않는 것은 오류가 아닙니다. 그 메시지가 아닌 것으로 보고 다음 받는 메시지를 시도합니다.
값이 범위를 벗어나 장비가 예외 응답을 보내야 하는 경우는 응답 규칙의 When 으로 처리합니다(17장 예시 A).

Const 를 비교하는 기준은 타입마다 다릅니다. 숫자형, BCD, HEXNUM 은 raw 로, TEXTNUM 은 소수 자리와 Scale 을 반영한 값으로, TEXT 는 채움 글자를 걷어 낸 글자로, BYTES 는 바이트 그대로 비교합니다.

### 10.5 길이를 정하는 방법

| 타입 | 길이를 정하는 것 (위에 있는 것이 우선) |
| --- | --- |
| `UINT`, `INT`, `FLOAT` | Size. 필수이며 수만 쓸 수 있습니다 |
| `BCD` | Size. 필수이며 수 또는 식 |
| `TEXT`, `TEXTNUM` | ① Size(수 또는 식) ② Terminator(끝 글자까지, 끝 글자 포함) ③ 둘러싼 크기의 끝까지 |
| `HEXNUM` | ① Size(글자 수) ② Terminator. 둘 다 없으면 저장 오류 |
| `BYTES` | Size. 필수이며 수 또는 식 |
| `PAD` | Size. 필수 |
| `BITS` | Size. 필수이며 1–8 |
| 구조체 | Size 가 있으면 받을 때 그 크기 안에서만 읽고, 바이트가 남으면 그 메시지가 아닙니다. 보낼 때 크기가 다르면 응답하지 않습니다. Size 가 없으면 안쪽 필드 길이의 합입니다 |
| 순차 Target 항목 | Size. 필수(고정 크기여야 합니다) |

- **둘러싼 크기**는 가까운 것부터 찾습니다. Size 가 있는 구조체, 프레임의 Body size(본문 안), 받은 바이트의 끝 순서입니다.
  TCP 에서 "받은 바이트의 끝" 은 그때까지 도착한 바이트이므로 패킷이 나뉘어 도착하면 달라질 수 있습니다. 마지막 필드가 아니라면 Size 나 Terminator 를 적어 둡니다(경고).
- Size 에 적는 식에서, 받을 때는 앞에 있는 필드만 부를 수 있고 보낼 때는 어느 필드든 부를 수 있습니다(12.5절).
- 메시지의 Total size 는 길이를 정하지 않고, 다 읽은 뒤 검사에만 쓰입니다.

### 10.6 받을 때와 보낼 때의 차이

| 속성 | 받을 때 (받는 메시지, 프레임 필드) | 보낼 때 (보내는 메시지, 프레임 필드) |
| --- | --- | --- |
| Const | 받은 값과 비교하고, 다르면 그 메시지가 아닙니다 | 그 값을 raw 로 보냅니다. 우선순위가 가장 높습니다 |
| Value | 쓰이지 않습니다. 받은 값을 읽기만 합니다 | 식의 값(물리값)을 보냅니다 |
| Target | 쓰이지 않습니다. 받은 값을 저장하려면 응답 규칙의 Write 를 씁니다 | Target 의 값을 보냅니다 |
| Expect | `this` 로 검사하고, 거짓이면 그 메시지가 아닙니다 | 쓰이지 않습니다 |
| When | 거짓이면 읽지 않습니다. 앞 필드만 부를 수 있습니다 | 거짓이면 보내지 않습니다. 어느 필드든 부를 수 있습니다 |
| Checksum | 범위를 계산해 받은 값과 비교합니다(On mismatch) | 계산해서 채웁니다 |
| Size, Count 의 식 | 앞 필드만 부를 수 있습니다 | 어느 필드든 부를 수 있습니다 |
| On overflow | 쓰이지 않습니다 | 범위를 넘을 때의 처리 |
| 메시지의 Match, Total size, Respond | ✓ | 쓰이지 않습니다 |
| 메시지의 Set frame fields | 쓰이지 않습니다 | ✓ |

프레임은 받는 메시지와 보내는 메시지가 함께 쓰므로, 프레임 필드 하나에 Const(양쪽), Value(보낼 때), Expect(받을 때)를 함께 적어 두는 경우가 많습니다.

### 10.7 값을 정하는 속성을 함께 적었을 때

| 조합 | 보낼 때 | 받을 때 |
| --- | --- | --- |
| Const 와 Value 또는 Target | Const 가 우선합니다. Value 와 Target 은 쓰이지 않습니다 | Const 로 비교합니다 |
| 메시지의 Set frame fields 와 필드의 Value 또는 Target | Set frame fields 가 우선합니다. 프레임 필드의 값을 메시지마다 바꿀 때 씁니다 | Set frame fields 는 쓰이지 않습니다 |
| Set frame fields 와 필드의 Const | Const 가 우선합니다. Set frame fields 는 쓰이지 않습니다 | |
| Value 와 Target | Value 가 우선합니다 | 둘 다 쓰이지 않습니다 |
| Checksum 과 다른 값 | 항상 계산한 체크섬으로 덮어씁니다. 다른 값은 쓰이지 않습니다 | 체크섬 비교와 Const·Expect 검사를 모두 합니다. Const 를 두면 체크섬이 그 값일 때만 맞습니다 |
| 아무 값도 없음 | 받는 메시지 본문의 필드는 괜찮습니다. 보내는 메시지와 프레임의 필드는 저장 오류입니다(프레임 필드는 Set frame fields 로 채우라고 알려 줍니다) | 괜찮습니다 |

타입별로 쓸 수 있는 속성은 11.10절, Target 에 관한 규칙은 5.8절, 체크섬 자리는 8.5절에 있습니다.

---

## 11. 타입

Type 은 **표현 형식만** 고릅니다. 크기, 바이트 순서, 글자 모양 같은 자세한 인코딩은 별도 속성으로 정하며, Type 을 고르면 속성 창에 그 Type 에서 쓰는 칸만 나타납니다.
Type 목록은 Number(`UINT`, `INT`, `FLOAT`), 그 밖의 형식, Structure(구조체 이름) 묶음으로 나뉩니다.

| Type | 무엇 | 길이 | 나르는 값 | Target 을 실을 수 있는지 |
| --- | --- | --- | --- | --- |
| `UINT`, `INT`, `FLOAT` | 이진 숫자 | Size 바이트 | 수 | ○ |
| `BCD` | 한 바이트에 10진 두 자리 | Size 바이트 | 0 이상의 정수(Scale 적용 가능) | ○ |
| `TEXT` | 글자 | Size, Terminator, 끝까지 | 글자 | ✕ |
| `TEXTNUM` | 10진 숫자 글자 | Size, Terminator, 끝까지 | 수 | ○ |
| `HEXNUM` | 16진 숫자 글자 | Size(글자 수), Terminator | 0 이상의 정수(Scale 적용 가능) | ○ |
| `BYTES` | 바이트 그대로 | Size | 바이트 | ✕ |
| `PAD` | 채움 | Size | 없음 | ✕ |
| `BITS` | 비트 묶음 | Size 1–8 바이트 | 비트 필드마다 0 이상의 정수 | 비트 필드마다 ○ |
| 구조체 | 필드 묶음 | 안쪽 필드의 합 또는 Size | 안쪽 필드들 | 안쪽 필드에 싣습니다 |

### 11.1 숫자형 — UINT, INT, FLOAT

```json
{ "name": "voltage", "type": "UINT", "size": 2, "scale": 0.1 }
{ "name": "energy",  "type": "INT",  "size": 4, "byteOrder": "word-swap" }
```

| 화면 | JSON | 값 |
| --- | --- | --- |
| Type | `type` | `UINT`(부호 없는 정수), `INT`(부호 있는 정수, 2의 보수), `FLOAT`(IEEE 754 실수) |
| Size (bytes) | `size` | 바이트 수. 정수는 1, 2, 3, 4, 8, 실수는 4, 8 중에서 고릅니다. 필수입니다 |
| Byte order | `byteOrder` | `big`(기본), `little`, `word-swap`, `byte-swap`. 목록에 `A B C D` 처럼 바이트 배치가 함께 보입니다. 2·3바이트는 big 과 little 만 쓸 수 있고, 1바이트에는 이 칸이 없습니다 |
| Scale, Offset | `scale`, `offset` | 10.1절. FLOAT 에도 쓸 수 있습니다 |
| On overflow | `onOverflow` | 정수가 범위를 넘을 때의 처리(10.1절). FLOAT 은 범위를 검사하지 않으며, 4바이트 FLOAT 의 범위를 넘는 값은 Infinity 가 됩니다 |

Size 를 바꿨을 때 지금의 Byte order 를 그 크기에서 쓸 수 없으면 `big` 으로 돌아갑니다.

| Byte order | 2바이트 | 4바이트 | 8바이트 |
| --- | --- | --- | --- |
| `big` | A B | A B C D | A B C D E F G H |
| `little` | B A | D C B A | H G F E D C B A |
| `word-swap` | 없음 | C D A B | G H E F C D A B |
| `byte-swap` | 없음 | B A D C | B A D C F E H G |

A 가 가장 높은 바이트입니다. 3바이트는 big(A B C)과 little(C B A)만 쓸 수 있습니다.

| 타입 | 최솟값 | 최댓값 |
| --- | --- | --- |
| `UINT` n바이트 | 0 | 2^(8n) − 1. 단 8바이트 UINT 는 2^63 − 1 까지만 다룹니다(18장) |
| `INT` n바이트 | −2^(8n−1) | 2^(8n−1) − 1 |

다른 프로토콜(Modbus 등)의 Device 에서 쓰는 CTS data type 과는 이렇게 대응합니다.
`UI8` = UINT 1, `UI16AB` = UINT 2, `UI16BA` = UINT 2 little, `SI32CB` = INT 4 word-swap, `SI32BC` = INT 4 byte-swap, `FP32DA` = FLOAT 4 little, `SI64GB` = INT 8 word-swap.
3바이트 정수나 8바이트 UINT 처럼 CTS data type 에 없는 형식도 쓸 수 있습니다.

Const 는 수 또는 `0x1234` 같은 16진 표기로 적고, raw 값입니다.

### 11.2 BCD

| 화면 | JSON | 내용 |
| --- | --- | --- |
| Size (bytes) | `size` | 바이트 수(한 바이트에 두 자리). 필수이며 수 또는 식 |
| Byte order | `byteOrder` | `big`(기본) 또는 `little`. 바이트 단위의 순서입니다(DL/T 645 는 little) |
| Scale, Offset, On overflow | `scale`, `offset`, `onOverflow` | 10.1절 |

raw 값은 0 이상의 정수이며 최댓값은 10^(2 × Size) − 1 입니다. 소수 자리는 Scale 로 표현합니다(Scale 0.01 이면 `12345678` 은 123456.78).
받을 때 한 자리(4비트)라도 0~9 가 아니면 그 메시지로 보지 않습니다.

### 11.3 TEXT — 글자

| 화면 | JSON | 내용 |
| --- | --- | --- |
| Encoding | `encoding` | `ascii`(기본, General 의 Text encoding), `utf-8`, `utf-16be`, `utf-16le`, `euc-kr` |
| Size (bytes) 또는 Terminator | `size` 또는 `terminator` | 고정 바이트 수 또는 끝 글자. 둘 다 있으면 Size 만 씁니다. 둘 다 없으면 둘러싼 크기의 끝까지입니다(10.5절) |
| Pad character, Align | `pad`, `align` | 고정 크기에서 남는 자리를 채울 글자(기본은 공백, 여러 글자면 차례로 반복)와 정렬(`left` 기본, `right`). 받을 때는 정렬 반대쪽의 채움 글자를 걷어 냅니다 |

- Size 는 글자 수가 아니라 **바이트 수**입니다. UTF-8 에서 한글 한 글자는 3바이트입니다. 글자가 Size 보다 길면 응답하지 않습니다(On overflow 와 관계없습니다).
- 인코딩이 `ascii` 인데 ASCII 밖의 글자가 오면 응답하지 않습니다.
- 값은 Const(글자 그대로)나 Value(식의 결과를 글자로 바꿈. 수도 `text()` 를 거친 것처럼 바뀝니다)로 줍니다. Server Data 는 수만 담으므로 **Target 은 실을 수 없습니다.**
- 끝 글자도 이 필드의 바이트에 포함되며, 받은 값에서는 떼어 냅니다.

### 11.4 TEXTNUM — 10진 숫자 글자

| 화면 | JSON | 기본 | 내용 |
| --- | --- | --- | --- |
| Size (bytes) 또는 Terminator | `size` 또는 `terminator` | | 고정 폭 또는 끝 글자. 둘 다 있으면 Size 가 우선합니다(10.5절) |
| Decimals | `decimals` | 0 | 소수 자리 수 |
| Decimal point | `point` | `written` | 소수점 글자를 쓰는지 여부. `written (default)` 은 글자에 소수점이 있고, `implied` 는 소수점 없이 자리만 정합니다. `implied` 에서 Decimals 가 1 이면 `"02205"` 는 220.5 입니다 |
| Sign | `sign` | `auto` | `auto — minus only`(음수만 `-`), `always + or -`, `none`(부호 없음) |
| Pad character, Align | `pad`, `align` | `0`, `right` | 채움 글자와 정렬. `0` 으로 오른쪽 정렬하면 부호가 채움보다 앞에 옵니다(`+005.12`). 다른 채움 글자는 채움, 부호, 숫자 순서입니다 |
| Encoding | `encoding` | 문서 기본 | 글자 인코딩 |
| Scale, Offset | `scale`, `offset` | | 10.1절 |
| On overflow | `onOverflow` | 문서 기본 | Size 를 넘을 때: `error`(응답하지 않음), `clamp`(표현할 수 있는 가장 큰 수, 예: `999.9`), `wrap`(아래 자리만). Sign 이 `none` 인데 음수이면 `clamp` 는 0 을 보내고, 그 밖에는 오류입니다 |

받을 때는 앞뒤 공백과 `0` 이 아닌 채움 글자를 걷어 낸 뒤, `[+-]숫자[.숫자]` 꼴이 아니면 그 메시지로 보지 않습니다.

**Decimals 와 Scale** 은 둘 다 소수를 다루지만 하는 일이 다릅니다.

| | Decimal point `written` (기본) | Decimal point `implied` |
| --- | --- | --- |
| Decimals n | 보낼 때 소수점 뒤의 자리 수를 정합니다(12.3 을 Decimals 2 로 보내면 `"12.30"`). 받을 때는 글자의 소수점을 그대로 읽습니다 | 소수점 없이 n 자리가 숨어 있습니다. Scale 10^−n 과 계산이 같습니다 |
| Scale | 물리값과 raw 의 환산(단위 바꾸기) | 같습니다 |

`implied` 의 숨은 소수 자리는 Decimals 와 Scale 중 **하나로만** 표현합니다. 둘 다 쓰면 두 번 적용되어 `"234"` 가 2.34 가 됩니다.
Decimals 가 0 보다 크거나 Scale, Offset 이 있으면 이 필드는 실수 값을 나릅니다(11.9절).

### 11.5 HEXNUM — 16진 숫자 글자

| 화면 | JSON | 내용 |
| --- | --- | --- |
| Size (characters) | `size` | **글자 수**. Size 와 Terminator 중 하나는 있어야 합니다 |
| Terminator | `terminator` | 끝 글자. Size 가 있으면 쓰이지 않습니다 |
| Letters | `case` | `upper`(기본) 또는 `lower` |
| Scale, Offset, On overflow | `scale`, `offset`, `onOverflow` | 10.1절. raw 최댓값은 16^Size − 1 이며, 16글자 이상은 8바이트 UINT 와 같은 한도입니다 |

- 항상 ASCII 이며 왼쪽을 `0` 으로 채웁니다.
- 예: `0x1A3F` 는 `"1A3F"` 로 보냅니다. 체크섬을 글자로 보낼 때 많이 씁니다(17장 예시 C). 받을 때 16진 글자가 아니면 그 메시지로 보지 않습니다.

### 11.6 BYTES, PAD

**BYTES** 는 바이트를 그대로 싣습니다. 식에서는 바이트 값으로 다룹니다.

| 화면 | JSON | 내용 |
| --- | --- | --- |
| Size (bytes) | `size` | 바이트 수(수 또는 식). 필수 |
| Value | `value` | 보낼 값. 바이트를 돌려주는 식(`read(…)`, `bytes(…)`, `x'0203'`)이나 글자(Encoding 으로 바이트로 바꿈). 길이가 Size 와 다르면 응답하지 않습니다 |
| Const | `const` | `02 03` 같은 16진 바이트열. 받을 때 바이트가 같아야 합니다 |

**PAD** 는 Size 만큼의 바이트를 Fill byte(`fill`, 0–255, 기본 0)로 채웁니다. Size 는 필수입니다. 받을 때는 내용을 보지 않고 건너뜁니다. 이름이나 값은 필요 없습니다.

### 11.7 BITS — 비트 묶음

```json
{ "name": "flags", "type": "BITS", "size": 1,
  "fields": [ { "name": "mode", "bit": 4, "bits": 3 },
              { "name": "run",  "bit": 0, "target": "운전" } ] }
```

Size 바이트(1–8, 높은 바이트가 앞)를 하나의 값으로 보고, 비트 필드마다 **자리를 직접** 지정합니다.
필드 속성 창의 **Bit fields** 표에서 비트 필드를 추가(**+ Bit field**)하고 고칩니다. 이 표는 속성 창 안에 있으므로 칸을 눌러 바로 고칩니다. 표는 비트 자리 순서로 정렬되고, 아래에 `3 of 8 bits used` 처럼 사용 중인 비트 수가 표시됩니다.

| 화면 (Bit fields 표) | JSON | 내용 |
| --- | --- | --- |
| Bit | `bit` | 시작 비트. **값의 가장 아래 비트가 0** 입니다(Size 1 이면 `0x01` 이 bit 0, `0x80` 이 bit 7). 장비 설명서의 "Bit0 … Bit7" 을 그대로 옮겨 적으면 됩니다 |
| Bits | `bits` | 폭(기본 1). Bit 4, Bits 3 이면 bit 4–6 입니다 |
| Name | `name` | 식에서 `body.flags.mode` 처럼 부를 이름. Target 이나 Const 가 있으면 비워도 됩니다 |
| Target / Const | `target` / `const` | 그 자리의 값. 0 이상의 정수입니다. 한 줄에는 둘 중 하나만 적습니다 |
| (JSON 화면에서만) | `value` | 보낼 값을 계산하는 식. 보낼 때의 우선순위는 Const, Value, Target 순서입니다(10.2절) |

- 표의 순서는 비트 자리와 관계가 없습니다. 비트 필드끼리 겹치거나 Size × 8 비트를 넘으면 오류입니다.
- 어느 비트 필드도 차지하지 않은 비트는 보낼 때 0 이고, 받을 때는 무시합니다. "사용 안 함" 비트를 채우려고 비트 필드를 만들 필요는 없습니다.
- 받을 때 비트 필드의 Const 가 다르면 그 메시지로 보지 않습니다.
- 보내는 메시지의 BITS 에서 값이 없는 비트 필드는 저장 오류입니다. 이름만 적은 비트 필드는 받는 메시지에서만 쓸 수 있습니다. 순차 Target 의 비트 집합 항목은 값이 없는 비트를 0 으로 보냅니다.
- 보낼 값은 정수여야 합니다. 메시지의 BITS 는 Target 값이 0.5 처럼 정수가 아니면 응답하지 않고, 순차 Target 의 비트 집합 항목은 반올림합니다.
- 비트 필드에는 When, Expect, Scale, Offset 을 두지 않습니다. 값이 비트 폭을 넘으면(2^Bits − 1 초과) BITS 필드의 On overflow 를 따릅니다.
- BITS 필드 자체에는 Count, When, Expect 를 둘 수 있고, 값(Const, Value, Target)은 비트 필드에 둡니다.
- 여러 바이트를 낮은 바이트부터 묶는 방식은 없습니다. 그런 장비는 바이트마다 Size 1 인 BITS 필드로 나눕니다.
- Bit order(`bitOrder`)는 Bit 를 비운 비트 필드가 앞 필드의 다음 자리부터 위로(`lsb first`, 기본) 또는 아래로(`msb first`) 놓이게 합니다.
  편집기에서 Bit 나 Bits 를 고치면 모든 비트 필드에 자리가 적히므로, 편집기에서 만든 BITS 에서는 쓰이지 않습니다.

### 11.8 변환 (Transform)

변환은 인코딩한 바이트를 한 번 더 바꿔서 보내는 규칙입니다. 받을 때는 거꾸로 되돌린 뒤 안쪽 필드를 읽습니다.
장비가 데이터를 그대로 보내지 않고 16진 글자로 바꾸거나 값을 더해서 보내는 경우에 씁니다. 변환은 두 곳에 걸 수 있습니다.

| 위치 | 화면 | 대상 |
| --- | --- | --- |
| 구조체를 쓰는 필드 | 필드 속성 창의 Transform | 그 구조체를 인코딩한 바이트 |
| 메시지 | 메시지 설정 카드의 Body transform | 그 메시지의 본문 전체 |

| op | 뜻 | 속성 | 쓰는 곳 |
| --- | --- | --- | --- |
| `ascii-hex` | 바이트 하나를 16진 글자 두 개로 | 대소문자(`upper` 기본, `lower`) | Modbus ASCII, YD/T 1363 |
| `add` | 바이트마다 값을 더함(256 으로 나눈 나머지) | 더할 값 | DL/T 645 의 +33H |
| `xor` | 바이트마다 값과 XOR | XOR 할 값 | 단순한 난독화 |

- 변환을 여러 개 적으면 위에서부터 차례로 적용하고, 받을 때는 반대 순서로 되돌립니다.
- 되돌린 바이트는 안쪽 필드가 남김없이 읽어야 합니다. 남으면 그 메시지로 보지 않습니다. `ascii-hex` 를 되돌릴 때 16진 글자가 아닌 것이 있어도 그 메시지로 보지 않습니다.
- 체크섬 Covers 의 `body` 나 구조체 필드는 **전송 바이트(변환 후)** 를 가리킵니다. 변환된 부분의 안쪽 필드는 Covers 로 가리킬 수 없으며, 변환된 부분 안에 체크섬 자리를 두지 않습니다.
- `sizeof(body)` 와 `sizeof(구조체)` 는 변환 후의 바이트 수입니다. 길이 필드에 그대로 쓸 수 있습니다.

**구조체 필드의 변환**

- 그 필드의 Size 는 **전송 바이트** 기준입니다. `ascii-hex` 를 거치면 두 배가 됩니다. Size 가 없으면 받을 때 둘러싼 크기의 끝까지를 변환 대상으로 봅니다.
- 배열(Count)이면 원소마다 따로 변환합니다.

**본문 전체의 변환 (Body transform)**

본문 안에서 여러 필드로 된 레코드가 반복되는 경우처럼, 구조체 하나로 묶을 수 없는 본문 전체를 변환할 때 씁니다. 구조체 안에는 구조체를 둘 수 없으므로(6장), 레코드 배열은 메시지 본문에 직접 두고 본문 전체를 변환합니다.

받을 때 본문의 끝은 이렇게 정해집니다.

| 경우 | 본문의 끝 |
| --- | --- |
| 프레임에 Body size 가 있을 때 | 그 크기 |
| 없을 때 | 받은 바이트의 끝에서 고정 크기 푸터만큼 뺀 곳. 푸터 크기가 정해지지 않으면(Terminator, When, Count, 식으로 된 Size) 저장 오류입니다 |

- Body size 없이 쓰면 저장할 때 경고가 나옵니다. TCP 에서는 그때까지 받은 바이트로 본문의 끝을 정하므로, 나뉘어 도착한 요청을 잘못 읽을 수 있습니다. TCP 장비라면 프레임에 Body size 를 둡니다.
- 헤더와 푸터는 변환하지 않습니다. 시작 글자와 끝 글자(SOI, EOI)는 헤더와 푸터의 Const 로 둡니다.

```json
{ "name": "get", "direction": "rx", "frame": "line", "bodyTransform": [ { "op": "ascii-hex" } ],
  "body": [ { "name": "addr", "type": "UINT", "size": 1 }, { "name": "cmd", "type": "UINT", "size": 1 } ],
  "match": "body.cmd == 0x42", "respond": [ { "reply": "data" } ] }
```

### 11.9 Value Simulation data type 과의 관계

Target 의 **값의 종류**(binary, integer, float)가 그 Target 에 등록할 수 있는 Value Simulation data type 을 정합니다.
이름 Target 과 패턴 Target 은 선언의 Value 로 정하고, Value 를 비웠거나 순차 Target 이면 그 Target 을 실은 표현으로 정합니다.

| 값의 종류 | 표현으로 정해지는 경우 | 등록할 수 있는 data type |
| --- | --- | --- |
| `binary` | BITS 의 1비트 비트 필드 | BINARY, INTEGER |
| `integer` | 정수 표현(`UINT`, `INT`, BCD, HEXNUM, Decimals 0 인 TEXTNUM)이면서 Scale·Offset 이 없음. BITS 의 여러 비트 비트 필드 | INTEGER |
| `float` | 위와 같은 표현에 Scale, Offset, Decimals 가 있음. `FLOAT` | INTEGER, FLOAT |

`binary` 나 `integer` 로 선언한 Target 을 소수를 나르는 표현(Scale, Offset, Decimals, FLOAT)에 실으면 오류입니다.
반대로 `binary` Target 을 UINT 1바이트나 TEXTNUM 한 글자 같은 정수 표현에 싣는 것은 괜찮습니다. 0/1 을 한 바이트나 한 글자로 보내는 장비가 있기 때문입니다.
TEXT, BYTES, 구조체에는 Target 을 실을 수 없습니다. Server Data 는 수만 담습니다.

### 11.10 타입별로 쓸 수 있는 속성

● 필수 · ○ 쓸 수 있음 · — 쓰지 않음(적어도 효과 없음) · ✕ 오류. 각 JSON 키의 화면 이름은 10장 표에 있습니다.

| 속성 (JSON) \ 타입 | UINT·INT·FLOAT | BCD | TEXT | TEXTNUM | HEXNUM | BYTES | PAD | BITS | 구조체 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `size` | ● 수만 | ● 수·식 | ○ 수·식 | ○ 수·식 | ●¹ 글자 수 | ● 수·식 | ● | ● 1–8 | ○ |
| `terminator` | — | — | ○² | ○² | ○¹² | — | — | — | — |
| `byteOrder` | ○ 크기별 4종 | ○ big·little | — | — | — | — | — | — | — |
| `encoding` | — | — | ○ | ○ | — (ASCII) | ○³ | — | — | — |
| `pad`, `align` | — | — | ○ 공백·left | ○ `0`·right | — | — | — | — | — |
| `decimals`, `point`, `sign` | — | — | — | ○ | — | — | — | — | — |
| `case` | — | — | — | — | ○ | — | — | — | — |
| `fill` | — | — | — | — | — | — | ○ | — | — |
| `bitOrder` | — | — | — | — | — | — | — | ○⁴ | — |
| `transform` | — | — | — | — | — | — | — | — | ○ |
| `scale`, `offset` | ○ | ○ | — | ○ | ○ | — | — | — | — |
| `onOverflow` | ○ (FLOAT 은 —) | ○ | — | ○ | ○ | — | — | ○ | — |
| `count`, `when` | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| `const` | ○ 수 | ○ 수 | ○ 글자 | ○ 수 | ○ 수 | ○ 16진 | — | ✕ (비트 필드에 ○) | ✕ |
| `expect` (받을 때) | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| `value` (보낼 때) | ○ | ○ | ○ | ○ | ○ | ○ | — | — (비트 필드에 ○) | — (안쪽 필드에) |
| `target` | ○ | ○ | ✕ | ○ | ○ | ✕ | ✕ | ✕ (비트 필드에 ○) | ✕ |
| `checksum` | ○ (FLOAT 은 권하지 않음) | ○ | ✕⁵ | ○ | ○ | ✕ | ✕ | ✕ | ✕ |

1. HEXNUM 은 Size 와 Terminator 중 하나가 필수입니다.
2. Size 와 함께 있으면 쓰이지 않습니다.
3. 보낼 값이 글자일 때 바이트로 바꾸는 데만 씁니다.
4. Bit 를 비운 비트 필드에만 의미가 있습니다.
5. 보낼 때는 체크섬 값을 10진 글자로 싣지만, 받을 때는 글자를 수로 비교하지 못합니다. 체크섬을 글자로 주고받으려면 HEXNUM 이나 TEXTNUM 을 씁니다.

**순차 Target 항목**에는 필드 속성 중 Type, Size, 표현 속성, Target·Const·빈 칸, At address, Access, Description 만 씁니다(5.2절). When, Count, Value, Expect, Checksum, Transform 은 적어도 쓰이지 않으며, 구조체는 고정 크기가 아니므로 쓸 수 없습니다.
**이름 Target 과 패턴 Target 의 항목**에는 Target(패턴은 Pattern), Value, Access, Description 만 씁니다(5.3절, 5.4절).

### 11.11 효과가 없는 조합과 주의할 조합

아래 조합은 저장은 되지만 아무 효과가 없습니다. Problems 가 경고하는 것은 (경고)로 표시했습니다.
편집기는 Type 에 맞지 않는 칸을 대부분 보여 주지 않으므로, 이런 조합은 주로 JSON 을 직접 고치거나 파일을 가져올 때 생깁니다.

| 조합 | 실제 동작 |
| --- | --- |
| 받는 메시지 본문 필드의 Value | 받을 때는 읽기만 하므로 쓰이지 않습니다 |
| 보내는 메시지 필드의 Expect | 보낼 때는 검사하지 않습니다 |
| 보내는 메시지의 Match, Total size, Respond / 받는 메시지의 Set frame fields | 쓰이지 않습니다(경고) |
| 프레임을 고른 메시지의 Header, Footer | 쓰이지 않습니다(경고) |
| Size 와 Terminator 를 함께 적음 | Size 만 씁니다. 보낼 때 끝 글자를 붙이지 않고, 받을 때도 찾지 않습니다 |
| HEXNUM 의 Encoding, Pad character, Align, Byte order | 쓰이지 않습니다(항상 ASCII, 왼쪽 `0` 채움) |
| TEXT, BYTES, PAD 의 Scale, Offset / TEXT 의 On overflow | 쓰이지 않습니다. TEXT 는 길이를 넘으면 항상 오류입니다 |
| PAD 의 Const, Value | 쓰이지 않습니다. Fill byte 를 보냅니다 |
| 1바이트 숫자형의 Byte order | 쓰이지 않습니다 |
| 모든 비트 필드에 Bit 가 있는 BITS 의 Bit order | 쓰이지 않습니다 |
| Const 가 있는 필드의 Set frame fields, Value, Target | Const 가 우선하므로 쓰이지 않습니다(10.2절) |
| 체크섬 자리의 Const, Value, Target (보낼 때) | 계산한 값으로 덮어쓰므로 쓰이지 않습니다 |
| 체크섬 Over 의 `before transforms` | 아직 적용되지 않습니다. 항상 전송 바이트로 계산합니다(18장) |

아래 조합은 저장은 되지만 의도와 다르게 동작할 수 있으므로 주의합니다.

| 조합 | 문제 |
| --- | --- |
| TEXTNUM 의 Decimal point `implied` 에 Decimals 와 Scale 을 함께 적음 | 숨은 소수 자리가 두 번 적용됩니다(11.4절) |
| 체크섬 자리에 Scale, Offset | 보낼 때는 raw 로 채우지만 받을 때는 물리값으로 비교하므로 항상 틀립니다. 체크섬 자리에는 두지 않습니다 |
| Match 나 Expect 의 식이 없는 필드를 부름 | 저장은 되지만 받을 때마다 그 메시지가 아닌 것으로 처리됩니다(9.3절) |
| 마지막 필드가 아닌 TEXT, BYTES 에 Size 와 Terminator 가 없음 | 둘러싼 크기의 끝까지 읽어 버려 뒤 필드를 읽지 못합니다(경고, 10.5절) |
| 긴 받는 메시지를 짧은 메시지보다 앞에 둠 | TCP 에서 앞 메시지가 바이트를 기다리는 동안 뒤 메시지가 처리되지 않습니다(9.3절) |
| `data('이름')` 의 이름 오타 | 알림 없이 0 이 됩니다. Target 을 필드의 Target 칸으로 실으면 선언과 맞춰 보고 알려 줍니다 |

---

## 12. 식과 조건

Match, When, Expect, Value, Size, Count, Set frame fields, Args for the reply, Body size, Write to Server Data, 체크섬 EXPR 의 Init·Step·Final 칸에 적는 식은 모두 같은 문법을 씁니다.
체크섬의 Covers 는 식이 아니라 경로입니다(8.1절). 식의 문법은 저장할 때 검사합니다(12.6절).
정의는 JSON 문서이므로 식 안의 글자는 작은따옴표로 씁니다.

### 12.1 값을 적는 법

| 종류 | 예 |
| --- | --- |
| 정수 | `125`, `0x80`, `0b1010`. 음수는 앞에 `-` 를 붙입니다 |
| 실수 | `0.1`, `2.5e3`. 소수점 앞뒤에 숫자가 있어야 합니다(`.5` 나 `5.` 는 쓸 수 없습니다) |
| 글자 | `'R'`, `'전압'`. 특수 문자는 `\'`, `\\`, `\r`, `\n`, `\t`, `\xHH` 로 씁니다 |
| 바이트 | `x'0203'`. 16진 숫자 두 개가 한 바이트이며, `x'02 03'` 처럼 띄어 써도 됩니다 |
| 참, 거짓 | `true`, `false` |
| 목록 | `[1, 2, 3]`. 주로 `in` 의 오른쪽에 씁니다 |

### 12.2 연산자

위에 있는 연산자가 먼저 계산됩니다.

| 순위 | 연산자 |
| --- | --- |
| 1 | `a.b`, `a[i]`, `f(…)` |
| 2 | 단항 `-` `!` `~` |
| 3 | `*` `/` `//` `%` (`/` 는 항상 실수 나눗셈, `//` 는 몫을 내림한 정수, `%` 는 그 나머지) |
| 4 | `+` `-` (한쪽이 글자이거나 둘 다 바이트이면 `+` 는 이어 붙임) |
| 5 | `<<` `>>` |
| 6 | `<` `<=` `>` `>=` `in` |
| 7 | `==` `!=` |
| 8 | `&` |
| 9 | `^` |
| 10 | `\|` |
| 11 | `&&` |
| 12 | `\|\|` |
| 13 | `c ? a : b` |

- `&&` 와 `||` 는 왼쪽만으로 결과가 정해지면 오른쪽을 계산하지 않습니다. `c ? a : b` 도 고른 쪽만 계산합니다.
  그래서 `has(body.extra) && body.extra > 0` 처럼, 없을 수 있는 필드를 먼저 확인한 뒤에 부를 수 있습니다.
- `a in [1, 2, 3]` 은 목록에 `a` 와 같은 값이 있는지 봅니다. 오른쪽이 바이트이면 그 바이트 중에 `a` 와 같은 값이 있는지 봅니다(`0x03 in body.data`). 오른쪽에 다른 값이 오면 오류입니다.
- `<<`, `>>`, `&`, `^`, `|`, `~` 는 정수에만 씁니다. `>>` 는 부호를 유지한 채 옮깁니다.

### 12.3 값의 종류

식이 다루는 값은 정수(64비트), 실수, 참·거짓, 글자, 바이트, 목록입니다.
정수와 실수는 섞어서 계산할 수 있지만, 비트 연산은 정수에만 씁니다. **조건을 적는 자리에는 참·거짓만** 올 수 있으며, 0 과 1 을 참·거짓 대신 쓸 수 없습니다(12.6절).

- **계산 결과**: 정수끼리의 `+`, `-`, `*` 는 정수이고, 실수가 섞이면 실수입니다. `/` 는 항상 실수입니다.
- **정수가 필요한 자리**: 비트 연산, `%`, 배열 번호 같은 자리에는 소수가 없는 실수(`220.0`)도 정수로 받아 줍니다. `0.5` 처럼 소수가 있으면 오류입니다.
- **음수의 몫과 나머지**: `//` 와 `%` 는 내림 기준입니다. `-7 // 2` 는 −4, `-7 % 2` 는 1 입니다. `%` 는 정수에만 씁니다.
- **글자 잇기**: `+` 의 한쪽이 글자이면 다른 쪽을 `text()` 로 바꿔 이어 붙입니다. `'ch' + 3` 은 `'ch3'` 이고, `'v' + 2.50` 은 `'v2.5'` 입니다. 바이트끼리 `+` 하면 바이트를 이어 붙입니다.
- **같음 비교**: 수는 값으로 비교하므로 `1 == 1.0` 은 참입니다. 글자와 바이트는 내용이 같으면 참입니다. 종류가 다른 값은 오류 없이 같지 않은 것으로 봅니다(`'1' == 1` 은 거짓).
- **크기 비교**: `<`, `<=`, `>`, `>=` 는 수끼리와 글자끼리만 비교합니다. 글자는 사전 순서로 비교하며, 그 밖의 조합은 오류입니다.
- **배열 원소**: `body.values[2]` 처럼 0부터 시작하는 번호로 꺼냅니다. 번호는 `[i + 1]` 처럼 식으로 적어도 되며, 범위를 넘으면 오류입니다.
  바이트(BYTES 필드의 값)는 `[i]` 로 꺼낼 수 없습니다. 바이트 하나씩 읽어야 하면 UINT 1바이트 필드의 배열(Count)로 받습니다.

### 12.4 함수

| 함수 | 결과 | 내용 |
| --- | --- | --- |
| `data(target)` | 수 | Target 의 물리값. 값이 없으면 0 입니다(5.5절). 이름은 `data('ch' + text(n))` 처럼 식으로 만들어도 됩니다 |
| `read(map, address, count)` | 바이트 | 순차 Target 을 인코딩한 바이트(5.2절) |
| `fits(map, address, count)` | 참·거짓 | 그 범위를 온전히 읽을 수 있는지 |
| `writable(map, address, count)` | 참·거짓 | `fits` 가 참이고 모든 항목의 Access 가 `rw` 인지 |
| `slots(map)` | 정수 | 순차 Target 의 칸 수 |
| `sizeof(path)` | 정수 | 필드나 영역을 인코딩한 바이트 수. 인자에는 `body.values` 같은 경로만 씁니다 |
| `len(x)` | 정수 | 배열의 원소 수, 글자의 글자 수, 바이트의 바이트 수. 그 밖의 값은 오류입니다 |
| `has(path)` | 참·거짓 | 조건부 필드가 있는지. 인자에는 경로만 씁니다 |
| `valid(checksum)` | 참·거짓 | 받은 프레임에서 그 체크섬이 맞았는지(On mismatch 가 `accept · valid() false` 일 때). 예: `valid('bcc')` |
| `min(a, b)` `max(a, b)` | 수 | 두 값 중 작은 값, 큰 값. 인자는 두 개입니다 |
| `abs(x)` | 수 | 절댓값. 정수는 정수로 돌려줍니다 |
| `round(x)` | 정수 | 가장 가까운 정수. .5 는 0 에서 먼 쪽으로 올립니다(`round(2.5)` 는 3, `round(-2.5)` 는 −3) |
| `floor(x)` `ceil(x)` | 정수 | 내림, 올림 |
| `int(x)` | 정수 | 실수는 소수점 아래를 버립니다(`int(-2.7)` 은 −2). 글자는 정수로 읽으며, `'1.5'` 처럼 정수가 아닌 글자는 오류입니다 |
| `real(x)` | 실수 | 수나 글자를 실수로 바꿉니다(`real('1.5')`) |
| `text(x)` | 글자 | 사람이 읽는 글자로 바꿉니다. `text(3)` 은 `'3'`, `text(220.0)` 은 `'220'`, `text(0.10)` 은 `'0.1'`, 바이트는 `'01 03 0A'` 같은 16진 글자, 참·거짓은 `'true'`, `'false'` 입니다 |
| `hex(x[, width])` | 글자 | 대문자 16진 글자. `hex(26, 2)` 는 `'1A'`, `hex(5, 4)` 는 `'0005'`. 폭을 생략하면 필요한 만큼이며, 음수는 64비트 2의 보수(16글자)가 됩니다 |
| `substr(t, start[, n])` | 글자 또는 바이트 | `t` 의 `start` 번째(0부터)부터 `n` 개. `t` 가 글자이면 글자를, 바이트이면 바이트를 돌려줍니다. `n` 을 생략하면 끝까지이고, 범위를 넘는 부분은 있는 만큼만 돌려줍니다 |
| `find(t, s)` | 정수 | 글자 `t` 에서 글자 `s` 가 처음 나오는 위치(0부터). 없으면 −1 입니다. `find('PMKD', 'K')` 는 2 |
| `bytes(x)` | 바이트 | 글자를 UTF-8 바이트로 바꿉니다. 바이트는 그대로 두고, 수는 오류입니다 |
| `pack(x, size[, order])` | 바이트 | 정수 `x` 를 `size` 바이트(1–8)로 바꿉니다. `order` 는 `'big'`(기본) 또는 `'little'` 이며, 넘치는 윗자리는 버립니다. `pack(0x1234, 2, 'little')` 은 `x'3412'` |
| `unpack(b[, order])` | 정수 | 바이트 `b`(1–8바이트)를 부호 없는 정수로 읽습니다. `order` 는 `pack` 과 같습니다. `unpack(x'3412', 'little')` 은 0x1234 |
| `any(list, 조건)` `all(list, 조건)` | 참·거짓 | 배열의 원소마다 조건을 계산해, 하나라도 참이면(`any`) 또는 모두 참이면(`all`) 참입니다. 조건 안에서 `it` 이 지금 원소입니다. 예: `any(body.vars, it.address > 999)`. 빈 배열에서 `any` 는 거짓, `all` 은 참입니다 |
| `now([part])` | 정수 | 서버 시각. `'year'`, `'month'`, `'day'`, `'hour'`, `'minute'`, `'second'`, `'epoch'` 중 하나이며, 생략하면 `'epoch'`(1970-01-01 00:00 UTC 부터의 초)입니다 |

### 12.5 식을 쓰는 곳

| 자리 | 결과 | 부를 수 있는 것 |
| --- | --- | --- |
| Match, Expect, 응답 규칙의 When | 참·거짓 | 이 메시지의 필드, `$` 파라미터와 상수, 함수. Expect 에서는 `this` |
| 필드의 When, Size, Count, 프레임의 Body size | 참·거짓 또는 정수 | 받을 때는 이 필드보다 앞에 있는 필드만, 보낼 때는 어느 필드든 |
| 필드의 Value, Set frame fields, Args for the reply | 값 | 보내는 메시지의 필드, `req.`, `args.`, `$`, 함수, `i` |
| Write to Server Data 의 Value, Address, Bytes | 값 | 받는 메시지의 필드, `$`, 함수 |
| 체크섬 EXPR 의 Init, Step, Final | 정수 | `acc`(지금까지 계산한 값)와 `b`(지금 바이트), 계산 함수(`min`, `round`, `hex` 등). Init 에서는 `acc` 와 `b` 를 쓸 수 없습니다. 필드, `$`, `data`·`read` 같은 Server Data 함수, `sizeof`, `has`, `valid` 는 쓸 수 없습니다 |

체크섬 EXPR 의 Init 을 비우면 0 에서 시작하고, Final 을 비우면 마지막 `acc` 가 결과입니다. 결과는 정수여야 합니다(8.2절).

### 12.6 식의 오류

| 언제 드러나는지 | 확인하는 것 | 결과 |
| --- | --- | --- |
| 저장할 때 | 문법, 함수 이름과 인자 수, `$이름` 이 Parameters 나 Constants 에 있는지(`$이름` 은 고치는 동안에도 Problems 에 표시됩니다) | 저장되지 않습니다. `The server did not save it.` 메시지와 함께 Problems 에 위치(메시지, 필드, 칸)가 표시됩니다 |
| Device 가 동작할 때(시험 사례 포함) | 경로가 실제 필드인지, 받을 때 아직 읽지 않은 뒤쪽 필드를 부르는지, 조건 자리가 참·거짓인지, 0 으로 나누는지, 배열 범위를 넘는지, 정수가 필요한 자리에 실수가 오는지 | 받는 중이면 그 메시지가 아닌 것으로 처리합니다(9.3절). 응답을 만드는 중이면 응답하지 않습니다. 어느 경우든 Settings 의 Realtime Logs 에 남습니다 |

`body.qantity` 같은 경로 오타는 저장할 때 잡히지 않습니다. 시험 사례(13장)로 확인합니다.

---

## 13. 시험 사례 (Tests)

시험 사례는 "이 바이트를 받으면 이 바이트로 응답해야 한다" 를 적어 둔 것입니다. 정의를 저장할 때 모두 실행되며, 하나라도 실패하면 저장되지 않습니다.

| 화면 | JSON | 내용 |
| --- | --- | --- |
| Test n 옆 칸 | `name` | 사례 이름 |
| Send | `send` | 장비가 받을 바이트(16진). `01 03 00 00 00 0A C5 CD` 처럼 띄어 써도 됩니다 |
| Expect | `expect` | 장비가 보내야 할 바이트(16진) |
| No reply | `expect: null` | 응답하지 않아야 하는 경우 |
| Server Data before | `data` | 보내기 전에 Server Data 에 넣을 값. 한 줄에 하나씩 `Target = 값`(물리값)으로 적습니다 |
| Expected writes | `expectWrites` | 처리 뒤 Server Data 에 있어야 할 값. 한 줄에 하나씩 `Target = 값` 으로 적습니다 |
| Parameters | `params` | 이 사례에서 쓸 파라미터. 한 줄에 하나씩 `이름 = 값` 으로 적고, 비워 두면 Default 를 씁니다 |

- **Run tests** 는 저장하지 않고 사례만 실행합니다. 사례마다 Pass 또는 Fail 이 표시되고, 실패하면 Expected 와 Actual 바이트, 실패 이유가 나타납니다.
- 사례는 하나의 Device 에서 **순서대로** 실행됩니다. 앞 사례의 Server Data before 와 Write 결과가 뒤 사례에도 남아 있으므로, 값이 중요하면 사례마다 다시 적습니다.
- 시험 사례는 "저장은 되지만 맞지 않는 메시지"(9.3절, 14.3절)를 찾는 가장 좋은 방법입니다. 메시지마다 맞아야 하는 사례 하나와, 맞지 않아야 하는 사례(다른 국번, 틀린 체크섬) 하나를 두는 것을 권합니다.
- 실제 장비나 상위 시스템이 보낸 바이트를 그대로 Send 에 넣어 실행해 보면 어디서 어긋나는지 가장 빨리 찾을 수 있습니다(19장).

---

## 14. 저장할 때 확인하는 것

### 14.1 오류: 저장되지 않음

Problems 에 오류가 있으면 Save 가 막힙니다. 버튼 옆에 `Fix the N errors first.` 가 나타나고 Problems 목록이 자동으로 열리며, 오류를 누르면 해당 위치로 이동합니다.
저장할 때는 한 번 더 검사하고 시험 사례를 모두 실행합니다. 여기서 걸려도 저장되지 않으며, `The server did not save it.` 과 함께 Problems 에 이유가 표시됩니다.

1. 형식: 정의 이름, 메시지가 하나 이상, 받는 메시지가 하나 이상
2. 이름: 식별자 규칙, 예약어(필드·비트 필드의 `header`, `true` 등, 메시지의 `none`, `echo`, 3장), 이름 겹침(메시지, 같은 범위의 필드, 구조체, 체크섬, Target 묶음, 파라미터와 상수, Target 선언), 다른 정의와 같은 정의 이름
3. 가리키는 대상이 있는지: 프레임, 응답 규칙의 Reply, 체크섬, 구조체(구조체 안에서는 쓸 수 없음, 6장), Set frame fields 의 필드, Write 의 Sequence
4. 식: 문법, 함수 이름과 인자 수, `$이름`(12.6절)
5. 파라미터: Value, Default, Min, Max(4.2절)
6. 타입별 필수 속성과 값: 숫자형의 Size 와 Byte order(11.1절), BCD 와 PAD 의 Size, HEXNUM 의 Size 또는 Terminator, BITS 의 Size 와 비트 자리(겹침, 범위), 구조체 필드에만 쓰는 Transform, Body transform 에서 본문의 끝을 알 수 있는지(11.8절), Const 의 형식, 0 이 아닌 Scale
7. 값: 보내는 메시지와 프레임의 필드가 모두 값을 가지는지(10.2절, 10.7절)
8. Target: 이름·패턴 Target 선언에 표현 속성이 없는지, Value 의 형식, 이름 Target 과 비트를 Type 없이 싣지 않았는지, 수를 나르지 않는 타입에 싣지 않았는지, 값의 종류와 표현이 맞는지(11.9절)
9. 순차 Target: 항목이 고정 크기인지, 번지가 겹치지 않는지, 비트 집합 항목에 자체 Target 이 없는지
10. 체크섬 정의: 알고리즘, 직접 정한 CRC 의 값, EXPR 의 Step, Covers 의 양쪽 끝
11. 응답 규칙: Reply 가 비어 있지 않은지, Write 하는 Target 의 Access 가 `rw` 인지(`read and write` 로 바꿉니다)
12. 시험 사례가 모두 통과하는지

### 14.2 경고: 저장은 되지만 확인이 필요함

- Body size 없이 쓴 Body transform(11.8절)
- 선언하지 않은 Target 을 필드에 싣거나 Write 하는 경우
- 값의 종류를 선언하지 않은 Target 이 필드마다 다른 종류로 실린 경우(5.8절)
- Size 와 Terminator 가 모두 없는 TEXT, TEXTNUM(10.5절)
- 응답 규칙이 없는 받는 메시지, 보내는 메시지의 Match·Respond, 받는 메시지의 Set frame fields, 프레임을 고른 메시지의 Header·Footer(적어도 쓰이지 않음)
- 시험 사례의 Server Data before 에 선언하지 않은 Target

### 14.3 저장할 때 확인하지 않는 것

아래 항목은 저장할 때 확인하지 않습니다. 시험 사례(13장)로 확인합니다.

- 식의 경로가 실제 필드인지(오타), 받을 때 아직 읽지 않은 뒤쪽 필드를 부르는지, 조건 자리가 참·거짓인지(12.6절)
- 체크섬 Covers 의 경로가 그 메시지에 있는지, 체크섬끼리 서로를 품는지, 자리의 폭이 결과에 맞는지(8.5절)
- TEXT 가 Size 보다 긴지, ASCII 밖의 글자가 있는지, BYTES 값의 길이, 구조체의 크기
- 값의 범위 초과(On overflow `error`), 0 으로 나누기, 배열 범위, 정수가 아닌 비트 값

받는 중에 이런 문제가 생기면 그 메시지가 아닌 것으로 처리하고 다음 메시지를 시도합니다. 응답을 만드는 중에 생기면 응답하지 않습니다. 어느 경우든 Settings 의 **Realtime Logs** 에 Device 이름과 함께 기록됩니다.

---

## 15. Device 연결

### 15.1 Device 에 정의 지정하기

**Generic Device**: Device 편집기에서 Protocol 을 Generic 으로 고르면 **Protocol definition** 카드가 나타납니다.

| 칸 | 내용 |
| --- | --- |
| Definition | 저장된 정의 중 하나를 고릅니다. 필수입니다 |
| 파라미터 칸 | 정의의 파라미터마다 한 칸씩 있습니다. 옆에 `integer · 1–247` 처럼 값의 종류와 범위가 표시됩니다. 비워 두면 Default 를 씁니다 |
| **Open** | 그 정의를 새 탭의 편집기로 엽니다 |

Device 를 저장할 때 모르는 파라미터 이름, 수가 아닌 값, integer 인데 정수가 아닌 값, Min·Max 를 벗어난 값, Default 가 없는데 비워 둔 칸을 검사합니다.
Device 의 Communication Settings 에는 정의의 id 와 파라미터 값만 저장됩니다.

```json
{ "definitionId": 12, "params": { "unitId": 3 } }
```

정의가 지정되지 않은 Generic Device 는 포트는 열지만 요청에 응답하지 않으며, 요청이 올 때마다 Realtime Logs 에
`Device "이름" has no usable protocol definition` 을 남깁니다.

**MK RCU Device**: 정의 지정은 선택입니다. 정의는 **RS(시리얼 중계) 요청의 본문**만 해석하며, RCU 의 포트·길이·CRC 처리와 DI, AI, DO 는 정의와 관계없이 CTS 가 처리합니다.
RS 본문 하나를 프레임 하나로 다룹니다(UDP 와 같은 방식). 정의가 없으면 RS 요청에는 응답하지 않고, 나머지 기능은 그대로 동작합니다.

**일괄 등록**: 엑셀의 Communication Settings 칸에 정의를 **이름으로** 적습니다. 파일을 올린 뒤 화면의 Protocol definition 칸에서 고칠 수도 있으며, 검사 규칙은 Device 편집기와 같습니다.

```json
{"definition": "power-meter", "params": {"unitId": 3}}
```

### 15.2 값 넣기

정의를 지정한 Device 는 정의의 Target 목록(5.7절)을 기준으로 동작합니다.

| 기능 | 동작 |
| --- | --- |
| Value Simulation | Target 을 목록에서 고릅니다. 목록에 없는 Target 은 등록되지 않습니다(`No target "X" in protocol definition "…"`). DataType 은 그 Target 의 값의 종류(11.9절)가 허용하는 것만 고를 수 있습니다 |
| Server Data 화면 | 목록의 Target 이 순서대로 모두 표시됩니다. 값이 없으면 0 입니다 |
| REST API 쓰기 | 목록에 있는 Target 이면 Simulation 이 없어도 쓸 수 있습니다. 목록에 없으면 501 을 돌려줍니다 |
| Test Plan | 쓰기 단계의 Target 이 목록에 있어야 합니다 |
| Error Simulation | 시스템 오류(DISCARD, TIMEOUT, GARBAGE, DISCONNECT)가 그대로 적용됩니다(16장) |

### 15.3 정의를 고쳐 저장하면

- 그 정의를 쓰는 모든 Device 가 **연결을 유지한 채 다음 요청부터** 새 정의로 응답합니다. 재시작할 필요가 없습니다.
- Server Data 의 값은 그대로 남습니다.
- Target 을 지우거나 이름을 바꾸면, 그 Target 을 쓰던 Value Simulation 은 계속 동작하지만 값이 응답에 실리지 않습니다. Simulation 의 Target 도 함께 고칩니다.
- 파라미터를 추가하거나 범위를 좁혔다면, 그 정의를 쓰는 Device 의 값도 확인합니다. 이미 저장된 Device 설정은 다시 검사하지 않습니다.

---

## 16. 응답 지연과 오류 시험

- **응답 지연**: 응답 규칙의 Delay(`delayMs`)에 적은 시간만큼 응답을 늦게 보냅니다. 다른 연결의 응답은 기다리지 않습니다.
- **시스템 오류**: Error Simulation 의 DISCARD, TIMEOUT, GARBAGE, DISCONNECT 는 정의와 관계없이 그대로 적용됩니다.
- 응답 규칙의 Name 은 적어 둘 수 있지만, Error Simulation 에서 규칙 단위로 오류를 지정하는 기능은 아직 없습니다(18장).

---

## 17. 예시

아래 세 예시는 Protocol Definitions 목록에서 **Add** 를 누른 뒤 "or start from an example" 에서 바로 열 수 있습니다
(Modbus RTU power meter, STX … BCC ETX controller, ASCII line meter). 시험 사례가 들어 있으므로 열자마자 **Run tests** 로 실행해 볼 수 있습니다.
예시 D 는 변환을 적는 방법만 보여 주는 일부분입니다.

같은 목록의 **LS XGT FEnet (XGK)** 는 실제 PLC 프로토콜을 옮긴 큰 예시라 이 장에 원문을 싣지 않았습니다. 편집기로 열어 각 항목의 설명과 시험 사례 35개를 보면 됩니다.
변수 이름(`%DW100`)을 구조체로 나눠 받고 `find()` 로 디바이스 영역을 고르는 방법, `pack()`·`unpack()`·`substr()` 로 비트와 바이트를 워드에서 꺼내고 고쳐 쓰는 방법,
`any()` 로 블록 여러 개를 한 번에 검사하는 방법, Repeat 로 블록마다 쓰는 방법을 보여 줍니다.

### 예시 A — Modbus RTU 전력 계측기 (순차 Target, 비트 묶기, 쓰기, 예외 응답)

코일 10개(FC01 읽기, FC05 쓰기), 접점 입력 10개(FC02 읽기), 보유 레지스터 10개(FC03 읽기, FC06·FC16 쓰기)를 갖춘 장비입니다.
보유 레지스터 0~6 은 16비트이고, 전력량은 32비트라 7~8 두 칸을 씁니다. 측정값은 읽기 전용이고 CT비(9)와 경보전력(10)만 쓸 수 있습니다.
그 밖의 기능 코드에는 예외 01 로 응답합니다.

| 기능 코드 | 받는 메시지 | 응답 | 예외 |
| --- | --- | --- | --- |
| FC01 코일 읽기 | `readCoils` | `readBitsReply` (비트 8개를 한 바이트에) | 수량이 1~2000 밖이면 03, 범위 밖이면 02 |
| FC02 접점 입력 읽기 | `readInputs` | `readBitsReply` | FC01 과 같음 |
| FC03 보유 레지스터 읽기 | `readHolding` | `readHoldingReply` (`read`) | 수량이 1~125 밖이면 03, `fits` 가 거짓이면 02 |
| FC05 코일 하나 쓰기 | `writeCoil` | `echo` (요청 그대로) | 값이 `FF00`·`0000` 이 아니면 03, 쓸 수 없는 번지면 02 |
| FC06 레지스터 하나 쓰기 | `writeRegister` | `echo` | 쓸 수 없는 번지면 02 |
| FC16 레지스터 여러 칸 쓰기 | `writeRegisters` | `writeRegistersReply` (시작 번지·수량) | 수량이 1~123 밖이거나 바이트 수가 수량×2 가 아니면 03, 쓸 수 없는 범위면 02 |
| 그 밖 (FC04 등) | `otherFunction` | `exception` | 01 |

- **비트 묶기(FC01·FC02)**: 응답의 `status` 는 바이트 배열(Count)이고, Value 를 바이트마다 다시 계산합니다(10.3절).
  `i` 번째 바이트의 비트 b 는 번지 `start + 8i + b` 의 Target 값이며, 요청한 수량을 넘는 비트는 0 입니다.
  Target 이름은 `args.prefix + text(번지 + 1)` 로 만들어 `data()` 로 읽습니다(12.4절). 응답 메시지 하나를 두 기능 코드가 Args 의 `prefix`(`'출력'`, `'입력'`)만 바꿔 씁니다.
- **번지로 정해지는 쓰기(FC05)**: Write to Server Data 의 Target 칸에는 고정된 이름만 적을 수 있습니다. 그래서 코일을 칸 하나가 1바이트인 순차 Target 으로 선언하고,
  Sequence·Address·Bytes 쓰기로 그 번지에 `x'01'`(켜기) 또는 `x'00'`(끄기)을 씁니다(5.6절).
- **쓰기 허용**: `writable` 은 범위가 온전하고 모든 항목의 Access 가 `rw` 일 때만 참입니다. 측정값 레지스터나 전력량의 절반에 쓰면 예외 02 입니다.
- **시험 사례**: 한 장비에서 차례로 실행되므로(13장) 앞에서 쓴 값이 뒤의 읽기에 그대로 나타납니다. FC05 뒤의 FC01, FC16 뒤의 FC03 사례가 그 예입니다.

```json
{
  "format": "cts-protocol",
  "version": 1,
  "name": "power-meter",
  "doc": "Modbus RTU 전력 계측기. 코일(FC01 읽기·FC05 쓰기), 접점 입력(FC02), 보유 레지스터(FC03 읽기·FC06·FC16 쓰기)를 지원한다. 전력량은 32비트라 레지스터 7~8 두 칸을 쓴다. 그 밖의 기능 코드는 예외 01.",
  "params": {"unitId": {"value": "integer", "default": 1, "min": 0, "max": 255, "doc": "장비 번호"}},
  "checksums": {"crc": {"algorithm": "CRC-16/MODBUS", "covers": {"from": "header", "to": "body"}}},
  "framing": {"maxLength": 256, "idleFlushMs": 50},
  "targets": {
    "coils": {
      "kind": "sequence",
      "base": 0,
      "slotSize": 1,
      "doc": "코일 0~9 (FC01 읽기, FC05 쓰기). 한 칸이 코일 하나, 값은 0 또는 1",
      "items": [
        {"target": "출력1", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력2", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력3", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력4", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력5", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력6", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력7", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력8", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력9", "type": "UINT", "size": 1, "access": "rw"},
        {"target": "출력10", "type": "UINT", "size": 1, "access": "rw"}
      ]
    },
    "inputs": {
      "kind": "sequence",
      "base": 0,
      "slotSize": 1,
      "doc": "접점 입력 0~9 (FC02 읽기). 값은 0 또는 1",
      "items": [
        {"target": "입력1", "type": "UINT", "size": 1},
        {"target": "입력2", "type": "UINT", "size": 1},
        {"target": "입력3", "type": "UINT", "size": 1},
        {"target": "입력4", "type": "UINT", "size": 1},
        {"target": "입력5", "type": "UINT", "size": 1},
        {"target": "입력6", "type": "UINT", "size": 1},
        {"target": "입력7", "type": "UINT", "size": 1},
        {"target": "입력8", "type": "UINT", "size": 1},
        {"target": "입력9", "type": "UINT", "size": 1},
        {"target": "입력10", "type": "UINT", "size": 1}
      ]
    },
    "holding": {
      "kind": "sequence",
      "base": 0,
      "slotSize": 2,
      "doc": "보유 레지스터 0~10 (FC03 읽기, FC06·FC16 쓰기). 측정값은 읽기 전용, CT비·경보전력만 쓸 수 있다",
      "items": [
        {"target": "전압1", "type": "UINT", "size": 2, "scale": 0.1},
        {"target": "전압2", "type": "UINT", "size": 2, "scale": 0.1},
        {"target": "전압3", "type": "UINT", "size": 2, "scale": 0.1},
        {"target": "전류1", "type": "UINT", "size": 2, "scale": 0.01},
        {"target": "전류2", "type": "UINT", "size": 2, "scale": 0.01},
        {"target": "전류3", "type": "UINT", "size": 2, "scale": 0.01},
        {"target": "전력", "type": "UINT", "size": 2},
        {"target": "전력량", "type": "UINT", "size": 4, "scale": 0.1},
        {"target": "CT비", "type": "UINT", "size": 2, "access": "rw"},
        {"target": "경보전력", "type": "UINT", "size": 2, "access": "rw"}
      ]
    }
  },
  "frames": {
    "rtu": {
      "header": [
        {"name": "unitId", "type": "UINT", "size": 1, "value": "req.header.unitId"},
        {"name": "function", "type": "UINT", "size": 1, "value": "req.header.function"}
      ],
      "footer": [{"name": "crc", "type": "UINT", "size": 2, "byteOrder": "little", "checksum": "crc"}]
    }
  },
  "messages": [
    {
      "name": "readCoils",
      "direction": "rx",
      "frame": "rtu",
      "match": "header.function == 1",
      "doc": "FC01 코일 읽기",
      "body": [{"name": "start", "type": "UINT", "size": 2}, {"name": "quantity", "type": "UINT", "size": 2}],
      "respond": [
        {"when": "header.unitId != $unitId", "reply": "none"},
        {"when": "body.quantity < 1 || body.quantity > 2000", "reply": "exception", "args": {"code": 3}},
        {"when": "!fits('coils', body.start, body.quantity)", "reply": "exception", "args": {"code": 2}},
        {"reply": "readBitsReply", "args": {"prefix": "'출력'"}}
      ]
    },
    {
      "name": "readInputs",
      "direction": "rx",
      "frame": "rtu",
      "match": "header.function == 2",
      "doc": "FC02 접점 입력 읽기",
      "body": [{"name": "start", "type": "UINT", "size": 2}, {"name": "quantity", "type": "UINT", "size": 2}],
      "respond": [
        {"when": "header.unitId != $unitId", "reply": "none"},
        {"when": "body.quantity < 1 || body.quantity > 2000", "reply": "exception", "args": {"code": 3}},
        {"when": "!fits('inputs', body.start, body.quantity)", "reply": "exception", "args": {"code": 2}},
        {"reply": "readBitsReply", "args": {"prefix": "'입력'"}}
      ]
    },
    {
      "name": "readHolding",
      "direction": "rx",
      "frame": "rtu",
      "match": "header.function == 3",
      "doc": "FC03 보유 레지스터 읽기",
      "body": [{"name": "start", "type": "UINT", "size": 2}, {"name": "quantity", "type": "UINT", "size": 2}],
      "respond": [
        {"when": "header.unitId != $unitId", "reply": "none"},
        {"when": "body.quantity < 1 || body.quantity > 125", "reply": "exception", "args": {"code": 3}},
        {"when": "!fits('holding', body.start, body.quantity)", "reply": "exception", "args": {"code": 2}},
        {"name": "readOk", "reply": "readHoldingReply"}
      ]
    },
    {
      "name": "writeCoil",
      "direction": "rx",
      "frame": "rtu",
      "match": "header.function == 5",
      "doc": "FC05 코일 하나 쓰기. 값은 0xFF00(켜기) 또는 0x0000(끄기)이고, 응답은 요청 그대로",
      "body": [{"name": "address", "type": "UINT", "size": 2}, {"name": "value", "type": "UINT", "size": 2}],
      "respond": [
        {"when": "header.unitId != $unitId", "reply": "none"},
        {"when": "body.value != 0xFF00 && body.value != 0", "reply": "exception", "args": {"code": 3}},
        {"when": "!writable('coils', body.address, 1)", "reply": "exception", "args": {"code": 2}},
        {"reply": "echo", "write": [{"map": "coils", "address": "body.address", "bytes": "body.value == 0xFF00 ? x'01' : x'00'"}]}
      ]
    },
    {
      "name": "writeRegister",
      "direction": "rx",
      "frame": "rtu",
      "match": "header.function == 6",
      "doc": "FC06 레지스터 하나 쓰기. 응답은 요청 그대로",
      "body": [{"name": "address", "type": "UINT", "size": 2}, {"name": "value", "type": "BYTES", "size": 2}],
      "respond": [
        {"when": "header.unitId != $unitId", "reply": "none"},
        {"when": "!writable('holding', body.address, 1)", "reply": "exception", "args": {"code": 2}},
        {"reply": "echo", "write": [{"map": "holding", "address": "body.address", "bytes": "body.value"}]}
      ]
    },
    {
      "name": "writeRegisters",
      "direction": "rx",
      "frame": "rtu",
      "match": "header.function == 16",
      "doc": "FC16 레지스터 여러 칸 쓰기",
      "body": [
        {"name": "start", "type": "UINT", "size": 2},
        {"name": "quantity", "type": "UINT", "size": 2},
        {"name": "byteCount", "type": "UINT", "size": 1},
        {"name": "values", "type": "BYTES", "size": "byteCount"}
      ],
      "respond": [
        {"when": "header.unitId != $unitId", "reply": "none"},
        {
          "when": "body.quantity < 1 || body.quantity > 123 || body.byteCount != body.quantity * 2",
          "reply": "exception",
          "args": {"code": 3}
        },
        {"when": "!writable('holding', body.start, body.quantity)", "reply": "exception", "args": {"code": 2}},
        {"reply": "writeRegistersReply", "write": [{"map": "holding", "address": "body.start", "bytes": "body.values"}]}
      ]
    },
    {
      "name": "otherFunction",
      "direction": "rx",
      "frame": "rtu",
      "doc": "본문이 4바이트인 그 밖의 기능 코드(FC04 등). 앞 메시지에 안 맞은 요청은 여기서 예외 01 로 답한다.",
      "body": [{"name": "data", "type": "BYTES", "size": 4}],
      "respond": [{"when": "header.unitId != $unitId", "reply": "none"}, {"reply": "exception", "args": {"code": 1}}]
    },
    {
      "name": "readBitsReply",
      "direction": "tx",
      "frame": "rtu",
      "doc": "FC01·FC02 응답. 비트 8개를 한 바이트에(첫 번지가 가장 아래 비트). args.prefix 는 Target 이름 앞부분('출력'·'입력'), 번지 n 의 Target 은 prefix + (n + 1)",
      "body": [
        {"name": "byteCount", "type": "UINT", "size": 1, "value": "(req.body.quantity + 7) // 8"},
        {
          "name": "status",
          "type": "UINT",
          "size": 1,
          "count": "byteCount",
          "value": "(i * 8 + 0 < req.body.quantity && data(args.prefix + text(req.body.start + i * 8 + 1)) != 0 ? 1 : 0) | (i * 8 + 1 < req.body.quantity && data(args.prefix + text(req.body.start + i * 8 + 2)) != 0 ? 2 : 0) | (i * 8 + 2 < req.body.quantity && data(args.prefix + text(req.body.start + i * 8 + 3)) != 0 ? 4 : 0) | (i * 8 + 3 < req.body.quantity && data(args.prefix + text(req.body.start + i * 8 + 4)) != 0 ? 8 : 0) | (i * 8 + 4 < req.body.quantity && data(args.prefix + text(req.body.start + i * 8 + 5)) != 0 ? 16 : 0) | (i * 8 + 5 < req.body.quantity && data(args.prefix + text(req.body.start + i * 8 + 6)) != 0 ? 32 : 0) | (i * 8 + 6 < req.body.quantity && data(args.prefix + text(req.body.start + i * 8 + 7)) != 0 ? 64 : 0) | (i * 8 + 7 < req.body.quantity && data(args.prefix + text(req.body.start + i * 8 + 8)) != 0 ? 128 : 0)"
        }
      ]
    },
    {
      "name": "readHoldingReply",
      "direction": "tx",
      "frame": "rtu",
      "body": [
        {"name": "byteCount", "type": "UINT", "size": 1, "value": "sizeof(body.values)"},
        {"name": "values", "type": "BYTES", "size": "byteCount", "value": "read('holding', req.body.start, req.body.quantity)"}
      ]
    },
    {
      "name": "writeRegistersReply",
      "direction": "tx",
      "frame": "rtu",
      "body": [
        {"name": "start", "type": "UINT", "size": 2, "value": "req.body.start"},
        {"name": "quantity", "type": "UINT", "size": 2, "value": "req.body.quantity"}
      ]
    },
    {
      "name": "exception",
      "direction": "tx",
      "frame": "rtu",
      "set": {"header.function": "req.header.function | 0x80"},
      "body": [{"name": "code", "type": "UINT", "size": 1, "value": "args.code"}]
    }
  ],
  "tests": [
    {
      "name": "FC03 전체 읽기 0..10",
      "data": {
        "전압1": 220.1,
        "전압2": 220.3,
        "전압3": 219.8,
        "전류1": 5.12,
        "전류2": 4.98,
        "전류3": 5.05,
        "전력": 3275,
        "전력량": 12345.6,
        "CT비": 100,
        "경보전력": 5000
      },
      "send": "01 03 00 00 00 0B 04 0D",
      "expect": "01 03 16 08 99 08 9B 08 96 02 00 01 F2 01 F9 0C CB 00 01 E2 40 00 64 13 88 25 DB"
    },
    {"name": "FC03 전류 3..5", "send": "01 03 00 03 00 03 F5 CB", "expect": "01 03 06 02 00 01 F2 01 F9 41 4A"},
    {"name": "FC03 전력량 7..8", "send": "01 03 00 07 00 02 75 CA", "expect": "01 03 04 00 01 E2 40 E2 A3"},
    {"name": "FC03 전력량 절반만", "send": "01 03 00 08 00 01 05 C8", "expect": "01 83 02 C0 F1"},
    {"name": "FC03 범위 밖", "send": "01 03 00 0A 00 02 E4 09", "expect": "01 83 02 C0 F1"},
    {"name": "FC03 수량 0", "send": "01 03 00 00 00 00 45 CA", "expect": "01 83 03 01 31"},
    {
      "name": "FC01 코일 0..9",
      "data": {"출력1": 1, "출력2": 0, "출력3": 1, "출력4": 1, "출력5": 0, "출력6": 0, "출력7": 0, "출력8": 1, "출력9": 0, "출력10": 1},
      "send": "01 01 00 00 00 0A BC 0D",
      "expect": "01 01 02 8D 02 5D 6D"
    },
    {"name": "FC01 코일 2..4", "send": "01 01 00 02 00 03 DD CB", "expect": "01 01 01 03 11 89"},
    {"name": "FC01 범위 밖", "send": "01 01 00 08 00 03 FD C9", "expect": "01 81 02 C1 91"},
    {
      "name": "FC02 입력 0..9",
      "data": {"입력1": 0, "입력2": 1, "입력3": 0, "입력4": 0, "입력5": 1, "입력6": 1, "입력7": 0, "입력8": 0, "입력9": 1, "입력10": 1},
      "send": "01 02 00 00 00 0A F8 0D",
      "expect": "01 02 02 32 03 EC D9"
    },
    {"name": "FC02 수량 0", "send": "01 02 00 00 00 00 78 0A", "expect": "01 82 03 00 A1"},
    {"name": "FC05 코일 4 켜기", "send": "01 05 00 04 FF 00 CD FB", "expect": "01 05 00 04 FF 00 CD FB", "expectWrites": {"출력5": 1}},
    {"name": "FC05 코일 0 끄기", "send": "01 05 00 00 00 00 CD CA", "expect": "01 05 00 00 00 00 CD CA", "expectWrites": {"출력1": 0}},
    {"name": "FC01 쓴 뒤 다시 읽기 0..9", "send": "01 01 00 00 00 0A BC 0D", "expect": "01 01 02 9C 02 51 3D"},
    {"name": "FC05 값 틀림", "send": "01 05 00 01 12 34 91 7D", "expect": "01 85 03 02 91"},
    {"name": "FC05 범위 밖", "send": "01 05 00 0A FF 00 AC 38", "expect": "01 85 02 C3 51"},
    {
      "name": "FC06 CT비 쓰기 (번지 9)",
      "send": "01 06 00 09 00 C8 58 5E",
      "expect": "01 06 00 09 00 C8 58 5E",
      "expectWrites": {"CT비": 200}
    },
    {"name": "FC06 읽기 전용 레지스터", "send": "01 06 00 00 00 01 48 0A", "expect": "01 86 02 C3 A1"},
    {
      "name": "FC16 설정 두 칸 쓰기 9..10",
      "send": "01 10 00 09 00 02 04 00 96 0F A0 D6 61",
      "expect": "01 10 00 09 00 02 91 CA",
      "expectWrites": {"CT비": 150, "경보전력": 4000}
    },
    {"name": "FC03 쓴 값 읽기 9..10", "send": "01 03 00 09 00 02 14 09", "expect": "01 03 04 00 96 0F A0 1F 97"},
    {"name": "FC16 바이트 수 틀림", "send": "01 10 00 09 00 02 03 00 96 0F 23 22", "expect": "01 90 03 0C 01"},
    {"name": "FC16 읽기 전용 포함 7..8", "send": "01 10 00 07 00 02 04 00 01 00 02 62 48", "expect": "01 90 02 CD C1"},
    {"name": "FC04 (지원 안 함)", "send": "01 04 00 00 00 01 31 CA", "expect": "01 84 01 82 C0"},
    {"name": "다른 장비", "send": "02 03 00 00 00 01 84 39", "expect": null},
    {"name": "CRC 틀림", "send": "01 03 00 00 00 01 84 F5", "expect": null}
  ]
}
```

### 예시 B — `STX … BCC ETX` 제어기 (헤더·본문·푸터, 길이 필드, 비트, 쓰기, NAK)

가상의 온습도 제어기입니다. 패킷은 `[STX][국번][명령][길이][본문…][BCC][ETX]` 이고, BCC 가 틀리면 버리지 않고 NAK 로 응답합니다.

```json
{
  "format": "cts-protocol",
  "version": 1,
  "name": "thermo-controller",
  "doc": "STX … BCC ETX 이진 프로토콜의 온습도 제어기 (설명용 가상 장비).",

  "params": {
    "address": { "value": "integer", "default": 1, "min": 1, "max": 31, "doc": "국번" }
  },

  "consts": { "READ": 16, "WRITE": 32, "ACK": 6, "NAK": 21 },

  "checksums": {
    "bcc": {
      "algorithm": "XOR8",
      "covers": { "from": "header.address", "to": "body" },
      "onMismatch": "flag",
      "doc": "틀려도 버리지 않는다. respond 에서 valid('bcc') 로 보고 NAK 로 답한다"
    }
  },

  "framing": { "syncOn": "0x02", "maxLength": 64, "idleFlushMs": 100 },

  "targets": {
    "points": {
      "kind": "named",
      "items": [
        { "target": "운전",     "value": "binary" },
        { "target": "경보",     "value": "binary" },
        { "target": "현재온도", "value": "float" },
        { "target": "습도",     "value": "integer" },
        { "target": "설정온도", "value": "float", "access": "rw" }
      ]
    }
  },

  "frames": {
    "stx": {
      "header": [
        { "type": "UINT", "size": 1, "const": "0x02", "doc": "STX" },
        { "name": "address", "type": "UINT", "size": 1, "value": "$address" },
        { "name": "command", "type": "UINT", "size": 1 },
        { "name": "length",  "type": "UINT", "size": 1, "value": "sizeof(body)" }
      ],
      "bodySize": "header.length",
      "footer": [
        { "name": "bcc", "type": "UINT", "size": 1, "checksum": "bcc" },
        { "type": "UINT", "size": 1, "const": "0x03", "doc": "ETX" }
      ]
    }
  },

  "messages": [
    {
      "name": "read",
      "direction": "rx",
      "frame": "stx",
      "match": "header.command == $READ",
      "body": [],
      "respond": [
        { "when": "header.address != $address", "reply": "none" },
        { "when": "!valid('bcc')", "reply": "nak" },
        { "name": "readOk", "reply": "status" }
      ]
    },
    {
      "name": "write",
      "direction": "rx",
      "frame": "stx",
      "match": "header.command == $WRITE",
      "body": [
        { "name": "setpoint", "type": "INT", "size": 2, "scale": 0.1 }
      ],
      "respond": [
        { "when": "header.address != $address", "reply": "none" },
        { "when": "!valid('bcc')", "reply": "nak" },
        { "when": "body.setpoint < 5 || body.setpoint > 40", "reply": "nak" },
        { "name": "writeOk",
          "write": [ { "target": "설정온도", "value": "body.setpoint" } ],
          "reply": "ack" }
      ]
    },
    {
      "name": "status",
      "direction": "tx",
      "frame": "stx",
      "set": { "header.command": "$READ" },
      "body": [
        { "name": "flags", "type": "BITS", "size": 1,
          "fields": [
            { "name": "run",   "bit": 0, "target": "운전" },
            { "name": "alarm", "bit": 1, "target": "경보" }
          ] },
        { "name": "temperature", "type": "INT", "size": 2, "scale": 0.1, "target": "현재온도" },
        { "name": "humidity",    "type": "UINT", "size": 1,                 "target": "습도" },
        { "name": "setpoint",    "type": "INT", "size": 2, "scale": 0.1, "target": "설정온도" }
      ]
    },
    { "name": "ack", "direction": "tx", "frame": "stx", "set": { "header.command": "$ACK" }, "body": [] },
    { "name": "nak", "direction": "tx", "frame": "stx", "set": { "header.command": "$NAK" }, "body": [] }
  ],

  "tests": [
    {
      "name": "상태 읽기",
      "data": { "운전": 1, "경보": 0, "현재온도": 23.4, "습도": 55, "설정온도": 25.0 },
      "send":   "02 01 10 00 11 03",
      "expect": "02 01 10 06 01 00 EA 37 00 FA 31 03"
    },
    { "name": "설정 26.5", "send": "02 01 20 02 01 09 2B 03", "expect": "02 01 06 00 07 03",
      "expectWrites": { "설정온도": 26.5 } },
    { "name": "설정 45.0 (범위 밖)", "send": "02 01 20 02 01 C2 E0 03", "expect": "02 01 15 00 14 03" },
    { "name": "BCC 틀림",  "send": "02 01 10 00 44 03",    "expect": "02 01 15 00 14 03" },
    { "name": "다른 국번", "send": "02 02 10 00 12 03",    "expect": null },
    { "name": "앞에 잡음", "data": { "설정온도": 25.0 }, "send": "FF 02 01 10 00 11 03", "expect": "02 01 10 06 01 00 EA 37 00 FA 31 03" }
  ]
}
```

### 예시 C — ASCII 한 줄 (TEXT, TEXTNUM, 16진 글자 체크섬)

`#R01\r` 을 받으면 `=01,+220.5,+005.12,1100*1A\r` 로 응답합니다. 체크섬은 주소부터 본문 끝까지의 바이트 합을 16진 글자 두 개로 보냅니다.

```json
{
  "format": "cts-protocol",
  "version": 1,
  "name": "ascii-meter",
  "doc": "ASCII 한 줄 명령/응답 계측기 (설명용 가상 장비). 응답 끝에 16진 두 글자 합 체크섬.",

  "defaults": { "encoding": "ascii" },

  "params": {
    "address": { "value": "integer", "default": 1, "min": 0, "max": 99 }
  },

  "checksums": {
    "sum": { "algorithm": "SUM", "width": 8, "covers": { "from": "header.address", "to": "body" } }
  },

  "framing": { "syncOn": "#", "maxLength": 32, "idleFlushMs": 200 },

  "targets": {
    "points": {
      "kind": "named",
      "items": [ { "target": "전압" }, { "target": "전류" }, { "target": "전력" } ]
    }
  },

  "frames": {
    "commandLine": {
      "header": [
        { "type": "TEXT", "size": 1, "const": "#" },
        { "name": "command", "type": "TEXT",    "size": 1 },
        { "name": "address", "type": "TEXTNUM", "size": 2, "pad": "0" }
      ],
      "footer": [ { "type": "TEXT", "size": 1, "const": "\r" } ]
    },
    "replyLine": {
      "header": [
        { "type": "TEXT", "size": 1, "const": "=" },
        { "name": "address", "type": "TEXTNUM", "size": 2, "pad": "0", "value": "$address" }
      ],
      "footer": [
        { "type": "TEXT", "size": 1, "const": "*" },
        { "name": "sum", "type": "HEXNUM", "size": 2, "checksum": "sum" },
        { "type": "TEXT", "size": 1, "const": "\r" }
      ]
    }
  },

  "messages": [
    {
      "name": "read",
      "direction": "rx",
      "frame": "commandLine",
      "match": "header.command == 'R'",
      "body": [],
      "respond": [
        { "when": "header.address != $address", "reply": "none" },
        { "reply": "values" }
      ]
    },
    {
      "name": "values",
      "direction": "tx",
      "frame": "replyLine",
      "body": [
        { "type": "TEXT", "size": 1, "const": "," },
        { "name": "voltage", "type": "TEXTNUM", "size": 6, "decimals": 1, "sign": "always", "pad": "0", "target": "전압" },
        { "type": "TEXT", "size": 1, "const": "," },
        { "name": "current", "type": "TEXTNUM", "size": 7, "decimals": 2, "sign": "always", "pad": "0", "target": "전류" },
        { "type": "TEXT", "size": 1, "const": "," },
        { "name": "power",   "type": "TEXTNUM", "size": 4, "pad": "0", "target": "전력" }
      ]
    }
  ],

  "tests": [
    {
      "name": "읽기",
      "data": { "전압": 220.5, "전류": 5.12, "전력": 1100 },
      "send":   "23 52 30 31 0D",
      "expect": "3D 30 31 2C 2B 32 32 30 2E 35 2C 2B 30 30 35 2E 31 32 2C 31 31 30 30 2A 31 41 0D",
      "doc": "'#R01\\r' → '=01,+220.5,+005.12,1100*1A\\r'"
    },
    { "name": "다른 주소", "send": "23 52 30 32 0D", "expect": null }
  ]
}
```

### 예시 D — 변환 (일부분)

```json
"structures": {
  "info":  { "fields": [ { "name": "status", "type": "UINT", "size": 1, "target": "상태" },
                         { "name": "voltage", "type": "UINT", "size": 2, "scale": 0.1, "target": "전압" } ] },
  "meter": { "fields": [ { "name": "energy", "type": "BCD", "size": 4, "byteOrder": "little", "scale": 0.01, "target": "전력량" } ] }
}
```

```json
{ "name": "info", "structure": "info",  "transform": [ { "op": "ascii-hex", "case": "upper" } ] }
{ "name": "data", "structure": "meter", "transform": [ { "op": "add", "value": 51 } ] }
```

첫 번째는 YD/T 1363 처럼 데이터를 16진 글자로 바꿔 보내는 경우이고, 두 번째는 DL/T 645 처럼 BCD 를 바이트마다 +33H 해서 보내는 경우입니다.
본문 전체를 변환할 때는 구조체 없이 메시지에 `"bodyTransform": [ { "op": "ascii-hex" } ]` 를 적습니다(11.8절).

---

## 18. 지원하지 않는 것

| 항목 | 내용 |
| --- | --- |
| 바이트 스터핑 | 지원하지 않습니다. JSON 에 `framing.escape` 가 있으면 경고하고 무시합니다 |
| 체크섬 Over 의 `before transforms` | 적어 둘 수는 있지만 적용되지 않습니다. 항상 실제로 오가는 바이트로 계산하므로, Modbus ASCII 의 LRC 는 아직 표현할 수 없습니다 |
| 응답 규칙별 오류 지정, 응답 변형 | 응답 규칙의 Name 으로 Error Simulation 의 대상을 지정하거나, 체크섬만 틀리게·잘라서·두 번 보내는 기능은 없습니다. 시스템 오류 4종만 적용됩니다 |
| 글자 값 Target | Server Data 는 수만 담습니다. TEXT 의 값은 Const 나 Value 로만 줍니다 |
| 글자 값 파라미터 | 파라미터는 `integer` 와 `float` 만 있습니다 |
| 구조체 안의 구조체 | 구조체는 한 단계입니다. 변환한 본문 안의 레코드 배열은 Body transform 으로 표현합니다(11.8절, 6장) |
| 여러 바이트를 낮은 바이트부터 묶은 BITS | 바이트마다 Size 1 인 BITS 로 나눕니다(11.7절) |
| 8바이트 UINT 의 2^63 이상 값 | 다루지 않습니다(11.1절) |
| 장비가 먼저 보내는 통신, 여러 프레임으로 된 응답, 세션 상태, 압축, 암호화 | 지원 범위 밖입니다 |

---

## 19. 문제 해결

### 19.1 저장되지 않을 때

| 증상 | 확인할 것 |
| --- | --- |
| Save 옆에 `Fix the N errors first.` | 위 막대의 Problems 목록이 열립니다. 오류를 누르면 해당 위치로 이동하며, 오류를 모두 고쳐야 저장됩니다 |
| Save 옆에 `The server did not save it.` | 저장할 때의 검사나 시험 사례에서 걸린 것입니다. Problems 목록에 실패한 사례가 표시되므로, Tests 에서 Expected 와 Actual 을 비교합니다 |
| `Another protocol definition is already named "X".` | 정의 이름은 다른 정의와 겹칠 수 없습니다. General 의 Name 을 바꿉니다 |
| 다른 곳에서 먼저 저장했다는 알림 | 다른 창이나 다른 사용자가 같은 정의를 먼저 저장했습니다. 다시 연 뒤 고쳐서 저장합니다 |

### 19.2 시험 사례가 실패할 때

| Actual | 흔한 원인 |
| --- | --- |
| `no reply` | 어느 받는 메시지에도 맞지 않았습니다. Send 의 길이, Const 값, Match, 체크섬(바이트 순서 포함)을 확인합니다. 또는 앞쪽 응답 규칙(다른 국번 등)에 걸렸을 수 있습니다 |
| 체크섬만 다름 | Covers 범위(양쪽 끝 포함), 알고리즘, 체크섬 필드의 Byte order(`big`, `little`)를 확인합니다(8장) |
| 값이 10배 또는 0.1배 | Scale 과 TEXTNUM 의 Decimals 를 확인합니다. 둘 다 쓰면 두 번 적용됩니다(11.4절). Server Data before 에는 물리값을 적습니다 |
| 앞 사례는 통과하고 뒤 사례만 값이 다름 | 앞 사례에서 쓴 값이 남아 있는 것입니다. 그 사례의 Server Data before 에 값을 다시 적습니다 |
| 응답이 없고 실패 이유가 범위 초과 | 물리값이 타입의 범위를 넘었습니다. Type, Size, Scale 을 고치거나 On overflow 를 `clamp` 로 바꿉니다 |

### 19.3 Device 가 응답하지 않을 때

1. Device 에 정의가 지정되어 있는지 확인합니다(15.1절).
2. 파라미터(국번 등)가 상위 시스템이 보내는 값과 같은지 확인합니다.
3. Settings 의 **Realtime Logs** 를 봅니다. 맞지 않아 버린 바이트, 쓰기가 막힌 이유, 식 오류가 Device 이름과 함께 기록됩니다.
4. 받은 바이트를 그대로 Tests 의 Send 에 넣고 **Run tests** 를 실행합니다. 어디서 어긋나는지 가장 빨리 찾을 수 있습니다.
5. 체크섬이 틀린 요청은 기본 설정(On mismatch `drop the frame`)에서 알림 없이 버려집니다. 상위 시스템의 체크섬 계산을 확인합니다.

### 19.4 응답이 1초 정도 늦을 때

TCP 에서 앞 순서의 받는 메시지가 바이트를 더 기다리는 중일 수 있습니다(9.3절).
길이가 다른 요청이 섞여 있다면 첫 몇 바이트(Const, Match)로 구분되는 메시지를 앞에 두거나, Framing 의 Idle flush 를 줄입니다.

### 19.5 Value Simulation 값이 응답에 나오지 않을 때

- Simulation 의 Target 이 정의의 Target 과 글자 그대로 같은지 확인합니다(대소문자, 공백).
- 그 Target 이 보내는 메시지의 필드에 실려 있는지 확인합니다. 선언만 하고 필드에 싣지 않으면 응답에 나오지 않습니다. 식의 `data('…')` 에 적은 이름은 오타가 있어도 알림 없이 0 이 됩니다.
- Server Data 화면에서 그 Target 의 값이 바뀌는지 봅니다. 잠금(Lock)이 걸려 있으면 Simulation 이 값을 쓰지 못합니다.
