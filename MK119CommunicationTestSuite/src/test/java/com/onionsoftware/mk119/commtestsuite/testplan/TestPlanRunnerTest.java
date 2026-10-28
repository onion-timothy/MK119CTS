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
    void runsEachActionAtItsRunTime() {
        // A(0초) B(0초) C(1초): A·B 는 곧바로 함께, C 는 1초 뒤
        TestPlan plan = plan(1, action(LIVE, "3_1", 0, 1), action(LIVE, "3_2", 0, 1), action(LIVE, "3_3", 1, 1));
        long t0 = System.currentTimeMillis();
        runner.start(1, TestPlanTrigger.MANUAL);

        verify(testPlanService, timeout(4000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED), isNull(), eq(0));
        assertEquals(Arrays.asList("3_1", "3_2", "3_3"), writes);
        assertTrue(writeTimes.get(1) - t0 < 500, "A and B run together");
        long c = writeTimes.get(2) - t0;
        assertTrue(c >= 950 && c < 2000, "C runs about 1 s after the start, was " + c + " ms");
        assertFalse(runner.isRunning(1));
        assertEquals(Arrays.asList(TestPlanActionStatus.OK, TestPlanActionStatus.OK, TestPlanActionStatus.OK), recordedStatuses());
    }

    @Test
    void runsInRunTimeOrderWhateverTheListOrder() {
        // 목록에는 2초, 0초, 1초 순서로 있다
        plan(8, action(LIVE, "3_3", 2, 1), action(LIVE, "3_1", 0, 1), action(LIVE, "3_2", 1, 1));
        runner.start(8, TestPlanTrigger.MANUAL);
        verify(testPlanService, timeout(5000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED), isNull(), eq(0));
        assertEquals(Arrays.asList("3_1", "3_2", "3_3"), writes);
    }

    @Test
    void finishesRightAfterTheLastRunTime() {
        plan(2, action(LIVE, "3_1", 0, 1), action(LIVE, "3_2", 1, 1));
        long t0 = System.currentTimeMillis();
        runner.start(2, TestPlanTrigger.MANUAL);
        assertTrue(runner.isRunning(2));
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED), isNull(), eq(0));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= 950 && took < 1600, "ends with the action at 1 s, took " + took + " ms");
    }

    @Test
    void waitsForTheLastLockBeforeFinishing() throws Exception {
        TestPlanAction locked = action(LIVE, "3_1", 0, 1);
        locked.setLockSeconds(1);
        plan(10, locked);
        long t0 = System.currentTimeMillis();
        runner.start(10, TestPlanTrigger.MANUAL);
        Thread.sleep(300);
        assertTrue(runner.isRunning(10), "still running while the lock holds");
        assertEquals(1, runner.statuses().get(0).getNextAction());   // Action 은 다 썼다
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED), isNull(), eq(0));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= 950 && took < 1600, "ends when the 1 s lock is released, took " + took + " ms");
    }

    @Test
    void failureContinuesByDefault() {
        failing.add("3_2");
        plan(3, action(LIVE, "3_1", 0, 1), action(LIVE, "3_2", 0, 0), action(LIVE, "3_3", 0, 1));
        runner.start(3, TestPlanTrigger.MANUAL);
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.COMPLETED_WITH_SKIPS), isNull(), eq(1));
        assertEquals(Arrays.asList("3_1", "3_2", "3_3"), writes);
        assertEquals(Arrays.asList(TestPlanActionStatus.OK, TestPlanActionStatus.SKIPPED, TestPlanActionStatus.OK), recordedStatuses());
        TestPlanRunAction skipped = recorded().get(1);
        assertEquals(400, skipped.getHttpStatus());
        assertEquals("invalid target: 3_2", skipped.getError());
    }

    @Test
    void stopOnFailureStopsThePlan() {
        failing.add("3_2");
        plan(4, action(LIVE, "3_1", 0, 1), action(LIVE, "3_2", 1, 1), action(LIVE, "3_3", 2, 1));
        runner.start(4, TestPlanTrigger.MANUAL);
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.FAILED),
                eq("Action #2 failed and is set to stop the plan."), eq(1));
        assertEquals(Arrays.asList("3_1", "3_2"), writes);   // 3_3 은 실행하지 않는다
        assertEquals(Arrays.asList(TestPlanActionStatus.OK, TestPlanActionStatus.FAILED), recordedStatuses());
    }

    @Test
    void stopOnFailureStopsRightThereEvenAtTheSameTime() {
        // 같은 시각이라도 실패한 Action 뒤의 것은 쓰지 않는다
        failing.add("3_2");
        plan(9, action(LIVE, "3_1", 0, 1), action(LIVE, "3_2", 0, 1), action(LIVE, "3_3", 0, 1));
        runner.start(9, TestPlanTrigger.MANUAL);
        verify(testPlanService, timeout(3000)).finishRun(anyLong(), eq(TestPlanRunResult.FAILED),
                eq("Action #2 failed and is set to stop the plan."), eq(1));
        assertEquals(Arrays.asList("3_1", "3_2"), writes);
    }

    @Test
    void deletedDeviceFailsWithoutWriting() {
        plan(5, action(DELETED, "3_1", 0, 0), action(LIVE, "3_2", 0, 1));
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
        plan(6, action(LIVE, "3_1", 0, 1), action(LIVE, "3_2", 2, 1));
        runner.start(6, TestPlanTrigger.MANUAL);
        assertThrows(TestPlanRunner.AlreadyRunningException.class, () -> runner.start(6, TestPlanTrigger.SCHEDULE));
        verify(testPlanService, times(1)).startRun(any(), any());
        runner.stop(6);
    }

    @Test
    void stopDuringWaitEndsTheRunAndSkipsTheRest() throws Exception {
        plan(7, action(LIVE, "3_1", 0, 1), action(LIVE, "3_2", 2, 1));
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

    /** 절전에서 깨어나 뒤늦게 불린 실행은 놓친 것으로 본다. plan#1 은 매시 24분인데 10:08:04 에 돌았다. */
    @Test
    void aScheduleFiredFarOffItsTimeIsTreatedAsMissed() {
        String hourly = "0 24 * * * *";
        assertTrue(TestPlanScheduler.isOnTime(hourly, LocalDateTime.of(2026, 10, 28, 10, 24, 0)));
        assertTrue(TestPlanScheduler.isOnTime(hourly, LocalDateTime.of(2026, 10, 28, 10, 24, 3)));       // 조금 늦음
        assertTrue(TestPlanScheduler.isOnTime(hourly, LocalDateTime.of(2026, 10, 28, 10, 23, 59, 990_000_000))); // 조금 이름
        assertFalse(TestPlanScheduler.isOnTime(hourly, LocalDateTime.of(2026, 10, 28, 10, 8, 4)));
        assertFalse(TestPlanScheduler.isOnTime(hourly, LocalDateTime.of(2026, 10, 28, 10, 24, 30)));
        String everyMinute = "5 * * * * *";
        assertTrue(TestPlanScheduler.isOnTime(everyMinute, LocalDateTime.of(2026, 10, 28, 10, 0, 5)));
        assertFalse(TestPlanScheduler.isOnTime(everyMinute, LocalDateTime.of(2026, 10, 28, 10, 0, 35)));
    }

    /** interval 은 켜는 순간 돌고 간격마다 돈다. 기동 때는 한 주기 뒤에 시작한다. 같은 스케줄로 다시 저장하면 박자를 지킨다. */
    @Test
    void intervalScheduleRunsAtOnceThenEveryInterval() throws Exception {
        TestPlanService service = mock(TestPlanService.class);
        TestPlanRunner runner = mock(TestPlanRunner.class);
        TestPlanScheduler scheduler = new TestPlanScheduler(service, runner);
        try {
            TestPlan p = new TestPlan();
            p.setId(7);
            p.setName("interval");
            p.setScheduleType(ScheduleType.INTERVAL);
            p.setScheduleIntervalSeconds(1);
            p.setScheduleEnabled(TestPlan.SCHEDULE_ENABLED);

            // 켜면 곧바로 한 번, 1초 뒤 또 한 번
            assertTrue(scheduler.register(p));
            verify(runner, timeout(300).times(1)).start(7, TestPlanTrigger.SCHEDULE);
            verify(runner, timeout(1500).times(2)).start(7, TestPlanTrigger.SCHEDULE);
            assertNotNull(scheduler.nextRun(p, LocalDateTime.now()));

            // 이름만 고친 저장: 다시 등록하지 않으므로 곧바로 돌지 않는다
            clearInvocations(runner);
            p.setName("renamed");
            scheduler.register(p);
            Thread.sleep(300);
            verify(runner, atMost(1)).start(7, TestPlanTrigger.SCHEDULE);   // 박자에 걸린 한 번까지만

            // 끄면 멈춘다
            p.setScheduleEnabled(TestPlan.SCHEDULE_DISABLED);
            assertFalse(scheduler.register(p));
            assertNull(scheduler.nextRun(p, LocalDateTime.now()));
            clearInvocations(runner);
            Thread.sleep(1300);
            verify(runner, never()).start(anyInt(), any());

            // 기동: 한 주기 뒤에 시작한다
            p.setScheduleEnabled(TestPlan.SCHEDULE_ENABLED);
            when(service.selectTestPlans()).thenReturn(Collections.singletonList(p));
            scheduler.registerAll();
            Thread.sleep(500);
            verify(runner, never()).start(anyInt(), any());
            verify(runner, timeout(1000).times(1)).start(7, TestPlanTrigger.SCHEDULE);
        } finally {
            scheduler.shutdown();
        }
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

    private static TestPlanAction action(int deviceId, String target, int at, int stop) {
        TestPlanAction a = new TestPlanAction();
        a.setDeviceId(deviceId);
        a.setTarget(target);
        a.setValue(1);
        a.setDataType("UI16AB");
        a.setAtSeconds(at);
        a.setStopOnFailure(stop);
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
