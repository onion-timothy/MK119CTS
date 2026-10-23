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
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Test Plan 의 스케줄 실행. 스케줄 시각이 되면 {@link TestPlanRunner} 로 실행을 시작할 뿐이고,
 * Action 실행은 실행기의 풀에서 돈다.
 * <pre>
 *   SECOND  매분 S초          S * * * * *
 *   MINUTE  매시 M분 S초      S M * * * *
 *   HOUR    매일 H시 M분 S초  S M H * * *
 * </pre>
 * 시각은 서버의 시간대를 따른다. 꺼져 있던 동안 놓친 실행은 건너뛴다(E4) — 기동 시 다시 등록할 뿐이다.
 */
@Component
public class TestPlanScheduler {
    private static final Logger logger = LoggerFactory.getLogger("testplan");

    private final TestPlanService testPlanService;
    private final TestPlanRunner runner;
    private final ThreadPoolTaskScheduler scheduler;

    /** planId -> 등록된 스케줄. */
    private final Map<Integer, ScheduledFuture<?>> registered = new ConcurrentHashMap<>();

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

    /** 스케줄의 cron 식. 스케줄이 없거나 꺼져 있거나 칸이 비어 있으면 null. */
    public static String cronOf(TestPlan plan) {
        if (plan == null || plan.getScheduleEnabled() != TestPlan.SCHEDULE_ENABLED) return null;
        ScheduleType type = plan.getScheduleType();
        Integer h = plan.getScheduleHour(), m = plan.getScheduleMinute(), s = plan.getScheduleSecond();
        if (type == ScheduleType.SECOND && s != null) return s + " * * * * *";
        if (type == ScheduleType.MINUTE && s != null && m != null) return s + " " + m + " * * * *";
        if (type == ScheduleType.HOUR && s != null && m != null && h != null) return s + " " + m + " " + h + " * * *";
        return null;
    }

    /** 다음 실행 시각. 스케줄이 없으면 null. */
    public static LocalDateTime nextRunOf(TestPlan plan, LocalDateTime from) {
        String cron = cronOf(plan);
        return cron == null ? null : CronExpression.parse(cron).next(from);
    }

    /** 기동 시 부른다. 켜진 스케줄을 모두 등록한다. */
    public void registerAll() {
        int n = 0;
        for (TestPlan plan : testPlanService.selectTestPlans()) {
            if (register(plan)) n++;
        }
        logger.info("Registered {} test plan schedule(s).", n);
    }

    /**
     * Plan 의 스케줄을 (다시) 등록한다. 저장·스케줄 토글 뒤에 부른다.
     * 이전 등록은 지운다. 스케줄이 없거나 꺼져 있으면 지우기만 한다.
     *
     * @return 등록했으면 true
     */
    public boolean register(TestPlan plan) {
        unregister(plan.getId());
        String cron = cronOf(plan);
        if (cron == null) return false;
        Integer planId = plan.getId();
        ScheduledFuture<?> f = scheduler.schedule(() -> fire(planId), new CronTrigger(cron));
        registered.put(planId, f);
        logger.info("Test plan \"{}\"({}) scheduled: {} (next {})", plan.getName(), planId, cron,
                nextRunOf(plan, LocalDateTime.now()));
        return true;
    }

    public void unregister(Integer planId) {
        ScheduledFuture<?> f = registered.remove(planId);
        if (f != null) f.cancel(false);
    }

    public boolean isRegistered(Integer planId) {
        return registered.containsKey(planId);
    }

    private void fire(Integer planId) {
        if (runner.isRunning(planId)) {
            // 등록 시 interval 합이 주기보다 짧은지 보지만(S3), 쓰기가 늦어지면 겹칠 수 있다. 실패로 세지 않는다(E2)
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
