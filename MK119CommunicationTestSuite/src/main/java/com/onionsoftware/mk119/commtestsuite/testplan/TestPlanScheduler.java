package com.onionsoftware.mk119.commtestsuite.testplan;

import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import com.onionsoftware.mk119.commtestsuite.db.service.TestPlanService;
import com.onionsoftware.mk119.commtestsuite.enums.ScheduleType;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test Plan 의 스케줄 실행. 스케줄 시각이 되면 {@link TestPlanRunner} 로 실행을 시작할 뿐이고,
 * Action 실행은 실행기의 풀에서 돈다. 스케줄은 cron 과 interval 두 갈래다.
 * <pre>
 *   SECOND    매분 S초          S * * * * *
 *   MINUTE    매시 M분 S초      S M * * * *
 *   HOUR      매일 H시 M분 S초  S M H * * *
 *   INTERVAL  N초마다           켜는 순간 한 번, 그 뒤 N초마다
 * </pre>
 * cron 시각은 서버의 시간대를 따른다. 꺼져 있던 동안 놓친 실행은 건너뛴다(E4) — 기동 시 다시 등록할 뿐이다.
 * <p>
 * 절전으로 놓친 cron 실행도 건너뛴다. 스케줄러는 다음 시각을 "N초 뒤" 로 걸고 그 N초를 단조 시계로 재는데,
 * 이 시계는 절전 중에 멈춘다(macOS). 그래서 놓친 실행 하나가 깨어난 뒤 엉뚱한 시각에 돈다.
 * 실제 시각이 cron 시각에서 {@link #ON_TIME_TOLERANCE_SECONDS} 넘게 벗어났으면 놓친 것으로 본다.
 * interval 은 정해진 시각이 없으므로 보지 않는다 — 절전한 만큼 늦어질 뿐이다.
 * <p>
 * interval 은 켤 때(생성·수정으로 간격이 바뀔 때 포함) 곧바로 돈다. 기동 때는 한 주기 뒤에 시작한다 —
 * 등록이 장비 기동보다 먼저라 곧바로 돌면 쓰기가 실패한다. 이름만 고친 저장처럼 스케줄이 그대로면
 * 등록을 건드리지 않아 박자가 유지된다.
 */
@Component
public class TestPlanScheduler {
    private static final Logger logger = LoggerFactory.getLogger("testplan");
    static final long ON_TIME_TOLERANCE_SECONDS = 5;

    private final TestPlanService testPlanService;
    private final TestPlanRunner runner;
    private final ThreadPoolTaskScheduler scheduler;

    /** 등록 하나. key 는 스케줄을 나타내는 문자열이다 — 같으면 다시 등록하지 않는다. */
    private static final class Registration {
        final String key;
        final ScheduledFuture<?> future;

        Registration(String key, ScheduledFuture<?> future) {
            this.key = key;
            this.future = future;
        }
    }

    /** planId -> 등록된 스케줄. */
    private final Map<Integer, Registration> registered = new ConcurrentHashMap<>();

    public TestPlanScheduler(TestPlanService testPlanService, TestPlanRunner runner) {
        this.testPlanService = testPlanService;
        this.runner = runner;
        // 시각에 맞춰 실행을 시작만 하므로 스레드 하나면 된다. 실행기와 같은 이유로 빈으로 두지 않는다
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(1);
        this.scheduler.setThreadNamePrefix("testplan-cron-");
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.initialize();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    /** 스케줄의 cron 식. cron 스케줄이 아니거나 꺼져 있거나 칸이 비어 있으면 null. */
    public static String cronOf(TestPlan plan) {
        if (plan == null || plan.getScheduleEnabled() != TestPlan.SCHEDULE_ENABLED) return null;
        ScheduleType type = plan.getScheduleType();
        Integer h = plan.getScheduleHour(), m = plan.getScheduleMinute(), s = plan.getScheduleSecond();
        if (type == ScheduleType.SECOND && s != null) return s + " * * * * *";
        if (type == ScheduleType.MINUTE && s != null && m != null) return s + " " + m + " * * * *";
        if (type == ScheduleType.HOUR && s != null && m != null && h != null) return s + " " + m + " " + h + " * * *";
        return null;
    }

    /** interval 스케줄의 간격(초). interval 이 아니거나 꺼져 있거나 비어 있으면 null. */
    static Integer intervalOf(TestPlan plan) {
        if (plan == null || plan.getScheduleEnabled() != TestPlan.SCHEDULE_ENABLED) return null;
        if (plan.getScheduleType() != ScheduleType.INTERVAL) return null;
        Integer n = plan.getScheduleIntervalSeconds();
        return n == null || n <= 0 ? null : n;
    }

    /** 등록을 나타내는 문자열. 스케줄이 없으면 null. */
    private static String keyOf(TestPlan plan) {
        String cron = cronOf(plan);
        if (cron != null) return "cron:" + cron;
        Integer n = intervalOf(plan);
        return n == null ? null : "interval:" + n;
    }

    /** cron 의 다음 실행 시각. cron 스케줄이 없으면 null. */
    public static LocalDateTime nextRunOf(TestPlan plan, LocalDateTime from) {
        String cron = cronOf(plan);
        return cron == null ? null : CronExpression.parse(cron).next(from);
    }

    /** 다음 실행 시각. interval 은 등록된 예약에서 읽는다. 스케줄이 없으면 null. */
    public LocalDateTime nextRun(TestPlan plan, LocalDateTime now) {
        if (intervalOf(plan) == null) return nextRunOf(plan, now);
        Registration r = registered.get(plan.getId());
        if (r == null || !r.key.equals(keyOf(plan))) return null;
        long ms = Math.max(0, r.future.getDelay(TimeUnit.MILLISECONDS));
        return now.plus(ms, ChronoUnit.MILLIS).truncatedTo(ChronoUnit.SECONDS);
    }

    /** cron 시각에 맞춰 불렸는가. 앞뒤 {@link #ON_TIME_TOLERANCE_SECONDS} 까지 본다(시계 보정으로 조금 이를 수도 있다). */
    static boolean isOnTime(String cron, LocalDateTime now) {
        LocalDateTime due = CronExpression.parse(cron).next(now.minusSeconds(ON_TIME_TOLERANCE_SECONDS));
        return due != null && Math.abs(Duration.between(now, due).getSeconds()) <= ON_TIME_TOLERANCE_SECONDS;
    }

    /** 기동 시 부른다. 켜진 스케줄을 모두 등록한다. interval 은 한 주기 뒤에 시작한다. */
    public void registerAll() {
        int n = 0;
        for (TestPlan plan : testPlanService.selectTestPlans()) {
            if (register(plan, false)) n++;
        }
        logger.info("Registered {} test plan schedule(s).", n);
    }

    /**
     * Plan 의 스케줄을 (다시) 등록한다. 저장·스케줄 토글 뒤에 부른다.
     * 스케줄이 그대로면 두고, 바뀌었으면 이전 등록을 지운 뒤 등록한다. interval 은 곧바로 한 번 돈다.
     * 스케줄이 없거나 꺼져 있으면 지우기만 한다.
     *
     * @return 등록되어 있으면 true
     */
    public boolean register(TestPlan plan) {
        return register(plan, true);
    }

    private boolean register(TestPlan plan, boolean startNow) {
        Integer planId = plan.getId();
        String key = keyOf(plan);
        Registration old = registered.get(planId);
        if (old != null && old.key.equals(key)) return true;
        unregister(planId);
        if (key == null) return false;

        String cron = cronOf(plan);
        ScheduledFuture<?> f;
        if (cron != null) {
            f = scheduler.schedule(() -> fire(planId, cron), new CronTrigger(cron));
        } else {
            long periodMs = intervalOf(plan) * 1000L;
            Date first = new Date(System.currentTimeMillis() + (startNow ? 0 : periodMs));
            f = scheduler.scheduleAtFixedRate(() -> fire(planId, null), first, periodMs);
        }
        registered.put(planId, new Registration(key, f));
        logger.info("Test plan \"{}\"({}) scheduled: {} (next {})", plan.getName(), planId, key,
                nextRun(plan, LocalDateTime.now()));
        return true;
    }

    public void unregister(Integer planId) {
        Registration r = registered.remove(planId);
        if (r != null) r.future.cancel(false);
    }

    public boolean isRegistered(Integer planId) {
        return registered.containsKey(planId);
    }

    /** @param cron cron 스케줄이면 그 식, interval 이면 null */
    private void fire(Integer planId, String cron) {
        if (cron != null) {
            LocalDateTime now = LocalDateTime.now();
            if (!isOnTime(cron, now)) {
                logger.info("Test plan {} schedule skipped: fired at {}, off its time ({}). The machine was probably asleep.",
                        planId, now, cron);
                return;
            }
        }
        if (runner.isRunning(planId)) {
            // 등록 시 실행 시간이 주기보다 짧은지 보지만(S3), 쓰기가 늦어지면 겹칠 수 있다. 실패로 세지 않는다(E2)
            logger.info("Test plan {} schedule skipped: the previous run is still running.", planId);
            return;
        }
        try {
            runner.start(planId, TestPlanTrigger.SCHEDULE);
        } catch (TestPlanRunner.AlreadyRunningException e) {
            logger.info("Test plan {} schedule skipped: the previous run is still running.", planId);
        } catch (RuntimeException e) {
            logger.error("Test plan {} scheduled run failed to start.", planId, e);
        }
    }
}
