package com.onionsoftware.mk119.commtestsuite.testplan;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanAction;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRun;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRunAction;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.TestPlanService;
import com.onionsoftware.mk119.commtestsuite.enums.ScheduleType;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanActionStatus;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanRunResult;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanTrigger;
import com.onionsoftware.mk119.commtestsuite.web.serverdata.ServerDataWriteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실행기의 순서·대기·실패 처리·중지를 본다(docs/TEST_PLAN_VALIDATION.md 의 R·E).
 * 저장과 쓰기는 mock 이고, 스케줄러는 실제 것을 써서 시간이 실제로 흐른다.
 */
class TestPlanRunnerTest {
    private TestPlanService testPlanService;
    private ServerDataWriteService writeService;
    private DeviceService deviceService;
    private TestPlanRunner runner;

    /** write 가 불린 순서와 시각. */
    private final List<String> writes = new CopyOnWriteArrayList<>();
    private final List<Long> writeTimes = new CopyOnWriteArrayList<>();
    /** 이 target 에 쓰면 400 을 돌려준다. */
    private final Set<String> failing = new HashSet<>();
    private final AtomicLong runIds = new AtomicLong(100);

    private static final int LIVE = 1, DELETED = 2;

    @BeforeEach
    void setUp() {
        testPlanService = mock(TestPlanService.class);
        writeService = mock(ServerDataWriteService.class);
        deviceService = mock(DeviceService.class);
        when(deviceService.getDevice(LIVE)).thenReturn(new Device());
        when(deviceService.getDevice(DELETED)).thenReturn(null);
        when(testPlanService.startRun(any(), any())).thenAnswer(inv -> {
            TestPlanRun run = new TestPlanRun();
            run.setId(runIds.incrementAndGet());
            run.setPlan(inv.getArgument(0));
            run.setTriggerType(inv.getArgument(1));
            run.setStartedAt(LocalDateTime.now());
            return run;
        });
        when(writeService.write(anyInt(), anyString(), anyDouble(), any(), any())).thenAnswer(inv -> {
            String target = inv.getArgument(1);
            writes.add(target);
            writeTimes.add(System.currentTimeMillis());
            Map<String, Object> body = new HashMap<>();
            if (failing.contains(target)) {
                body.put("ok", false);
                body.put("error", "invalid target: " + target);
                return ServerDataWriteService.Result.of(HttpStatus.BAD_REQUEST, body);
            }
            body.put("ok", true);
            return ServerDataWriteService.Result.of(HttpStatus.OK, body);
        });
        runner = new TestPlanRunner(testPlanService, writeService, deviceService);
    }

    @AfterEach
    void tearDown() {
        runner.shutdown();
    }

    @Test
    void runsInOrderAndWaitsEachInterval() {
        // A(0초) B(1초) C(0초): A·B 는 곧바로 함께, C 는 1초 뒤
        TestPlan plan = plan(1, action(LIVE, "3_1", 0, 0), action(LIVE, "3_2", 1, 0), action(LIVE, "3_3", 0, 0));
        long t0 = System.currentTimeMillis();
        runner.start(1, TestPlanTrigger.MANUAL);

        verify(testPlanService, timeout(4000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED), isNull(), eq(0));
        assertEquals(Arrays.asList("3_1", "3_2", "3_3"), writes);
        assertTrue(writeTimes.get(1) - t0 < 500, "A and B run together");
        long gap = writeTimes.get(2) - writeTimes.get(1);
        assertTrue(gap >= 950 && gap < 2000, "C runs about 1 s after B, was " + gap + " ms");
        assertFalse(runner.isRunning(1));
        assertEquals(Arrays.asList(TestPlanActionStatus.OK, TestPlanActionStatus.OK, TestPlanActionStatus.OK), recordedStatuses());
    }

    @Test
    void waitsTheLastIntervalBeforeFinishing() {
        plan(2, action(LIVE, "3_1", 1, 0));
        long t0 = System.currentTimeMillis();
        runner.start(2, TestPlanTrigger.MANUAL);
        assertTrue(runner.isRunning(2));
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED), isNull(), eq(0));
        assertTrue(System.currentTimeMillis() - t0 >= 950, "the last action's interval is waited");
    }

    @Test
    void skippedFailureContinues() {
        failing.add("3_2");
        plan(3, action(LIVE, "3_1", 0, 0), action(LIVE, "3_2", 0, 1), action(LIVE, "3_3", 0, 0));
        runner.start(3, TestPlanTrigger.MANUAL);
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED_WITH_SKIPS), isNull(), eq(1));
        assertEquals(Arrays.asList("3_1", "3_2", "3_3"), writes);
        assertEquals(Arrays.asList(TestPlanActionStatus.OK, TestPlanActionStatus.SKIPPED, TestPlanActionStatus.OK), recordedStatuses());
        TestPlanRunAction skipped = recorded().get(1);
        assertEquals(400, skipped.getHttpStatus());
        assertEquals("invalid target: 3_2", skipped.getError());
    }

    @Test
    void unskippedFailureStopsThePlan() {
        failing.add("3_2");
        plan(4, action(LIVE, "3_1", 0, 0), action(LIVE, "3_2", 1, 0), action(LIVE, "3_3", 0, 0));
        runner.start(4, TestPlanTrigger.MANUAL);
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.FAILED),
                eq("Action #2 failed and is not set to skip."), eq(1));
        assertEquals(Arrays.asList("3_1", "3_2"), writes);   // 3_3 은 실행하지 않는다
        assertEquals(Arrays.asList(TestPlanActionStatus.OK, TestPlanActionStatus.FAILED), recordedStatuses());
    }

    @Test
    void deletedDeviceFailsWithoutWriting() {
        plan(5, action(DELETED, "3_1", 0, 1), action(LIVE, "3_2", 0, 0));
        runner.start(5, TestPlanTrigger.MANUAL);
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED_WITH_SKIPS), isNull(), eq(1));
        assertEquals(Collections.singletonList("3_2"), writes);
        TestPlanRunAction r1 = recorded().get(0);
        assertEquals(TestPlanActionStatus.SKIPPED, r1.getStatus());
        assertNull(r1.getHttpStatus());
        assertEquals("Device id 2 no longer exists.", r1.getError());
    }

    @Test
    void secondStartWhileRunningIsRejected() {
        plan(6, action(LIVE, "3_1", 2, 0));
        runner.start(6, TestPlanTrigger.MANUAL);
        assertThrows(TestPlanRunner.AlreadyRunningException.class, () -> runner.start(6, TestPlanTrigger.SCHEDULE));
        verify(testPlanService, times(1)).startRun(any(), any());
        runner.stop(6);
    }

    @Test
    void stopDuringWaitEndsTheRunAndSkipsTheRest() throws Exception {
        plan(7, action(LIVE, "3_1", 2, 0), action(LIVE, "3_2", 0, 0));
        runner.start(7, TestPlanTrigger.MANUAL);
        Thread.sleep(300);
        assertEquals(1, runner.statuses().size());
        assertEquals(1, runner.statuses().get(0).getNextAction());
        assertTrue(runner.stop(7));
        verify(testPlanService).finishRun(anyLong(), eq(TestPlanRunResult.STOPPED), eq("Stopped by user"), eq(0));
        assertFalse(runner.isRunning(7));
        assertFalse(runner.stop(7));
        Thread.sleep(2300);
        assertEquals(Collections.singletonList("3_1"), writes);   // 3_2 는 실행하지 않는다
    }

    @Test
    void cronExpressions() {
        TestPlan p = new TestPlan();
        p.setScheduleEnabled(TestPlan.SCHEDULE_ENABLED);
        p.setScheduleType(ScheduleType.SECOND);
        p.setScheduleSecond(5);
        assertEquals("5 * * * * *", TestPlanScheduler.cronOf(p));
        assertEquals(LocalDateTime.of(2026, 10, 23, 10, 0, 5),
                TestPlanScheduler.nextRunOf(p, LocalDateTime.of(2026, 10, 23, 10, 0, 0)));

        p.setScheduleType(ScheduleType.MINUTE);
        p.setScheduleMinute(30);
        assertEquals("5 30 * * * *", TestPlanScheduler.cronOf(p));
        assertEquals(LocalDateTime.of(2026, 10, 23, 10, 30, 5),
                TestPlanScheduler.nextRunOf(p, LocalDateTime.of(2026, 10, 23, 10, 0, 0)));

        p.setScheduleType(ScheduleType.HOUR);
        p.setScheduleHour(9);
        assertEquals("5 30 9 * * *", TestPlanScheduler.cronOf(p));
        assertEquals(LocalDateTime.of(2026, 10, 24, 9, 30, 5),
                TestPlanScheduler.nextRunOf(p, LocalDateTime.of(2026, 10, 23, 10, 0, 0)));

        p.setScheduleEnabled(TestPlan.SCHEDULE_DISABLED);
        assertNull(TestPlanScheduler.cronOf(p));
        p.setScheduleEnabled(TestPlan.SCHEDULE_ENABLED);
        p.setScheduleType(ScheduleType.NONE);
        assertNull(TestPlanScheduler.cronOf(p));
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private TestPlan plan(int id, TestPlanAction... actions) {
        TestPlan p = new TestPlan();
        p.setId(id);
        p.setName("plan" + id);
        for (int i = 0; i < actions.length; i++) {
            actions[i].setSeq(i);
            p.getActions().add(actions[i]);
        }
        when(testPlanService.selectTestPlan(id)).thenReturn(Optional.of(p));
        return p;
    }

    private static TestPlanAction action(int deviceId, String target, int interval, int skip) {
        TestPlanAction a = new TestPlanAction();
        a.setDeviceId(deviceId);
        a.setTarget(target);
        a.setValue(1);
        a.setDataType("UI16AB");
        a.setIntervalSeconds(interval);
        a.setSkipOnFailure(skip);
        return a;
    }

    private List<TestPlanRunAction> recorded() {
        ArgumentCaptor<TestPlanRunAction> c = ArgumentCaptor.forClass(TestPlanRunAction.class);
        verify(testPlanService, atLeastOnce()).recordAction(c.capture());
        return c.getAllValues();
    }

    private List<TestPlanActionStatus> recordedStatuses() {
        List<TestPlanActionStatus> out = new ArrayList<>();
        for (TestPlanRunAction a : recorded()) out.add(a.getStatus());
        return out;
    }
}
