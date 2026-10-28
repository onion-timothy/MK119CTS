package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanAction;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.enums.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * docs/TEST_PLAN_VALIDATION.md 의 등록 시점 규칙(P·S·A·X)을 ID 별로 본다.
 * 장비·시뮬레이션·Plan 조회는 mock 이고, target 형식 검사는 실제 SimulationTargetValidator 를 쓴다.
 */
class TestPlanValidatorTest {
    private TestPlanValidator validator;
    private TestPlanService testPlanService;

    private static final int MODBUS = 1, XGT = 2, SNMP = 3, DB = 4, DAEYUN = 5, BACNET = 6, EIP = 7;

    @BeforeEach
    void setUp() {
        DeviceService deviceService = mock(DeviceService.class);
        SimulationService simulationService = mock(SimulationService.class);
        testPlanService = mock(TestPlanService.class);
        when(deviceService.selectDevices()).thenReturn(Arrays.asList(
                device(MODBUS, "mb", Protocol.MODBUS, "100"),
                device(XGT, "xgt", Protocol.XGT_PLC, "1000"),
                device(SNMP, "snmp", Protocol.SNMP, null),
                device(DB, "db", Protocol.DB, null),
                device(DAEYUN, "dy", Protocol.DAEYUN_RCU, null),
                device(BACNET, "bac", Protocol.BACNET, null),
                device(EIP, "eip", Protocol.ETHERNET_IP, null)));
        when(simulationService.selectSimulationsByDeviceId(EIP)).thenReturn(Arrays.asList(
                simulation("TAG1", DataType.FP32AD),
                simulation("REAL@OLD", DataType.FP32AD),      // 아직 옮기지 않은 예전 형식
                simulation("FLAG", DataType.BOOL)));
        when(simulationService.selectSimulationsByDeviceId(SNMP)).thenReturn(
                Collections.singletonList(simulation("1.3.6.1.4.1.1.0")));
        when(simulationService.selectSimulationsByDeviceId(BACNET)).thenReturn(
                Collections.singletonList(simulation("analog_value 1")));
        when(testPlanService.existsByName(anyString(), any())).thenReturn(false);
        validator = new TestPlanValidator(deviceService, simulationService, testPlanService,
                new SimulationTargetValidator());
    }

    // ── 통과 ─────────────────────────────────────────────────────────────

    @Test
    void validPlanIsNormalized() {
        Map<String, Object> body = plan("  Ramp  ",
                action(MODBUS, "3_10", 500, "UI16AB", 30, 5, true),
                action(XGT, "%DW50", 7, "UI16AB", null, 0, null));
        body.put("scheduleType", "MINUTE");
        body.put("scheduleMinute", 15);
        body.put("scheduleSecond", 0.0);
        body.put("scheduleHour", 9);          // MINUTE 는 시를 쓰지 않는다
        body.put("scheduleEnabled", true);

        TestPlanValidator.Outcome out = validator.validatePlan(body, null);

        assertTrue(out.isOk(), () -> rules(out).toString());
        TestPlan p = out.getPlan();
        assertEquals("Ramp", p.getName());
        assertEquals(ScheduleType.MINUTE, p.getScheduleType());
        assertNull(p.getScheduleHour());
        assertEquals(15, p.getScheduleMinute());
        assertEquals(0, p.getScheduleSecond());
        assertEquals(TestPlan.SCHEDULE_ENABLED, p.getScheduleEnabled());
        TestPlanAction a0 = p.getActions().get(0), a1 = p.getActions().get(1);
        assertEquals(MODBUS, a0.getDeviceId());
        assertEquals(30, a0.getLockSeconds());
        assertEquals(1, a0.getStopOnFailure());
        assertEquals(0, a1.getLockSeconds());       // A11 비면 0
        assertEquals(0, a1.getStopOnFailure());     // A13 비면 FALSE
    }

    // ── Plan ─────────────────────────────────────────────────────────────

    @Test
    void p1NameRequiredAndLength() {
        assertRule("P1", validator.validatePlan(plan("   ", okAction()), null));
        assertRule("P1", validator.validatePlan(plan(repeat('x', 101), okAction()), null));
        assertTrue(validator.validatePlan(plan(repeat('x', 100), okAction()), null).isOk());
    }

    @Test
    void p2NameMustBeUniqueExceptSelf() {
        when(testPlanService.existsByName("Dup", null)).thenReturn(true);
        when(testPlanService.existsByName("Dup", 7)).thenReturn(false);
        assertRule("P2", validator.validatePlan(plan("Dup", okAction()), null));
        assertTrue(validator.validatePlan(plan("Dup", okAction()), 7).isOk());
    }

    @Test
    void p3ActionCount() {
        assertRule("P3", validator.validatePlan(plan("Empty"), null));
        Map<String, Object>[] many = new Map[1001];
        for (int i = 0; i < many.length; i++) many[i] = okAction();
        assertRule("P3", validator.validatePlan(plan("Many", many), null));
    }

    // ── Schedule ─────────────────────────────────────────────────────────

    @Test
    void s1UnknownType() {
        Map<String, Object> body = plan("S1", okAction());
        body.put("scheduleType", "DAILY");
        assertRule("S1", validator.validatePlan(body, null));
    }

    @Test
    void s2FieldRanges() {
        Map<String, Object> body = plan("S2", okAction());
        body.put("scheduleType", "HOUR");
        body.put("scheduleHour", 24);
        body.put("scheduleMinute", 0);
        body.put("scheduleSecond", 0);
        assertRule("S2", validator.validatePlan(body, null));

        body.put("scheduleHour", 23);
        body.put("scheduleSecond", 1.5);
        assertRule("S2", validator.validatePlan(body, null));

        body.remove("scheduleSecond");
        assertRule("S2", validator.validatePlan(body, null));
    }

    @Test
    void s3DurationMustBeShorterThanPeriod() {
        Map<String, Object> body = plan("S3",
                action(MODBUS, "3_1", 1, "UI16AB", null, 0, null),
                action(MODBUS, "3_2", 1, "UI16AB", null, 60, null));   // 실행 시간 60 == 주기 60
        body.put("scheduleType", "SECOND");
        body.put("scheduleSecond", 0);
        assertRule("S3", validator.validatePlan(body, null));

        body.put("actions", Arrays.asList(
                action(MODBUS, "3_1", 1, "UI16AB", null, 0, null),
                action(MODBUS, "3_2", 1, "UI16AB", null, 59, null)));  // 59 < 60
        assertTrue(validator.validatePlan(body, null).isOk());
    }

    @Test
    void s3DurationRunsUntilTheLastLockIsReleased() {
        // 0초에 쓰고 60초 잠근다: 실행 시간 60 == 주기 60
        Map<String, Object> body = plan("S3", action(MODBUS, "3_1", 1, "UI16AB", 60, 0, null));
        body.put("scheduleType", "SECOND");
        body.put("scheduleSecond", 0);
        assertRule("S3", validator.validatePlan(body, null));

        // 같은 칸을 5초에 다시 쓰면(lock 0) 앞 lock 은 그때 풀린다: 실행 시간 5
        body.put("actions", Arrays.asList(
                action(MODBUS, "3_1", 1, "UI16AB", 100, 0, null),
                action(MODBUS, "3_1", 2, "UI16AB", null, 5, null)));
        assertTrue(validator.validatePlan(body, null).isOk());

        // 칸 하나만 다시 쓰면 나머지 칸의 lock 은 남는다: 3_10 UI32 는 3_10·3_11, 다시 쓰는 것은 3_11 뿐
        body.put("actions", Arrays.asList(
                action(MODBUS, "3_10", 1, "UI32AD", 100, 0, null),
                action(MODBUS, "3_11", 2, "UI16AB", null, 5, null)));
        assertRule("S3", validator.validatePlan(body, null));
    }

    /** INTERVAL 은 간격(초)만 받는다. 1~86400. 실행 시간은 간격보다 짧아야 한다(S3). cron 칸은 비운다. */
    @Test
    void intervalScheduleTakesSecondsAndMustOutlastTheRun() {
        Map<String, Object> body = plan("Interval", action(MODBUS, "3_1", 1, "UI16AB", 30, 0, null));   // 실행 시간 30
        body.put("scheduleType", "INTERVAL");
        body.put("scheduleSecond", 7);
        body.put("scheduleEnabled", true);
        for (Object bad : new Object[]{null, 0, -5, 86401, 1.5, "abc"}) {
            body.put("scheduleIntervalSeconds", bad);
            assertRule("S2", validator.validatePlan(body, null));
        }
        body.put("scheduleIntervalSeconds", 30);                 // 30 == 30
        assertRule("S3", validator.validatePlan(body, null));

        body.put("scheduleIntervalSeconds", "31");
        TestPlanValidator.Outcome out = validator.validatePlan(body, null);
        assertTrue(out.isOk());
        assertEquals(ScheduleType.INTERVAL, out.getPlan().getScheduleType());
        assertEquals(31, out.getPlan().getScheduleIntervalSeconds().intValue());
        assertEquals(31, out.getPlan().schedulePeriodSeconds());
        assertNull(out.getPlan().getScheduleSecond());
        assertEquals(TestPlan.SCHEDULE_ENABLED, out.getPlan().getScheduleEnabled());

        // cron 으로 바꾸면 간격은 비운다
        body.put("scheduleType", "MINUTE");
        body.put("scheduleMinute", 0);
        out = validator.validatePlan(body, null);
        assertTrue(out.isOk());
        assertNull(out.getPlan().getScheduleIntervalSeconds());
    }

    @Test
    void scheduleNoneIsNeverEnabled() {
        Map<String, Object> body = plan("None", okAction());
        body.put("scheduleEnabled", true);
        TestPlanValidator.Outcome out = validator.validatePlan(body, null);
        assertTrue(out.isOk());
        assertEquals(TestPlan.SCHEDULE_DISABLED, out.getPlan().getScheduleEnabled());
    }

    // ── Action ───────────────────────────────────────────────────────────

    @Test
    void a1DeviceMustExist() {
        assertRule("A1", validator.validatePlan(plan("A1", action(999, "3_1", 1, "UI16AB", null, 0, null)), null));
        Map<String, Object> byName = action(null, "3_1", 1, "UI16AB", null, 0, null);
        byName.put("device", "nope");
        assertRule("A1", validator.validatePlan(plan("A1", byName), null));
        byName.put("device", " mb ");
        assertTrue(validator.validatePlan(plan("A1", byName), null).isOk());
        byName.put("device", "MB");    // 대소문자 구분
        assertRule("A1", validator.validatePlan(plan("A1", byName), null));
    }

    @Test
    void a2DbProtocolRejected() {
        assertRule("A2", validator.validatePlan(plan("A2", action(DB, "select 1", 1, null, null, 0, null)), null));
    }

    @Test
    void a3TargetFormatAndRange() {
        assertRule("A3", validator.validatePlan(plan("A3", action(MODBUS, "", 1, null, null, 0, null)), null));
        assertRule("A3", validator.validatePlan(plan("A3", action(MODBUS, "3_100", 1, "UI16AB", null, 0, null)), null));
        assertRule("A3", validator.validatePlan(plan("A3", action(XGT, "%ZW1", 1, "UI16AB", null, 0, null)), null));
    }

    @Test
    void a4RegisteredTargetOnly() {
        assertRule("A4", validator.validatePlan(plan("A4", action(SNMP, "1.3.6.1.4.1.2.0", 1, null, null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("A4", action(SNMP, "1.3.6.1.4.1.1.0", 1, null, null, 0, null)), null).isOk());
        assertRule("A4", validator.validatePlan(plan("A4", action(BACNET, "analog_value 2", 1, null, null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("A4", action(BACNET, "analog_value 1", 1, null, null, 0, null)), null).isOk());
    }

    @Test
    void a5ModbusWritableFunctionCodes() {
        assertRule("A3", validator.validatePlan(plan("A5", action(MODBUS, "5_1", 1, null, null, 0, null)), null));
    }

    @Test
    void a6UnknownDataType() {
        assertRule("A6", validator.validatePlan(plan("A6", action(MODBUS, "3_1", 1, "UINT16", null, 0, null)), null));
    }

    @Test
    void a7XgtSizeMustMatchDataType() {
        assertRule("A7", validator.validatePlan(plan("A7", action(XGT, "%DW10", 1, "UI32AD", null, 0, null)), null));
        assertRule("A7", validator.validatePlan(plan("A7", action(XGT, "%DD10", 1, null, null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("A7", action(XGT, "%DB10", 1, "UI8", null, 0, null)), null).isOk());
        assertTrue(validator.validatePlan(plan("A7", action(XGT, "%DD10", 1, "FP32AD", null, 0, null)), null).isOk());
    }

    @Test
    void xgtTargetIsStoredUpperCase() {
        TestPlanValidator.Outcome out = validator.validatePlan(plan("Upper", action(XGT, "%dw10", 1, "UI16AB", null, 0, null)), null);
        assertTrue(out.isOk(), () -> rules(out).toString());
        assertEquals("%DW10", out.getPlan().getActions().get(0).getTarget());
        assertRule("A7", validator.validatePlan(plan("Upper", action(XGT, "%dd10", 1, "UI16AB", null, 0, null)), null));
    }

    @Test
    void ethernetIpWritesRegisteredTagsWithTheirType() {
        // 태그 이름만, dataType 없이: 태그의 타입(REAL)으로 쓴다
        TestPlanValidator.Outcome ok = validator.validatePlan(plan("E", action(EIP, "TAG1", 1.5, null, null, 0, null)), null);
        assertTrue(ok.isOk(), () -> rules(ok).toString());
        assertEquals("TAG1", ok.getPlan().getActions().get(0).getTarget());
        // 예전 형식도 받고 태그 이름으로 저장한다. 아직 옮기지 않은 Simulation(REAL@OLD) 도 태그로 찾는다
        TestPlanValidator.Outcome legacy = validator.validatePlan(plan("E", action(EIP, "REAL@OLD", 2, null, null, 0, null)), null);
        assertTrue(legacy.isOk(), () -> rules(legacy).toString());
        assertEquals("OLD", legacy.getPlan().getActions().get(0).getTarget());
        // A4 등록되지 않은 태그
        assertRule("A4", validator.validatePlan(plan("E", action(EIP, "NOPE", 1, null, null, 0, null)), null));
        // A6 태그와 다른 타입: 접두어로 말하든 dataType 으로 말하든
        assertRule("A6", validator.validatePlan(plan("E", action(EIP, "INT@TAG1", 1, null, null, 0, null)), null));
        assertRule("A6", validator.validatePlan(plan("E", action(EIP, "TAG1", 1, "SI16AB", null, 0, null)), null));
        // A3 EtherNet/IP 로 보낼 수 없는 타입
        assertRule("A3", validator.validatePlan(plan("E", action(EIP, "TAG1", 1, "FP64AH", null, 0, null)), null));
        // A10 dataType 이 없으면 태그의 타입으로 값을 본다: BOOL 태그에 2
        assertRule("A10", validator.validatePlan(plan("E", action(EIP, "FLAG", 2, null, null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("E", action(EIP, "FLAG", 1, null, null, 0, null)), null).isOk());
    }

    @Test
    void boolAndBitsAreForEthernetIpOnly() {
        assertRule("A3", validator.validatePlan(plan("B", action(MODBUS, "3_1", 1, "BOOL", null, 0, null)), null));
        assertRule("A3", validator.validatePlan(plan("B", action(XGT, "%DD1", 1, "BITS", null, 0, null)), null));
    }

    @Test
    void a9ValueMustBeFiniteNumber() {
        assertRule("A9", validator.validatePlan(plan("A9", action(MODBUS, "3_1", null, "UI16AB", null, 0, null)), null));
        Map<String, Object> text = action(MODBUS, "3_1", null, "UI16AB", null, 0, null);
        text.put("value", "abc");
        assertRule("A9", validator.validatePlan(plan("A9", text), null));
        text.put("value", "NaN");
        assertRule("A9", validator.validatePlan(plan("A9", text), null));
    }

    @Test
    void a10ValueRange() {
        assertRule("A10", validator.validatePlan(plan("A10", action(MODBUS, "3_1", 65536, "UI16AB", null, 0, null)), null));
        assertRule("A10", validator.validatePlan(plan("A10", action(MODBUS, "3_1", -1, "UI16AB", null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("A10", action(MODBUS, "3_1", 65535, "UI16AB", null, 0, null)), null).isOk());
        assertRule("A10", validator.validatePlan(plan("A10", action(MODBUS, "3_1", -32769, "SI16AB", null, 0, null)), null));
        assertRule("A10", validator.validatePlan(plan("A10", action(MODBUS, "1_1", 2, null, null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("A10", action(MODBUS, "1_1", 1, null, null, 0, null)), null).isOk());
        assertRule("A10", validator.validatePlan(plan("A10", action(DAEYUN, "@RCUDI_1", 5, null, null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("A10", action(MODBUS, "3_1", 1e30, "FP32AD", null, 0, null)), null).isOk());
    }

    @Test
    void a6ModbusRegisterNeedsDataType() {
        assertRule("A6", validator.validatePlan(plan("A6", action(MODBUS, "3_1", 1, null, null, 0, null)), null));
        assertRule("A6", validator.validatePlan(plan("A6", action(MODBUS, "4_1", 1, null, null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("A6", action(MODBUS, "1_1", 1, null, null, 0, null)), null).isOk());
    }

    @Test
    void a10WholeNumbersOnlyForIntegerTypes() {
        assertRule("A10", validator.validatePlan(plan("A10", action(MODBUS, "3_1", 12.7, "UI16AB", null, 0, null)), null));
        assertRule("A10", validator.validatePlan(plan("A10", action(MODBUS, "3_1", -12.7, "SI16AB", null, 0, null)), null));
        assertRule("A10", validator.validatePlan(plan("A10", action(DAEYUN, "@RCUAI_1", 1.5, null, null, 0, null)), null));
        assertTrue(validator.validatePlan(plan("A10", action(MODBUS, "3_1", 12.7, "FP32AD", null, 0, null)), null).isOk());
        assertTrue(validator.validatePlan(plan("A10", action(MODBUS, "3_1", 4294967295d, "UI32AD", null, 0, null)), null).isOk());
        assertRule("A10", validator.validatePlan(plan("A10", action(MODBUS, "3_1", 4294967296d, "UI32AD", null, 0, null)), null));
    }

    @Test
    void a15SameTimeSameCell() {
        // 같은 시각(0초)에 같은 칸
        assertRule("A15", validator.validatePlan(plan("A15",
                action(MODBUS, "3_10", 1, "UI16AB", null, 0, null),
                action(MODBUS, "3_10", 2, "UI16AB", null, 0, null)), null));
        // 0초가 아닌 같은 시각도 같다
        assertRule("A15", validator.validatePlan(plan("A15",
                action(MODBUS, "3_1", 1, "UI16AB", null, 0, null),
                action(MODBUS, "3_10", 1, "UI16AB", null, 7, null),
                action(MODBUS, "3_10", 2, "UI16AB", null, 7, null)), null));
        // target 은 다르지만 칸이 겹친다: 3_10 UI32 는 3_10·3_11 을 쓴다
        TestPlanValidator.Outcome overlap = validator.validatePlan(plan("A15",
                action(MODBUS, "3_10", 1, "UI32AD", null, 0, null),
                action(MODBUS, "3_11", 2, "UI16AB", null, 0, null)), null);
        assertRule("A15", overlap);
        assertEquals(Integer.valueOf(1), overlap.getErrors().get(0).getAction());
        // XGT 는 byte 번지로 센다: %DW10 은 %DB20·%DB21
        assertRule("A15", validator.validatePlan(plan("A15",
                action(XGT, "%DW10", 1, "UI16AB", null, 0, null),
                action(XGT, "%DB21", 2, "UI8", null, 0, null)), null));
        // 시각이 다르면 된다
        assertTrue(validator.validatePlan(plan("A15",
                action(MODBUS, "3_10", 1, "UI16AB", null, 0, null),
                action(MODBUS, "3_10", 2, "UI16AB", null, 1, null)), null).isOk());
        // 같은 시각이라도 장비가 다르면 된다
        assertTrue(validator.validatePlan(plan("A15",
                action(MODBUS, "3_10", 1, "UI16AB", null, 0, null),
                action(XGT, "%DB20", 2, "UI8", null, 0, null)), null).isOk());
    }

    @Test
    void a11LockSecondsNonNegative() {
        assertRule("A11", validator.validatePlan(plan("A11", action(MODBUS, "3_1", 1, "UI16AB", -1, 0, null)), null));
        Map<String, Object> text = action(MODBUS, "3_1", 1, "UI16AB", null, 0, null);
        text.put("lockSeconds", "long");
        assertRule("A11", validator.validatePlan(plan("A11", text), null));
    }

    @Test
    void a12AtRequiredAndInRange() {
        assertRule("A12", validator.validatePlan(plan("A12", action(MODBUS, "3_1", 1, "UI16AB", null, null, null)), null));
        assertRule("A12", validator.validatePlan(plan("A12", action(MODBUS, "3_1", 1, "UI16AB", null, -1, null)), null));
        assertRule("A12", validator.validatePlan(plan("A12", okAction(), action(MODBUS, "3_2", 1, "UI16AB", null, 86400, null)), null));
        assertTrue(validator.validatePlan(plan("A12", okAction(), action(MODBUS, "3_2", 1, "UI16AB", null, 86399, null)), null).isOk());
    }

    @Test
    void a16EarliestActionRunsAtZero() {
        TestPlanValidator.Outcome late = validator.validatePlan(plan("A16",
                action(MODBUS, "3_1", 1, "UI16AB", null, 5, null),
                action(MODBUS, "3_2", 1, "UI16AB", null, 3, null)), null);
        assertRule("A16", late);
        assertEquals(Integer.valueOf(1), late.getErrors().get(0).getAction());   // 가장 이른 Action 을 가리킨다
        // 목록 순서와 상관없이 가장 이른 것이 0초면 된다. 저장은 시각 순서다(seq)
        TestPlanValidator.Outcome ok = validator.validatePlan(plan("A16",
                action(MODBUS, "3_1", 1, "UI16AB", null, 4, null),
                action(MODBUS, "3_2", 1, "UI16AB", null, 0, null)), null);
        assertTrue(ok.isOk(), () -> rules(ok).toString());
        assertEquals(4, ok.getPlan().getActions().get(0).getAtSeconds());   // 검증은 목록 순서를 그대로 둔다(정렬은 저장할 때)
    }

    @Test
    void a13StopTrueOrFalse() {
        Map<String, Object> a = action(MODBUS, "3_1", 1, "UI16AB", null, 0, null);
        a.put("stopOnFailure", "yes");
        assertRule("A13", validator.validatePlan(plan("A13", a), null));
        a.put("stopOnFailure", "TRUE");
        assertEquals(1, validator.validatePlan(plan("A13", a), null).getPlan().getActions().get(0).getStopOnFailure());
    }

    @Test
    void a14FractionInIntegerField() {
        assertRule("A14", validator.validatePlan(plan("A14", action(MODBUS, "3_1", 1, "UI16AB", 5.5, 0, null)), null));
        assertRule("A14", validator.validatePlan(plan("A14", okAction(), action(MODBUS, "3_2", 1, "UI16AB", null, 2.5, null)), null));
        TestPlanValidator.Outcome ok = validator.validatePlan(plan("A14",
                action(MODBUS, "3_1", 1, "UI16AB", 5.0, 0.0, null), action(MODBUS, "3_2", 1, "UI16AB", null, 2.0, null)), null);
        assertTrue(ok.isOk(), () -> rules(ok).toString());
        assertEquals(5, ok.getPlan().getActions().get(0).getLockSeconds());
        assertEquals(2, ok.getPlan().getActions().get(1).getAtSeconds());
    }

    @Test
    void allActionErrorsAreReportedWithPosition() {
        TestPlanValidator.Outcome out = validator.validatePlan(plan("Many errors",
                okAction(),
                action(MODBUS, "9_1", 1, null, -1, -1, null)), null);
        assertFalse(out.isOk());
        assertTrue(out.getErrors().stream().allMatch(i -> Integer.valueOf(1).equals(i.getAction())));
        assertEquals(new HashSet<>(Arrays.asList("A3", "A11", "A12")), rules(out));
    }

    // ── 엑셀 ─────────────────────────────────────────────────────────────

    @Test
    void x2SheetCount() {
        assertFalse(validator.validateWorkbook(Collections.emptyList()).isOk());
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (int i = 0; i < 51; i++) sheets.add(sheet("S" + i, header(), row("mb", "3_1", 1.0, "UI16AB", null, 0.0, null)));
        TestPlanValidator.WorkbookOutcome wb = validator.validateWorkbook(sheets);
        assertFalse(wb.isOk());
        assertEquals("X2", wb.getErrors().get(0).getRule());
    }

    @Test
    void workbookBuildsOnePlanPerSheet() {
        TestPlanValidator.WorkbookOutcome wb = validator.validateWorkbook(Arrays.asList(
                sheet("First", header(),
                        row("mb", "3_1", 1.0, "UI16AB", 10.0, 2.0, true),
                        row(null, null, null, null, null, null, null),          // X5 빈 행
                        row("xgt", "%DW5", 3.0, "UI16AB", null, 0.0, "FALSE")),
                sheet("Second", header(), row("mb", "3_2", 2.0, "UI16AB", null, 0.0, null))));
        assertTrue(wb.isOk(), () -> wb.getSheets().stream().map(s -> rules(s.getOutcome())).collect(Collectors.toList()).toString());
        List<TestPlan> plans = wb.plans();
        assertEquals(2, plans.size());
        assertEquals("First", plans.get(0).getName());
        assertEquals(2, plans.get(0).getActions().size());
        assertEquals(ScheduleType.NONE, plans.get(0).getScheduleType());   // X8
    }

    @Test
    void p2DuplicateSheetNamesInFile() {
        TestPlanValidator.WorkbookOutcome wb = validator.validateWorkbook(Arrays.asList(
                sheet("Same", header(), row("mb", "3_1", 1.0, "UI16AB", null, 0.0, null)),
                sheet("Same", header(), row("mb", "3_1", 1.0, "UI16AB", null, 0.0, null))));
        assertFalse(wb.isOk());
        assertTrue(wb.getSheets().get(0).getOutcome().isOk());
        assertRule("P2", wb.getSheets().get(1).getOutcome());
    }

    @Test
    void x4HeaderColumns() {
        List<Object> missing = new ArrayList<>(header());
        missing.remove("StopOnFailure");
        assertRule("X4", validator.validateWorkbook(Collections.singletonList(
                sheet("H", missing, row("mb", "3_1", 1.0, "UI16AB", null, 0.0)))).getSheets().get(0).getOutcome());

        List<Object> mixed = Arrays.asList("note", "STOPONFAILURE", "runatseconds", "lockseconds", "DATATYPE", "value", "target", "device");
        TestPlanValidator.Outcome out = validator.validateWorkbook(Collections.singletonList(
                sheet("H", mixed, row("memo", null, 0.0, null, "UI16AB", 1.0, "3_1", "mb")))).getSheets().get(0).getOutcome();
        assertTrue(out.isOk(), () -> rules(out).toString());
        assertEquals("X4", out.getWarnings().get(0).getRule());   // 모르는 열은 경고
    }

    @Test
    void x6SheetWithoutDataRows() {
        assertRule("P3", validator.validateWorkbook(Collections.singletonList(
                sheet("Empty", header(), row(null, null, null, null, null, null, null)))).getSheets().get(0).getOutcome());
    }

    @Test
    void excelRowNumbersAreReported() {
        TestPlanValidator.Outcome out = validator.validateWorkbook(Collections.singletonList(
                sheet("Rows", header(),
                        row("mb", "3_1", 1.0, "UI16AB", null, 0.0, null),
                        row(null, null, null, null, null, null, null),
                        row("mb", "3_1", 1.0, "UI16AB", null, -1.0, null)))).getSheets().get(0).getOutcome();
        assertEquals(4, out.getErrors().get(0).getRow());   // 헤더 1행, 데이터 2·3(빈)·4행
        assertEquals(1, out.getErrors().get(0).getAction()); // 빈 행을 뺀 Action 위치
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private static Device device(int id, String name, Protocol protocol, String registers) {
        Device d = new Device();
        d.setId(id);
        d.setName(name);
        d.setProtocol(protocol);
        Map<Object, Object> cs = new HashMap<>();
        if (registers != null) cs.put("registers", registers);
        d.setCommunicationSettings(cs);
        return d;
    }

    private static Simulation simulation(String target) {
        Simulation s = new Simulation();
        s.setTarget(target);
        return s;
    }

    private static Simulation simulation(String target, DataType dataType) {
        Simulation s = simulation(target);
        SimulationPolicy p = new SimulationPolicy();
        p.setData_type(dataType);
        s.setPolicies(Collections.singletonList(p));
        return s;
    }

    @SafeVarargs
    private static Map<String, Object> plan(String name, Map<String, Object>... actions) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("actions", new ArrayList<>(Arrays.asList(actions)));
        return body;
    }

    private static Map<String, Object> action(Integer deviceId, String target, Number value, String dataType,
                                              Number lockSeconds, Number at, Object stop) {
        Map<String, Object> a = new HashMap<>();
        a.put("deviceId", deviceId);
        a.put("target", target);
        a.put("value", value);
        a.put("dataType", dataType);
        a.put("lockSeconds", lockSeconds);
        a.put("atSeconds", at);
        a.put("stopOnFailure", stop);
        return a;
    }

    private static Map<String, Object> okAction() {
        return action(MODBUS, "3_1", 1, "UI16AB", null, 0, null);
    }

    private static List<Object> header() {
        return new ArrayList<>(Arrays.asList("Device", "Target", "Value", "DataType", "LockSeconds", "RunAtSeconds", "StopOnFailure"));
    }

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    @SafeVarargs
    private static Map<String, Object> sheet(String name, List<Object> header, List<Object>... rows) {
        Map<String, Object> s = new HashMap<>();
        s.put("name", name);
        List<Object> all = new ArrayList<>();
        all.add(header);
        all.addAll(Arrays.asList(rows));
        s.put("rows", all);
        return s;
    }

    private static Set<String> rules(TestPlanValidator.Outcome out) {
        return out.getErrors().stream().map(TestPlanValidator.Issue::getRule).collect(Collectors.toSet());
    }

    private static void assertRule(String rule, TestPlanValidator.Outcome out) {
        assertFalse(out.isOk(), "expected " + rule + " but passed");
        assertTrue(rules(out).contains(rule), () -> "expected " + rule + " but got " + rules(out) + " "
                + out.getErrors().stream().map(TestPlanValidator.Issue::getMessage).collect(Collectors.toList()));
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }
}
