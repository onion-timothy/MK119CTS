package com.onionsoftware.mk119.commtestsuite.testplan;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanAction;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRun;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRunAction;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.TestPlanService;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanActionStatus;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanRunResult;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanTrigger;
import com.onionsoftware.mk119.commtestsuite.web.serverdata.ServerDataWriteService;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Test Plan 실행기.
 * <p>
 * 각 Action 은 실행 시각(atSeconds, 시작부터 센 초)에 실행한다. 같은 시각의 Action 들은 한 차례에 seq 순서로 쓴다 —
 * 여러 target 을 동시에 바꾸는 용도다. 마지막 Action 을 쓴 뒤 마지막 lock 이 풀릴 때까지 기다렸다가 끝난다
 * (S3 의 실행 시간과 같게, {@link TestPlanTiming}).
 * <p>
 * 기다리는 동안 스레드를 붙잡지 않는다. 한 차례를 마치면 다음 시각을 "시작 시각 + at" 에 예약한다.
 * 앞 차례의 쓰기가 늦어져도 뒤 시각이 밀리지 않고, 몇 시간을 기다려도 풀을 막지 않는다.
 * <p>
 * Simulation 과 같은 스케줄러를 쓰지 않는다. 그 풀이 붐비면 시뮬레이션 주기가 흔들린다.
 * 같은 타입의 Spring 빈을 하나 더 두면 기존 주입이 모호해지므로 빈으로 등록하지 않고 여기서 만든다.
 * <p>
 * 실행 시점 규칙은 docs/TEST_PLAN_VALIDATION.md 의 R·E 항목이다.
 */
@Component
public class TestPlanRunner {
    private static final Logger logger = LoggerFactory.getLogger("testplan");

    private final TestPlanService testPlanService;
    private final ServerDataWriteService serverDataWriteService;
    private final DeviceService deviceService;
    private final ThreadPoolTaskScheduler scheduler;

    /** planId -> 실행 중인 실행. Plan 하나는 한 번에 하나만 돈다(E1, E2). */
    private final Map<Integer, Execution> running = new ConcurrentHashMap<>();

    public TestPlanRunner(TestPlanService testPlanService,
                          ServerDataWriteService serverDataWriteService,
                          DeviceService deviceService) {
        this.testPlanService = testPlanService;
        this.serverDataWriteService = serverDataWriteService;
        this.deviceService = deviceService;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(4);
        this.scheduler.setThreadNamePrefix("testplan-");
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.initialize();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    /** 실행 중이라 시작할 수 없을 때. 수동 실행은 409 로 알린다(E1). */
    public static class AlreadyRunningException extends RuntimeException {
        AlreadyRunningException(Integer planId) {
            super("Test plan " + planId + " is already running.");
        }
    }

    /** 실행 상태 조회용. */
    @Getter
    public static final class Status {
        private final Integer planId;
        private final Long runId;
        private final TestPlanTrigger trigger;
        private final LocalDateTime startedAt;
        /** 다음에 실행할 Action 위치(0부터). Action 을 다 쓰고 lock 이 풀리길 기다리는 중이면 Action 수와 같다. */
        private final int nextAction;
        private final int totalActions;
        /** 다음 차례(또는 끝)가 예약된 시각(epoch ms). */
        private final long nextAt;

        Status(Execution e) {
            this.planId = e.plan.getId();
            this.runId = e.run.getId();
            this.trigger = e.run.getTriggerType();
            this.startedAt = e.run.getStartedAt();
            this.nextAction = e.next;
            this.totalActions = e.actions.size();
            this.nextAt = e.nextAt;
        }
    }

    /** 실행 하나의 상태. 모든 변경은 this 를 잠그고 한다 — Stop 이 진행 중인 쓰기를 기다리게 하려고(E3). */
    private static final class Execution {
        final TestPlan plan;
        final List<TestPlanAction> actions;
        final TestPlanRun run;
        int next;
        int failed;
        /** 시작 시각(epoch ms). 각 차례는 이 시각 + at 에 예약한다. */
        long startMs;
        /** 실행 시간(초). 마지막 lock 이 풀리는 시각이다. 이때 끝난다. */
        long endSeconds;
        long nextAt;
        boolean finished;
        ScheduledFuture<?> future;

        Execution(TestPlan plan, TestPlanRun run) {
            this.plan = plan;
            // 실행 중에는 Plan 을 고칠 수 없지만(P4), 실행이 붙잡은 목록은 저장 시점의 것으로 고정해 둔다.
            // 저장할 때 시각 순으로 seq 를 매기지만 한 번 더 시각 순(같으면 seq 순)으로 늘어놓는다
            List<TestPlanAction> sorted = new ArrayList<>(plan.getActions());
            sorted.sort(Comparator.comparingInt(TestPlanAction::getAtSeconds).thenComparingInt(TestPlanAction::getSeq));
            this.actions = Collections.unmodifiableList(sorted);
            this.run = run;
        }
    }

    // ── 시작 / 중지 ──────────────────────────────────────────────────────

    /**
     * 실행을 시작한다. 첫 시각(0초)의 Action 들은 곧바로 예약된다.
     *
     * @throws AlreadyRunningException 그 Plan 이 이미 실행 중일 때
     * @throws javax.persistence.EntityNotFoundException Plan 이 없을 때
     */
    public TestPlanRun start(Integer planId, TestPlanTrigger trigger) {
        TestPlan plan = testPlanService.selectTestPlan(planId)
                .orElseThrow(javax.persistence.EntityNotFoundException::new);
        // 자리를 먼저 잡는다. 두 요청이 동시에 와도 하나만 들어간다
        Execution placeholder = new Execution(plan, new TestPlanRun());
        if (running.putIfAbsent(planId, placeholder) != null) {
            throw new AlreadyRunningException(planId);
        }
        Execution e;
        try {
            TestPlanRun run = testPlanService.startRun(plan, trigger);
            e = new Execution(plan, run);
            running.put(planId, e);
        } catch (RuntimeException ex) {
            running.remove(planId, placeholder);
            throw ex;
        }
        logger.info("Test plan \"{}\"({}) started by {}: run={}, actions={}",
                plan.getName(), planId, trigger, e.run.getId(), e.actions.size());
        synchronized (e) {
            e.endSeconds = TestPlanTiming.durationSeconds(e.actions, id -> {
                Device d = deviceService.getDevice(id);
                return d == null ? null : d.getProtocol();
            });
            e.startMs = System.currentTimeMillis();
            scheduleAt(e, e.actions.get(0).getAtSeconds());
        }
        return e.run;
    }

    /**
     * 실행을 멈춘다. 진행 중인 쓰기 하나는 끝내고, 대기 중이면 곧바로 끝낸다.
     * 이미 건 lock 은 건드리지 않는다(E3).
     *
     * @return 실행 중이었으면 true
     */
    public boolean stop(Integer planId) {
        Execution e = running.get(planId);
        if (e == null || e.run.getId() == null) return false;
        synchronized (e) {
            if (e.finished) return false;
            if (e.future != null) e.future.cancel(false);
            finish(e, TestPlanRunResult.STOPPED, "Stopped by user");
        }
        return true;
    }

    public boolean isRunning(Integer planId) {
        return planId != null && running.containsKey(planId);
    }

    /** 실행 중인 것 전부. */
    public List<Status> statuses() {
        List<Status> out = new ArrayList<>();
        for (Execution e : running.values()) {
            if (e.run.getId() == null) continue;   // 자리만 잡은 상태
            synchronized (e) {
                if (!e.finished) out.add(new Status(e));
            }
        }
        return out;
    }

    // ── 실행 ─────────────────────────────────────────────────────────────

    /** 시작 시각 + at 에 다음 차례를 예약한다. 이미 지났으면 곧바로 돈다. */
    private void scheduleAt(Execution e, long atSeconds) {
        e.nextAt = e.startMs + atSeconds * 1000L;
        e.future = scheduler.schedule(() -> step(e), new Date(e.nextAt));
    }

    /**
     * 예약된 차례. 이 시각의 Action 들을 seq 순서로 쓴다. 남은 Action 이 있으면 다음 시각을 예약하고,
     * 없으면 마지막 lock 이 풀리는 시각을 예약한다. 그 차례에 다시 들어오면 끝낸다.
     * stopOnFailure 인 Action 이 실패하면 그 자리에서 멈춘다 — 같은 시각이라도 뒤의 Action 은 쓰지 않는다.
     * 그 밖의 실패는 SKIPPED 로 기록하고 이어 간다.
     */
    private void step(Execution e) {
        synchronized (e) {
            if (e.finished) return;
            try {
                if (e.next >= e.actions.size()) {          // lock 이 다 풀렸다
                    finish(e, e.failed > 0 ? TestPlanRunResult.COMPLETED_WITH_SKIPS : TestPlanRunResult.COMPLETED, null);
                    return;
                }
                int at = e.actions.get(e.next).getAtSeconds();
                while (e.next < e.actions.size() && e.actions.get(e.next).getAtSeconds() == at) {
                    TestPlanAction action = e.actions.get(e.next);
                    boolean ok = execute(e, action);
                    e.next++;
                    if (!ok && action.getStopOnFailure() == 1) {
                        finish(e, TestPlanRunResult.FAILED,
                                "Action #" + (action.getSeq() + 1) + " failed and is set to stop the plan.");
                        return;
                    }
                }
                if (e.next < e.actions.size()) {
                    scheduleAt(e, e.actions.get(e.next).getAtSeconds());
                    return;
                }
                if (e.endSeconds > at) {                    // 마지막 lock 이 풀릴 때까지 기다린다
                    scheduleAt(e, e.endSeconds);
                    return;
                }
                finish(e, e.failed > 0 ? TestPlanRunResult.COMPLETED_WITH_SKIPS : TestPlanRunResult.COMPLETED, null);
            } catch (RuntimeException ex) {
                logger.error("Test plan run {} aborted by an unexpected error.", e.run.getId(), ex);
                finish(e, TestPlanRunResult.FAILED, "Unexpected error: " + ex.getMessage());
            }
        }
    }

    /** Action 하나를 쓰고 결과를 남긴다. @return 성공이면 true */
    private boolean execute(Execution e, TestPlanAction action) {
        TestPlanRunAction rec = new TestPlanRunAction();
        rec.setRun(e.run);
        rec.setSeq(action.getSeq());
        rec.setDeviceId(action.getDeviceId());
        rec.setTarget(action.getTarget());
        rec.setValue(action.getValue());
        rec.setDataType(action.getDataType());
        rec.setLockSeconds(action.getLockSeconds());
        rec.setExecutedAt(LocalDateTime.now());

        String error = null;
        Integer httpStatus = null;
        if (action.getDeviceId() == null || deviceService.getDevice(action.getDeviceId()) == null) {
            error = "Device " + (action.getDeviceId() == null ? "" : "id " + action.getDeviceId() + " ")
                    + "no longer exists.";                                                          // R1
        } else if (action.getLockSeconds() < 0) {
            error = "A lock without expiry is only for the Server Data editor.";   // 등록 검증(A11)이 막지만 한 번 더
        } else {
            ServerDataWriteService.Result r = serverDataWriteService.write(action.getDeviceId(),
                    action.getTarget(), action.getValue(), action.getDataType(), action.getLockSeconds());
            httpStatus = r.getStatus().value();
            if (!r.isOk()) error = r.getError();                                      // R2~R6
        }

        boolean ok = error == null;
        rec.setHttpStatus(httpStatus);
        rec.setError(error == null ? null : truncate(error, 1024));
        if (ok) {
            rec.setStatus(TestPlanActionStatus.OK);
        } else {
            e.failed++;
            rec.setStatus(action.getStopOnFailure() == 1 ? TestPlanActionStatus.FAILED : TestPlanActionStatus.SKIPPED);
            logger.info("Test plan run {} action #{} ({} on device {}) {}: {}", e.run.getId(), action.getSeq() + 1,
                    action.getTarget(), action.getDeviceId(), rec.getStatus(), error);
        }
        testPlanService.recordAction(rec);
        return ok;
    }

    private void finish(Execution e, TestPlanRunResult result, String message) {
        e.finished = true;
        running.remove(e.plan.getId(), e);
        try {
            testPlanService.finishRun(e.run.getId(), result, message, e.failed);
        } catch (RuntimeException ex) {
            logger.error("Failed to record the end of test plan run {}.", e.run.getId(), ex);
        }
        logger.info("Test plan \"{}\"({}) run {} ended: {}{}", e.plan.getName(), e.plan.getId(), e.run.getId(),
                result, message == null ? "" : " — " + message);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
