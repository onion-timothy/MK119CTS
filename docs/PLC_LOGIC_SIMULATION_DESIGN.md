# PLC Logic Control 시뮬레이션 — 기본 구조 설계

> 상태: **설계 초안 (구현 전)**
> 목적: 기존 Value Simulation 구조 위에, PLC의 Logic Control(입력→로직→출력)을 모사하는
> 별도 실행 경로를 추가하기 위한 기본 골격을 정의한다.

---

## 1. 개요

현재 시뮬레이터는 각 포인트(`Simulation`)가 **독립적으로** 값을 생성한다
(`STATIC` / `RANDOM` / `ACCUMULATE`). 즉 "센서값을 흉내내는" 입력 생성까지만 담당한다.

본 설계는 여기에 **PLC의 두뇌(Logic Control)** 를 추가한다:

> 다른 포인트들의 현재 값을 **입력으로 읽어**, 정의된 **로직으로 연산**한 결과를
> 자기 target에 **출력으로 기록**한다.

핵심 결정사항(요구사항):

1. 모든 프로토콜 Device는 **`scanTime`** 필드를 갖는다.
2. `scanTime` 주기로 도는 **별도 스레드(타이머)** 가, 해당 Device의 시뮬레이션 중
   **Policy가 `LOGIC`인 것**들을 찾아 실행한다.
3. 실행 시점에 **`ServerData`를 스냅샷**하고, 그 스냅샷만으로 연산한다(스캔 일관성).
4. `LOGIC` 정책은 **세분화된 연산 타입**(불리언/비교/산술/타이머/카운터/래치…)으로 나눈다.
5. 각 연산은 **대상 시뮬레이션 ID**를 입력으로 지정하고, 스냅샷에서 읽어 처리한다
   (기존 Value Simulation의 target 구조와 동형).
6. 설계는 **객체지향(다형성 기반 연산 트리)** 으로 구성한다.

---

## 2. 기존 구조 요약 (연계 지점)

| 구성요소 | 현재 역할 | Logic 연계 |
|---|---|---|
| `Simulation` | 출력 포인트 1개. `device`, `target`, `policies`, `interval`, `enabled` | `Policy.LOGIC`이면 Logic 시뮬로 취급 |
| `SimulationPolicy` | 값 생성 규칙 (`Policy` + value/range/distribution) | `logic`(연산 트리) 필드 추가 |
| `Policy` (enum) | STATIC / RANDOM / ACCUMULATE | **`LOGIC` 추가** |
| `GroupedSimulationJob` | `(device, interval)` 주기 실행 → 값 생성 → `ServerData`에 기록 | **LOGIC 정책은 제외(skip)** |
| `ServerData` | `setServerData(sim, value)` : target → 프로토콜 저장소 | **`getValue(sim)` 읽기 API 추가** ← 스냅샷 공급원 |
| `SimulationService` | 시뮬 시작/중지/그룹 스케줄 관리 | LOGIC 시뮬은 그룹 대신 **Logic Scan으로 라우팅** |
| `Device` | 프로토콜/접속 정보 | **`scanTime` 필드 추가** |

> 핵심: 출력 기록 경로(`setServerData`)와 target 개념은 **그대로 재사용**한다.
> 새로 필요한 것은 (a) 읽기 경로(`getValue`), (b) 스캔 실행기, (c) 연산 모델뿐이다.

---

## 3. 데이터 모델 변경

### 3.1 `Device` — scanTime 추가

```
class Device {
    ...
    private Integer scanTime;   // ms 단위. 이 Device의 Logic Scan 주기. null/0이면 Logic 비활성
}
```

- Value Simulation의 `interval`(초)과 **별개**다. Logic은 오직 `scanTime`으로만 돈다.
- 단위는 ms 권장(PLC 스캔은 보통 수~수십 ms). 운영 부하에 따라 하한선 둘 것.

### 3.2 `Policy` — LOGIC 추가

```
enum Policy { STATIC(1), RANDOM(2), ACCUMULATE(3), LOGIC(4) }
```

### 3.3 `SimulationPolicy` — logic 연산 트리 보유

```
class SimulationPolicy {
    private String id;
    private Policy policy;            // LOGIC이면 아래 logic 사용, 나머지 필드 무시
    private DataType data_type;
    private Double value;             // (기존)
    private List<Double> range;       // (기존)
    private Distribution distribution;// (기존)

    private LogicOperation logic;     // ★ policy == LOGIC 일 때의 연산 트리 루트
}
```

- `policies`는 이미 `JsonStringPolicyListConverter`로 JSON 직렬화되므로 필드 추가는 **하위호환**.
- `LogicOperation` 트리는 Jackson 다형성(`@JsonTypeInfo` / `@JsonSubTypes`)으로 직렬화.

### Logic Simulation 한 건의 의미

| 필드 | Logic 시뮬에서의 의미 |
|---|---|
| `Simulation.target` | **출력** 주소 (연산 결과를 쓸 곳). 기존 target 규칙 그대로 |
| `Simulation.device` | 이 Logic이 속한 Device (scanTime 주체) |
| `SimulationPolicy.logic` | 입력(다른 sim ID)을 참조하는 **연산 트리** |
| `Simulation.interval` | **사용 안 함** (Logic은 scanTime으로 동작) |
| `Simulation.enabled` | 활성/비활성 (기존과 동일) |

---

## 4. 객체지향 연산 모델

### 4.1 핵심 추상 — `LogicValue`

"스캔 한 번 동안 하나의 double로 해석(resolve)되는 모든 것"의 공통 인터페이스.
이걸 통해 **연산을 트리로 합성**한다(연산의 입력이 또 다른 연산일 수 있음).

```
interface LogicValue {
    double resolve(LogicContext ctx);
}
```

구현체 3종:

| 구현체 | 의미 | 필드 |
|---|---|---|
| `InputRef` | 다른 시뮬레이션의 현재값을 **스냅샷에서** 읽음 | `int simulationId` |
| `Constant` | 상수 | `double value` |
| `LogicOperation` | 연산(그 자체도 LogicValue → 합성 가능) | 4.2 참조 |

> `InputRef.resolve(ctx)` = `ctx.snapshot().get(simulationId)`
> 이것이 요구사항의 "각 연산의 대상 시뮬레이션 ID를 지정하고, 스냅샷에서 불러와서 처리"에 해당.

### 4.2 연산 추상 — `LogicOperation`

```
abstract class LogicOperation implements LogicValue {
    protected String nodeId;   // 연산 트리 내 고유 ID (상태 보유형 연산의 상태 키로 사용)
    public abstract double resolve(LogicContext ctx);
}
```

`nodeId`가 필요한 이유: 타이머/카운터/래치처럼 **스캔 간 상태를 기억**해야 하는 연산은,
"어느 노드의 상태인지"를 식별해야 하기 때문 (4.4 상태 저장소).

### 4.3 연산 타입 카탈로그 (Policy 세분화)

> 상태(state) 컬럼: 스캔 간 기억이 필요한가. 기존 `ACCUMULATE`의 `accumValues` 맵과 동일한 패턴으로 처리.

#### (a) 상태 없는 조합 로직 — 가장 먼저 구현 가능

| 클래스 | 종류 | 필드 | 결과 |
|---|---|---|---|
| `BooleanOperation` | AND / OR / NOT / XOR | `BoolOp op`, `List<LogicValue> operands` | 1.0 / 0.0 |
| `ComparisonOperation` | GT / GE / LT / LE / EQ / NE | `CmpOp op`, `LogicValue left`, `LogicValue right` | 1.0 / 0.0 |
| `ArithmeticOperation` | ADD / SUB / MUL / DIV | `ArithOp op`, `List<LogicValue> operands` | 연산 결과 |

#### (b) 상태 보유 로직 — 상태 저장소 필요

| 클래스 | 종류 | 필드 | 상태 |
|---|---|---|---|
| `TimerOperation` | TON / TOF / TP | `TimerType type`, `LogicValue input`, `long presetMillis` | 누적 경과시간, 직전 입력 |
| `CounterOperation` | CTU / CTD | `CounterType type`, `LogicValue countInput`, `LogicValue resetInput`, `int preset` | 현재 카운트, 직전 입력(에지 검출) |
| `SetResetOperation` | SR / RS | `LogicValue set`, `LogicValue reset`, `boolean resetDominant` | 현재 ON/OFF 상태 |

> 각 타입의 동작 정의는 `docs/PLC 기능 설명` 참고(타이머 TON/TOF/TP, 카운터 CTU/CTD, 래치 SR/RS).
> (c) 인터록/시퀀스, (d) PID/아날로그 루프는 후속 확장으로 분리.

### 4.4 실행 컨텍스트와 상태 저장소

```
class LogicContext {
    ServerDataSnapshot snapshot;   // simId -> 값 (스캔 시작 시 고정)
    long now;                      // 스캔 타임스탬프(ms)
    LogicStateStore stateStore;    // 스캔 간 영속 상태
    Simulation owner;              // 평가 중인 Logic 시뮬
}

class ServerDataSnapshot {         // 불변(immutable)
    Map<Integer, Double> values;   // simulationId -> 그 시점 ServerData 값
    double get(int simulationId);
}

class LogicStateStore {            // 스캔 간 유지 (싱글톤/서비스 보유)
    // key = ownerSimId + ":" + nodeId
    Object get(String key);
    void put(String key, Object state);
}
```

- `snapshot`은 **읽기 전용**. 모든 `InputRef`는 여기서만 값을 가져온다 → 스캔 일관성 보장.
- `stateStore`는 `TimerState` / `CounterState` / SR 상태 등을 노드별로 보관.
- 기존 `GroupedSimulationJob.accumValues`(static map)와 동일 개념을 일반화한 것.

### 4.5 클래스 관계 (텍스트 다이어그램)

```
            LogicValue (interface)
              ▲        ▲          ▲
              │        │          │
         InputRef  Constant   LogicOperation (abstract, nodeId)
                                  ▲
        ┌──────────┬─────────────┼───────────┬──────────────┐
   BooleanOp  ComparisonOp  ArithmeticOp  TimerOp(state)  CounterOp(state)  SetResetOp(state)

   * 연산의 피연산자(operand)는 다시 LogicValue → 트리 합성 가능
     예) AND( GT(InputRef(101), Constant(50)), InputRef(102) )
```

---

## 5. 실행 흐름 (Logic Scan)

### 5.1 스캐줄링 — `LogicScanService`

`SimulationService`와 분리된 **별도 스케줄러/스레드 풀**에서 Device별 스캔을 등록한다.

```
class LogicScanService {
    ThreadPoolTaskScheduler logicScheduler;   // Value 시뮬용과 별개 풀
    Map<Integer, ScheduledFuture<?>> scansByDevice;  // deviceId -> 스캔 핸들

    void startDeviceScan(Device device);   // scanTime 주기로 LogicScanJob 등록
    void stopDeviceScan(int deviceId);
    void reschedule(Device device);        // scanTime 변경 시
}
```

- Device의 `scanTime`이 설정되고 Logic 시뮬이 1개 이상 있을 때 스캔을 등록.
- Value 시뮬레이션(`GroupedSimulationJob`)과는 **독립된 타이머**로 동작.

### 5.2 스캔 1회 — `LogicScanJob` (Runnable, device당 1개)

```
run():
  1. SKIP 판정: device.scanTime 유효 & 활성 LOGIC 시뮬 존재?
  2. 입력 수집: 이 device의 LOGIC 시뮬들이 참조하는 모든 InputRef.simulationId 집합 수집
  3. 스냅샷 생성: 각 입력 sim에 대해 ServerData.getValue(sim) 호출 → ServerDataSnapshot(불변)
  4. 평가: LOGIC 시뮬마다 policy.logic(root).resolve(ctx) → 출력값
           (모든 InputRef는 3에서 만든 스냅샷만 참조 → 평가 순서 무관)
  5. 출력 기록: 평가 결과를 모아 setServerData(sim, value)로 일괄 기록 (Output Image)
```

> **출력 이미지(batch write) 채택 이유**: 한 스캔 내에서 A의 출력이 B의 입력이어도,
> B는 이번 스캔 스냅샷(=이전 스캔의 A값)을 본다 → 실제 PLC의 "1 스캔 지연" 동작과 일치하며
> 평가 순서에 의존하지 않는다.

### 5.3 스냅샷 공급 — `ServerData.getValue` (신규)

`setServerData`의 대칭. target → 프로토콜별 저장소에서 **현재값 읽기**.

```
interface ServerData {
    void setServerData(Simulation sim, double value);   // (기존)
    double getValue(Simulation sim);                     // ★ 신규: 스냅샷용 읽기
    ...
}
```

- Modbus: target `func_register` 파싱 → coil/holding/input에서 읽기
- XGT/Daeyun/OPC/SNMP/EtherNetIP: 각자 저장소에서 읽기 (setServerData의 역방향)
- 이 읽기 경로 덕분에 **외부 마스터가 control로 쓴 값**도 Logic 입력으로 자연스럽게 소비된다.

---

## 6. 기존 코드와의 연계 변경점 (요약)

| 파일/클래스 | 변경 |
|---|---|
| `Device` | `scanTime` 필드 추가 |
| `Policy` (enum) | `LOGIC` 추가 |
| `SimulationPolicy` | `LogicOperation logic` 필드 추가 |
| `ServerData` (+ 8개 구현체) | `getValue(sim)` 읽기 메서드 추가 |
| `GroupedSimulationJob` | LOGIC 정책 시뮬은 처리 대상에서 제외 |
| `SimulationService` | 시뮬 시작/중지 시 LOGIC이면 `LogicScanService`로 라우팅 |
| **신규** `LogicScanService` | Device별 scanTime 스캔 스케줄 관리 |
| **신규** `LogicScanJob` | 스냅샷 → 평가 → 출력 기록 |
| **신규** `logic.*` 패키지 | `LogicValue`, `LogicOperation` 및 연산 타입들, `LogicContext`, `ServerDataSnapshot`, `LogicStateStore` |

---

## 7. 미해결 / 결정 필요 사항

1. **입력 참조 범위**: 같은 Device 내 sim ID만 허용 vs Device 간 참조 허용
   (Device 간이면 스냅샷을 device 경계를 넘어 수집해야 함).
2. **scanTime 단위/하한**: ms 권장. 운영 안정성을 위한 최소값(예: 50ms?) 정책.
3. **출력 기록 시점**: 5.2의 batch write(권장) 확정 여부.
4. **연산 트리 정의 UI/스키마**: 사용자가 어떻게 로직을 입력할지(JSON 직접 / 폼 / 그래픽).
5. **에러 시뮬레이션과의 상호작용**: Logic 출력 포인트에도 `Error`(SIMULATION 모드)를 걸 수 있는지.
6. **상태 초기화 시점**: 시뮬 수정/재시작 시 Timer/Counter/SR 상태를 리셋할지 유지할지.

---

## 8. 단계별 구현 순서 (제안)

- **Phase 0** — `Device.scanTime`, `Policy.LOGIC`, `SimulationPolicy.logic` 필드 + `ServerData.getValue` 전 프로토콜 구현
- **Phase 1** — `logic.*` 모델(LogicValue/LogicOperation) + 상태 없는 연산(Boolean/Comparison/Arithmetic) + `LogicScanService`/`LogicScanJob`
- **Phase 2** — 상태 보유 연산(Timer/Counter/SetReset) + `LogicStateStore`
- **Phase 3** — 인터록/시퀀스(상태머신), control↔입력 연동 정리
- **Phase 4** — (선택) Device 간 참조, PID/아날로그 루프
