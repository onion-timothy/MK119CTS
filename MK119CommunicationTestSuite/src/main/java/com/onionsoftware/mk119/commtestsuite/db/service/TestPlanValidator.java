package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanAction;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.enums.ScheduleType;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.EthernetIPTypes;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataCells;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataValueRules;
import com.onionsoftware.mk119.commtestsuite.testplan.TestPlanTiming;
import etherip.types.CIPData;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Test Plan 등록 시점 검증. 규칙과 ID 는 docs/TEST_PLAN_VALIDATION.md 의 P·S·A·X 항목이다.
 * <p>
 * 화면 저장, 엑셀 import, 외부 REST 가 모두 이걸 거친다. 경로마다 넣으면 구멍이 생긴다.
 * 입력은 JSON/엑셀에서 온 느슨한 값(Map, 숫자·문자열·불리언 섞임)이고, 통과하면 정규화된
 * {@link TestPlan} 을 함께 돌려준다. 저장은 호출한 쪽이 {@link TestPlanService} 로 한다.
 * <p>
 * P4(실행 중인 Plan 은 수정·삭제 불가)는 실행기를 아는 쪽이 본다. 여기는 실행 상태를 모른다.
 */
@Component
@RequiredArgsConstructor
public class TestPlanValidator {
    public static final int NAME_MAX = 100;
    public static final int MAX_ACTIONS = 1000;
    public static final int MAX_SHEETS = 50;

    /** 엑셀 헤더(X4). 순서 무관, 대소문자 무시. */
    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "RunAtSeconds", "Device", "Target", "Value", "DataType", "LockSeconds", "StopOnFailure"));

    /** 실행 시각(초)의 상한(A12). 하루. 스케줄 주기 중 가장 긴 HOUR 와 같다. */
    public static final int MAX_AT_SECONDS = 86400;
    /** INTERVAL 스케줄의 간격 상한(초). 하루. */
    public static final int MAX_INTERVAL_SECONDS = 86400;

    private final DeviceService deviceService;
    private final SimulationService simulationService;
    private final TestPlanService testPlanService;
    private final SimulationTargetValidator simulationTargetValidator;

    // ── 결과 ─────────────────────────────────────────────────────────────

    /** 검증 문제 하나. action 은 0부터 센 Action 위치, row 는 엑셀 행 번호(엑셀에서 온 경우). */
    @Getter
    public static final class Issue {
        private final String rule;
        private final String field;
        private final Integer action;
        private final Integer row;
        private final String message;

        Issue(String rule, String field, Integer action, Integer row, String message) {
            this.rule = rule;
            this.field = field;
            this.action = action;
            this.row = row;
            this.message = message;
        }
    }

    /** Plan 하나의 검증 결과. 오류가 없을 때만 plan 이 채워진다. */
    @Getter
    public static final class Outcome {
        private final List<Issue> errors = new ArrayList<>();
        private final List<Issue> warnings = new ArrayList<>();
        private TestPlan plan;

        public boolean isOk() {
            return errors.isEmpty();
        }
    }

    /** 엑셀 시트 하나의 결과. */
    @Getter
    public static final class SheetOutcome {
        private final String sheet;
        private final Outcome outcome;

        SheetOutcome(String sheet, Outcome outcome) {
            this.sheet = sheet;
            this.outcome = outcome;
        }
    }

    /** 엑셀 파일 전체의 결과. 파일 단위 오류(X2)와 시트별 결과. */
    @Getter
    public static final class WorkbookOutcome {
        private final List<Issue> errors = new ArrayList<>();
        private final List<SheetOutcome> sheets = new ArrayList<>();

        /** 파일 단위·시트 단위 오류가 하나도 없을 때만 등록할 수 있다(X7). */
        public boolean isOk() {
            if (!errors.isEmpty()) return false;
            for (SheetOutcome s : sheets) if (!s.outcome.isOk()) return false;
            return true;
        }

        public List<TestPlan> plans() {
            List<TestPlan> out = new ArrayList<>();
            for (SheetOutcome s : sheets) if (s.outcome.plan != null) out.add(s.outcome.plan);
            return out;
        }
    }

    // ── 화면 / REST ──────────────────────────────────────────────────────

    /**
     * 편집기·REST 가 보내는 Plan 하나.
     * <pre>
     * {name, scheduleType, scheduleHour, scheduleMinute, scheduleSecond, scheduleIntervalSeconds, scheduleEnabled,
     *  actions: [{deviceId | device, target, value, dataType, lockSeconds, atSeconds, stopOnFailure}]}
     * </pre>
     * 장비는 deviceId 가 있으면 그것으로, 없으면 device(이름)로 찾는다.
     *
     * @param exceptId 수정이면 그 Plan 의 id (이름 중복 검사에서 자기 자신을 뺀다). 생성이면 null
     */
    public Outcome validatePlan(Map<String, Object> body, Integer exceptId) {
        Context ctx = new Context();
        Outcome out = new Outcome();
        if (body == null) body = Collections.emptyMap();

        TestPlan plan = new TestPlan();
        plan.setId(exceptId);
        plan.setName(checkName(out, body.get("name"), exceptId, null));
        checkSchedule(out, body, plan);

        List<Map<String, Object>> actions = mapList(body.get("actions"));
        checkActions(ctx, out, plan, actions);
        checkScheduleFits(ctx, out, plan);

        if (out.isOk()) out.plan = plan;
        return out;
    }

    // ── 엑셀 ─────────────────────────────────────────────────────────────

    /**
     * 엑셀 파일. 화면은 SheetJS 로 시트마다 행 배열을 만들어 보낸다.
     * <pre>
     * [{name: "시트 이름", startRow: 1, rows: [[헤더...], [셀...], ...]}]
     * </pre>
     * rows 는 빈 행을 포함한 그대로여야 한다(SheetJS {@code header: 1, blankrows: true, defval: null}).
     * 그래야 행 번호를 엑셀과 맞게 보고할 수 있다. startRow 는 시트 범위의 첫 행 번호(기본 1).
     * 스케줄은 받지 않는다 — import 된 Plan 은 NONE 이다(X8).
     */
    public WorkbookOutcome validateWorkbook(List<Map<String, Object>> sheets) {
        WorkbookOutcome wb = new WorkbookOutcome();
        if (sheets == null || sheets.isEmpty()) {
            wb.errors.add(new Issue("X2", "sheets", null, null, "The file has no sheets."));
            return wb;
        }
        if (sheets.size() > MAX_SHEETS) {
            wb.errors.add(new Issue("X2", "sheets", null, null,
                    "The file has " + sheets.size() + " sheets. At most " + MAX_SHEETS + " are allowed."));
            return wb;
        }
        Context ctx = new Context();
        Set<String> namesInFile = new HashSet<>();
        for (Map<String, Object> sheet : sheets) {
            String sheetName = str(sheet.get("name"));
            Outcome out = new Outcome();
            wb.sheets.add(new SheetOutcome(sheetName, out));

            TestPlan plan = new TestPlan();
            plan.setName(checkName(out, sheetName, null, namesInFile));

            List<Map<String, Object>> actions = readSheetRows(out, sheet);
            if (actions != null) {
                checkActions(ctx, out, plan, actions);
            }
            if (out.isOk()) out.plan = plan;
        }
        return wb;
    }

    /** X4·X5: 헤더를 읽고 데이터 행을 Action 입력으로 바꾼다. 헤더가 틀리면 null. */
    private List<Map<String, Object>> readSheetRows(Outcome out, Map<String, Object> sheet) {
        List<?> rows = sheet.get("rows") instanceof List ? (List<?>) sheet.get("rows") : Collections.emptyList();
        Integer startRow = intOrNull(sheet.get("startRow"));
        int firstRow = startRow == null ? 1 : startRow;
        if (rows.isEmpty()) {
            out.errors.add(new Issue("X4", "header", null, firstRow, "The sheet has no header row."));
            return null;
        }
        List<?> header = rows.get(0) instanceof List ? (List<?>) rows.get(0) : Collections.emptyList();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = str(header.get(i));
            if (h == null) continue;
            String known = null;
            for (String c : COLUMNS) if (c.equalsIgnoreCase(h)) known = c;
            if (known == null) {
                out.warnings.add(new Issue("X4", h, null, firstRow, "Unknown column \"" + h + "\" is ignored."));
            } else if (col.containsKey(known)) {
                out.errors.add(new Issue("X4", known, null, firstRow, "Column \"" + known + "\" appears twice."));
            } else {
                col.put(known, i);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String c : COLUMNS) if (!col.containsKey(c)) missing.add(c);
        if (!missing.isEmpty()) {
            out.errors.add(new Issue("X4", "header", null, firstRow,
                    "Missing column(s): " + String.join(", ", missing) + "."));
        }
        if (!out.isOk()) return null;

        List<Map<String, Object>> actions = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<?> cells = rows.get(r) instanceof List ? (List<?>) rows.get(r) : Collections.emptyList();
            boolean blank = true;
            for (Object c : cells) if (str(c) != null) { blank = false; break; }
            if (blank) continue;   // X5
            Map<String, Object> a = new HashMap<>();
            a.put("device", cell(cells, col.get("Device")));
            a.put("target", cell(cells, col.get("Target")));
            a.put("value", cell(cells, col.get("Value")));
            a.put("dataType", cell(cells, col.get("DataType")));
            a.put("lockSeconds", cell(cells, col.get("LockSeconds")));
            a.put("atSeconds", cell(cells, col.get("RunAtSeconds")));
            a.put("stopOnFailure", cell(cells, col.get("StopOnFailure")));
            a.put("_row", firstRow + r);
            actions.add(a);
        }
        return actions;
    }

    // ── Plan · Schedule ──────────────────────────────────────────────────

    /** P1·P2. namesInFile 이 있으면 엑셀 한 파일 안의 중복도 본다. */
    private String checkName(Outcome out, Object raw, Integer exceptId, Set<String> namesInFile) {
        String name = str(raw);
        if (name == null) {
            out.errors.add(new Issue("P1", "name", null, null, "Name is required."));
            return null;
        }
        if (name.length() > NAME_MAX) {
            out.errors.add(new Issue("P1", "name", null, null,
                    "Name must be at most " + NAME_MAX + " characters (now " + name.length() + ")."));
            return name;
        }
        if (namesInFile != null && !namesInFile.add(name)) {
            out.errors.add(new Issue("P2", "name", null, null, "Name \"" + name + "\" is used by another sheet in this file."));
        } else if (testPlanService.existsByName(name, exceptId)) {
            out.errors.add(new Issue("P2", "name", null, null, "A test plan named \"" + name + "\" already exists."));
        }
        return name;
    }

    /** S1·S2. 쓰지 않는 칸은 null 로 비운다. 스케줄이 NONE 이면 켜 둘 것이 없으므로 끈다. */
    private void checkSchedule(Outcome out, Map<String, Object> body, TestPlan plan) {
        String typeRaw = str(body.get("scheduleType"));
        ScheduleType type;
        try {
            type = typeRaw == null ? ScheduleType.NONE : ScheduleType.valueOf(typeRaw);
        } catch (IllegalArgumentException e) {
            out.errors.add(new Issue("S1", "scheduleType", null, null,
                    "Unknown schedule type \"" + typeRaw + "\". Use NONE, SECOND, MINUTE, HOUR or INTERVAL."));
            return;
        }
        plan.setScheduleType(type);
        plan.setScheduleHour(type == ScheduleType.HOUR
                ? scheduleField(out, body, "scheduleHour", 0, 23) : null);
        plan.setScheduleMinute(type == ScheduleType.MINUTE || type == ScheduleType.HOUR
                ? scheduleField(out, body, "scheduleMinute", 0, 59) : null);
        plan.setScheduleSecond(type.isCron()
                ? scheduleField(out, body, "scheduleSecond", 0, 59) : null);
        plan.setScheduleIntervalSeconds(type == ScheduleType.INTERVAL
                ? scheduleField(out, body, "scheduleIntervalSeconds", 1, MAX_INTERVAL_SECONDS) : null);

        Boolean enabled = boolOrNull(body.get("scheduleEnabled"));
        if (enabled == null && body.get("scheduleEnabled") != null && str(body.get("scheduleEnabled")) != null) {
            out.errors.add(new Issue("S1", "scheduleEnabled", null, null, "Schedule enabled must be true or false."));
        }
        plan.setScheduleEnabled(type != ScheduleType.NONE && Boolean.TRUE.equals(enabled)
                ? TestPlan.SCHEDULE_ENABLED : TestPlan.SCHEDULE_DISABLED);
    }

    private Integer scheduleField(Outcome out, Map<String, Object> body, String field, int min, int max) {
        Object raw = body.get(field);
        Integer v = integral(raw);
        if (v == null || v < min || v > max) {
            out.errors.add(new Issue("S2", field, null, null,
                    field + " must be a whole number from " + min + " to " + max + (str(raw) == null ? "." : " (got \"" + str(raw) + "\").")));
            return null;
        }
        return v;
    }

    /** S3·S4. Action 을 저장할 때마다 다시 본다. 실행 시간 = 마지막 lock 이 풀리는 시각(TestPlanTiming). */
    private void checkScheduleFits(Context ctx, Outcome out, TestPlan plan) {
        ScheduleType type = plan.getScheduleType();
        long period = plan.schedulePeriodSeconds();
        if (period <= 0) return;   // 스케줄이 없거나, interval 이 잘못돼 S2 가 이미 잡았다
        long duration = TestPlanTiming.durationSeconds(plan.getActions(), id -> {
            Device d = ctx.devicesById().get(id);
            return d == null ? null : d.getProtocol();
        });
        if (duration >= period) {
            out.errors.add(new Issue("S3", type == ScheduleType.INTERVAL ? "scheduleIntervalSeconds" : "scheduleType", null, null,
                    "The plan runs for " + duration + " s (until its last lock is released), "
                            + "which must be shorter than the schedule period ("
                            + period + " s for " + type + ")."));
        }
    }

    // ── Action ───────────────────────────────────────────────────────────

    /** P3 와 A1~A16. 통과한 Action 은 plan 에 붙는다. */
    private void checkActions(Context ctx, Outcome out, TestPlan plan, List<Map<String, Object>> actions) {
        if (actions.isEmpty() || actions.size() > MAX_ACTIONS) {
            out.errors.add(new Issue("P3", "actions", null, null,
                    "A test plan needs 1 to " + MAX_ACTIONS + " actions (now " + actions.size() + ")."));
            if (actions.isEmpty()) return;
        }
        for (int i = 0; i < actions.size(); i++) {
            TestPlanAction a = checkAction(ctx, out, actions.get(i), i);
            if (a != null) plan.getActions().add(a);
        }
        // 모든 Action 이 통과했을 때만 본다. 그래야 plan 의 위치와 입력의 위치가 같다
        if (out.isOk()) checkStartsAtZero(out, plan, actions);
        if (out.isOk()) checkSameTimeCells(ctx, out, plan, actions);
    }

    /** A16. 가장 이른 Action 은 0초다. Plan 을 시작해 놓고 아무것도 하지 않고 기다릴 까닭이 없다. */
    private void checkStartsAtZero(Outcome out, TestPlan plan, List<Map<String, Object>> raw) {
        List<TestPlanAction> actions = plan.getActions();
        int first = 0;
        for (int i = 1; i < actions.size(); i++) {
            if (actions.get(i).getAtSeconds() < actions.get(first).getAtSeconds()) first = i;
        }
        int at = actions.get(first).getAtSeconds();
        if (at != 0) {
            err(out, "A16", "at", first, intOrNull(raw.get(first).get("_row")),
                    "The earliest action must run at 0 s (action #" + (first + 1) + " is the earliest, at " + at
                            + " s). A plan starts with its first action.");
            plan.getActions().clear();
        }
    }

    /**
     * A15. 같은 시각에 같은 칸을 두 Action 이 쓰면 거절한다. 뒤의 것이 앞의 것을 곧바로 덮으므로 의미가 없다.
     * 칸은 lock 판정과 같은 ServerDataCells 로 센다 — target 이 달라도 칸이 겹치면 같은 칸이다
     * (예: 3_10 UI32 와 3_11). 시각은 Action 의 실행 시각(atSeconds)이다.
     */
    private void checkSameTimeCells(Context ctx, Outcome out, TestPlan plan, List<Map<String, Object>> raw) {
        Map<Integer, Map<String, Integer>> written = new HashMap<>();   // 시각 -> (장비:칸 -> 처음 쓴 Action 위치)
        List<TestPlanAction> actions = plan.getActions();
        for (int i = 0; i < actions.size(); i++) {
            TestPlanAction a = actions.get(i);
            int at = a.getAtSeconds();
            Device device = ctx.devicesById().get(a.getDeviceId());
            DataType dt = a.getDataType() == null ? null : DataType.valueOf(a.getDataType());
            Map<String, Integer> cells = written.computeIfAbsent(at, k -> new HashMap<>());
            for (String cell : ServerDataCells.of(device == null ? null : device.getProtocol(), a.getTarget(), dt)) {
                Integer first = cells.putIfAbsent(a.getDeviceId() + ":" + cell, i);
                if (first != null) {
                    err(out, "A15", "target", i, intOrNull(raw.get(i).get("_row")),
                            "Action #" + (i + 1) + " writes " + cell + " at " + at + " s, which action #" + (first + 1)
                                    + " already writes at the same time.");
                    break;
                }
            }
        }
        if (!out.isOk()) plan.getActions().clear();
    }

    private TestPlanAction checkAction(Context ctx, Outcome out, Map<String, Object> raw, int index) {
        Integer row = intOrNull(raw.get("_row"));
        int before = out.errors.size();
        TestPlanAction a = new TestPlanAction();

        // A1·A2
        Device device = null;
        Integer deviceId = integral(raw.get("deviceId"));
        String deviceName = str(raw.get("device"));
        if (deviceId != null) {
            device = ctx.devicesById().get(deviceId);
            if (device == null) err(out, "A1", "device", index, row, "Device id " + deviceId + " does not exist.");
        } else if (deviceName != null) {
            device = ctx.devicesByName().get(deviceName);
            if (device == null) err(out, "A1", "device", index, row, "Device \"" + deviceName + "\" does not exist.");
        } else {
            err(out, "A1", "device", index, row, "Device is required.");
        }
        if (device != null && device.getProtocol() == Protocol.DB) {
            err(out, "A2", "device", index, row, "Device \"" + device.getName()
                    + "\" uses the DB protocol, which has no Server Data to write.");
            device = null;
        }
        if (device != null) a.setDeviceId(device.getId());

        // A6
        String dataTypeRaw = str(raw.get("dataType"));
        DataType dataType = null;
        if (dataTypeRaw != null) {
            try {
                dataType = DataType.valueOf(dataTypeRaw);
            } catch (IllegalArgumentException e) {
                err(out, "A6", "dataType", index, row, "Unknown data type \"" + dataTypeRaw + "\".");
            }
        }
        a.setDataType(dataType == null ? null : dataType.name());

        // A3·A4·A5·A7·A8
        String target = str(raw.get("target"));
        // XGT target 은 대문자로 관리한다(ServerData 와 같은 표기). %dw10 과 %DW10 은 같은 번지다
        if (target != null && device != null && device.getProtocol() == Protocol.XGT_PLC) {
            target = target.toUpperCase(Locale.ROOT);
        }
        // EtherNet/IP target 은 태그 이름이다. 예전 형식의 접두어는 떼되, 타입 대조에는 쓴다
        String legacyPrefix = null;
        if (target != null && device != null && device.getProtocol() == Protocol.ETHERNET_IP) {
            legacyPrefix = EthernetIPTypes.legacyPrefixOf(target);
            target = EthernetIPTypes.tagOf(target);
        }
        // 값 검사에 쓸 타입. EtherNet/IP 는 비어 있으면 태그의 타입이다
        DataType effectiveType = dataType;
        if (target == null) {
            err(out, "A3", "target", index, row, "Target is required.");
        } else {
            a.setTarget(target);
            if (device != null) effectiveType = checkTarget(ctx, out, device, target, legacyPrefix, dataType, index, row);
        }

        // A9·A10
        Double value = number(raw.get("value"));
        if (value == null) {
            err(out, "A9", "value", index, row, str(raw.get("value")) == null
                    ? "Value is required." : "Value \"" + str(raw.get("value")) + "\" is not a number.");
        } else if (value.isNaN() || value.isInfinite()) {
            err(out, "A9", "value", index, row, "Value must be a finite number.");
        } else {
            a.setValue(value);
            if (device != null && target != null) {
                String rangeError = ServerDataValueRules.valueError(device.getProtocol(), target, effectiveType, value);
                if (rangeError != null) err(out, "A10", "value", index, row, rangeError);
            }
        }

        // A11·A14
        Object lockRaw = raw.get("lockSeconds");
        if (str(lockRaw) == null) {
            a.setLockSeconds(0);
        } else {
            Integer lock = integral(lockRaw);
            if (lock == null) {
                err(out, isNumber(lockRaw) ? "A14" : "A11", "lockSeconds", index, row,
                        "Lock seconds must be a whole number (got \"" + str(lockRaw) + "\").");
            } else if (lock < 0) {
                err(out, "A11", "lockSeconds", index, row,
                        "Lock seconds must be 0 or more. A lock without expiry is only for the Server Data editor.");
            } else {
                a.setLockSeconds(lock);
            }
        }

        // A12·A14
        Object atRaw = raw.get("atSeconds");
        Integer at = integral(atRaw);
        if (str(atRaw) == null) {
            err(out, "A12", "at", index, row, "Run at (seconds from the start) is required.");
        } else if (at == null) {
            err(out, isNumber(atRaw) ? "A14" : "A12", "at", index, row,
                    "Run at must be a whole number of seconds (got \"" + str(atRaw) + "\").");
        } else if (at < 0 || at >= MAX_AT_SECONDS) {
            err(out, "A12", "at", index, row, "Run at must be from 0 to " + (MAX_AT_SECONDS - 1) + " seconds (got " + at + ").");
        } else {
            a.setAtSeconds(at);
        }

        // A13. 비어 있으면 실패해도 계속(FALSE)
        Object stopRaw = raw.get("stopOnFailure");
        Boolean stop = str(stopRaw) == null ? Boolean.FALSE : boolOrNull(stopRaw);
        if (stop == null) {
            err(out, "A13", "stop", index, row, "Stop on failure must be TRUE or FALSE (got \"" + str(stopRaw) + "\").");
        } else {
            a.setStopOnFailure(stop ? 1 : 0);
        }

        return out.errors.size() == before ? a : null;
    }

    /** @return 값 검사에 쓸 타입. EtherNet/IP 에서 dataType 이 비어 있으면 태그의 타입 */
    private DataType checkTarget(Context ctx, Outcome out, Device device, String target, String legacyPrefix,
                                 DataType dataType, int index, Integer row) {
        // A3·A5·A8 — 형식·주소 범위, Modbus FC 1~4, EtherNet/IP 가 보낼 수 있는 타입
        String formatError = simulationTargetValidator.validate(device, target, dataType);
        if (formatError != null) {
            err(out, "A3", "target", index, row, formatError);
            return dataType;
        }
        Protocol p = device.getProtocol();
        // A4 — 맵 기반 저장이거나 시뮬레이션이 곧 object·태그인 프로토콜은 등록된 target 에만 쓴다
        if (p == Protocol.SNMP || p == Protocol.GENERIC || p == Protocol.BACNET || p == Protocol.ETHERNET_IP) {
            if (!ctx.simulationTargets(device).contains(target)) {
                err(out, "A4", "target", index, row, "No simulation with target \"" + target + "\" on device \""
                        + device.getName() + "\". " + p + " can only write to a registered simulation target.");
                return dataType;
            }
        }
        // A6 — EtherNet/IP 태그는 타입이 하나다. 다른 타입으로 쓰라는 요청은 거절한다(REST 쓰기와 같다)
        if (p == Protocol.ETHERNET_IP) {
            DataType tagType = ctx.tagType(device, target);
            CIPData.Type tagCip = EthernetIPTypes.cipTypeOf(tagType);
            CIPData.Type asked = legacyPrefix != null ? CIPData.Type.forName(legacyPrefix)
                    : (dataType != null ? EthernetIPTypes.cipTypeOf(dataType) : tagCip);
            if (tagCip != null && asked != tagCip) {
                err(out, "A6", "dataType", index, row, "Tag \"" + target + "\" is " + tagCip + " (" + tagType
                        + "). This action would write " + (asked == null ? dataType : asked) + ".");
            }
            if (dataType == null) return tagType;
        }
        // A6·A7 — Modbus 레지스터는 dataType 이 있어야 하고, XGT 는 크기 문자와 폭이 같아야 한다.
        // REST 쓰기와 같은 규칙(ServerDataValueRules)이다
        String typeError = ServerDataValueRules.dataTypeError(p, target, dataType);
        if (typeError != null) {
            err(out, p == Protocol.XGT_PLC ? "A7" : "A6", "dataType", index, row, typeError);
        }
        return dataType;
    }

    // ── 공용 ─────────────────────────────────────────────────────────────

    /** 한 번의 검증 동안 장비·시뮬레이션 조회를 한 번씩만 한다. */
    private final class Context {
        private Map<Integer, Device> byId;
        private Map<String, Device> byName;
        private final Map<Integer, List<Simulation>> simulations = new HashMap<>();

        Map<Integer, Device> devicesById() {
            load();
            return byId;
        }

        Map<String, Device> devicesByName() {
            load();
            return byName;
        }

        private void load() {
            if (byId != null) return;
            byId = new HashMap<>();
            byName = new HashMap<>();
            for (Device d : deviceService.selectDevices()) {
                byId.put(d.getId(), d);
                if (d.getName() != null) byName.put(d.getName(), d);
            }
        }

        private List<Simulation> simulationsOf(Integer deviceId) {
            return simulations.computeIfAbsent(deviceId, simulationService::selectSimulationsByDeviceId);
        }

        /** 장비에 등록된 target. EtherNet/IP 는 태그 이름으로 모은다. */
        Set<String> simulationTargets(Device device) {
            Set<String> s = new HashSet<>();
            for (Simulation sim : simulationsOf(device.getId())) {
                if (sim.getTarget() == null) continue;
                s.add(device.getProtocol() == Protocol.ETHERNET_IP ? EthernetIPTypes.tagOf(sim.getTarget()) : sim.getTarget());
            }
            return s;
        }

        /** EtherNet/IP 태그의 타입. 그 태그로 등록된 Simulation 의 첫 정책이 정한다. */
        DataType tagType(Device device, String tag) {
            for (Simulation sim : simulationsOf(device.getId())) {
                if (tag.equals(EthernetIPTypes.tagOf(sim.getTarget())) && sim.getPolicies() != null
                        && !sim.getPolicies().isEmpty()) {
                    return sim.getPolicies().get(0).getData_type();
                }
            }
            return null;
        }
    }

    private static void err(Outcome out, String rule, String field, int index, Integer row, String message) {
        out.errors.add(new Issue(rule, field, index, row, message));
    }

    /** 앞뒤 공백을 뗀 문자열. null·빈 문자열은 null. */
    private static String str(Object o) {
        if (o == null) return null;
        String s = (o instanceof Double && ((Double) o) == Math.rint((Double) o) && !((Double) o).isInfinite())
                ? String.valueOf(((Double) o).longValue()) : o.toString();
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isNumber(Object o) {
        return number(o) != null;
    }

    /** 숫자이거나 숫자로 읽히는 문자열. 불리언은 숫자가 아니다. */
    private static Double number(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.isEmpty()) return null;
            try {
                return Double.valueOf(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** A14. 정수로 읽히는 값. {@code 5.0} 은 5, {@code 5.5} 는 null. */
    private static Integer integral(Object o) {
        Double d = number(o);
        if (d == null || d.isNaN() || d.isInfinite() || d != Math.rint(d)) return null;
        if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) return null;
        return d.intValue();
    }

    private static Integer intOrNull(Object o) {
        return integral(o);
    }

    /** 불리언, 또는 "TRUE"/"FALSE"(대소문자 무시). 그 밖은 null. */
    private static Boolean boolOrNull(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        String s = str(o);
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        return null;
    }

    private static Object cell(List<?> cells, Integer col) {
        return col == null || col >= cells.size() ? null : cells.get(col);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<Object>) raw) {
            out.add(o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap());
        }
        return out;
    }
}
