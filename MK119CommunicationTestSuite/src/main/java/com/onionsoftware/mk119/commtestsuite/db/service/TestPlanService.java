package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanAction;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRun;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRunAction;
import com.onionsoftware.mk119.commtestsuite.db.repository.TestPlanRepository;
import com.onionsoftware.mk119.commtestsuite.db.repository.TestPlanRunActionRepository;
import com.onionsoftware.mk119.commtestsuite.db.repository.TestPlanRunRepository;
import com.onionsoftware.mk119.commtestsuite.db.schema.SchemaInspector;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanRunResult;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanTrigger;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Test Plan 과 실행 이력의 저장.
 * <p>
 * 검증(docs/TEST_PLAN_VALIDATION.md)은 여기서 하지 않는다. 화면·엑셀·REST 가 모두
 * TestPlanValidator 를 거친 뒤 이 서비스를 부른다.
 */
@Service
@RequiredArgsConstructor
public class TestPlanService {
    /** Plan 마다 남기는 실행 이력 수. 초 지정 스케줄은 하루 1440회를 돌므로 상한이 필요하다. */
    public static final int KEEP_RUNS = 200;

    private final TestPlanRepository testPlanRepository;
    private final TestPlanRunRepository testPlanRunRepository;
    private final TestPlanRunActionRepository testPlanRunActionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SchemaInspector schemaInspector;

    // ── Plan ─────────────────────────────────────────────────────────────

    public List<TestPlan> selectTestPlans() {
        return testPlanRepository.findAll(Sort.by("id"));
    }

    public Optional<TestPlan> selectTestPlan(Integer id) {
        return id == null ? Optional.empty() : testPlanRepository.findById(id);
    }

    public boolean existsByName(String name, Integer exceptId) {
        return exceptId == null
                ? testPlanRepository.existsByName(name)
                : testPlanRepository.existsByNameAndIdNot(name, exceptId);
    }

    @Transactional
    public TestPlan insertTestPlan(TestPlan plan) {
        LocalDateTime now = LocalDateTime.now();
        plan.setId(null);
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        List<TestPlanAction> given = plan.getActions();
        plan.setActions(new ArrayList<>());
        attachActions(plan, given);
        return testPlanRepository.save(plan);
    }

    /** 엑셀 import(X7). 전부 한 트랜잭션이라 하나라도 실패하면 아무것도 남지 않는다. */
    @Transactional
    public List<TestPlan> insertTestPlans(List<TestPlan> plans) {
        List<TestPlan> saved = new ArrayList<>();
        for (TestPlan p : plans) saved.add(insertTestPlan(p));
        return saved;
    }

    /**
     * 이름·스케줄을 바꾸고 Action 목록을 통째로 갈아 끼운다.
     * 실행 중인 Plan 을 막는 것(P4)은 실행기를 아는 쪽이 이 메서드를 부르기 전에 한다.
     */
    @Transactional
    public TestPlan updateTestPlan(TestPlan incoming) {
        TestPlan cur = testPlanRepository.findById(incoming.getId())
                .orElseThrow(EntityNotFoundException::new);
        cur.setName(incoming.getName());
        cur.setScheduleType(incoming.getScheduleType());
        cur.setScheduleHour(incoming.getScheduleHour());
        cur.setScheduleMinute(incoming.getScheduleMinute());
        cur.setScheduleSecond(incoming.getScheduleSecond());
        cur.setScheduleIntervalSeconds(incoming.getScheduleIntervalSeconds());
        cur.setScheduleEnabled(incoming.getScheduleEnabled());
        cur.setUpdatedAt(LocalDateTime.now());
        cur.getActions().clear();
        attachActions(cur, incoming.getActions());
        return cur;
    }

    @Transactional
    public TestPlan updateScheduleEnabled(Integer id, int enabled) {
        TestPlan cur = testPlanRepository.findById(id).orElseThrow(EntityNotFoundException::new);
        cur.setScheduleEnabled(enabled);
        cur.setUpdatedAt(LocalDateTime.now());
        return cur;
    }

    /** Action 과 실행 이력은 DB 의 ON DELETE CASCADE 가 함께 지운다. */
    @Transactional
    public void deleteTestPlan(Integer id) {
        testPlanRepository.deleteById(id);
    }

    /** removeDeviceFromPlans 의 결과. 스케줄을 내리려면 id 가, 사용자에게 알리려면 이름이 필요하다. */
    public static class PlanCleanup {
        /** Action 이 하나도 남지 않아 통째로 지운 Plan 의 id. */
        public final List<Integer> deleted = new ArrayList<>();
        /** 지운 Plan 의 이름. 화면에 "이 Plan 이 사라졌다" 고 알리는 데 쓴다. */
        public final List<String> deletedNames = new ArrayList<>();
        /** Action 일부만 떼어내고 남긴 Plan 의 id. */
        public final List<Integer> updated = new ArrayList<>();
    }

    /**
     * 디바이스가 사라질 때 그 디바이스를 쓰는 Action 을 Plan 에서 떼어낸다.
     * <p>
     * 그냥 두면 Plan 이 되살릴 수 없는 상태가 된다 — 검증 A1 이 "Action 의 디바이스는 존재해야
     * 한다" 이므로, 없는 디바이스를 가리키는 Action 이 하나라도 있으면 그 Plan 은 다시 저장할 수
     * 없다. 실행도 매번 그 Action 에서 실패한다.
     * <p>
     * 남은 Action 이 없으면 Plan 자체를 지운다(Action 0 개인 Plan 도 검증을 통과하지 못한다).
     * 남으면 seq 를 다시 매기고, 가장 이른 Action 이 0 초가 되도록 전체 시각을 같은 만큼
     * 앞당긴다(검증 A16). 편집기에서 0 초 Action 을 지울 때와 같은 처리다.
     */
    @Transactional
    public PlanCleanup removeDeviceFromPlans(Integer deviceId) {
        PlanCleanup out = new PlanCleanup();
        if (deviceId == null) return out;

        for (Integer planId : testPlanRepository.findPlanIdsByActionDeviceId(deviceId)) {
            TestPlan plan = testPlanRepository.findById(planId).orElse(null);
            if (plan == null) continue;

            plan.getActions().removeIf(a -> deviceId.equals(a.getDeviceId()));

            if (plan.getActions().isEmpty()) {
                out.deleted.add(planId);
                out.deletedNames.add(plan.getName());
                testPlanRepository.delete(plan);
                continue;
            }

            List<TestPlanAction> left = plan.getActions();
            left.sort(Comparator.comparingInt(TestPlanAction::getAtSeconds));   // 안정 정렬
            int offset = left.get(0).getAtSeconds();
            int seq = 0;
            for (TestPlanAction a : left) {
                a.setSeq(seq++);
                if (offset > 0) a.setAtSeconds(a.getAtSeconds() - offset);
            }
            plan.setUpdatedAt(LocalDateTime.now());
            out.updated.add(planId);
        }
        return out;
    }

    /**
     * Action 을 새 행으로 복사해 붙인다. 실행 시각 순으로 늘어놓고 seq 를 다시 매긴다 —
     * 같은 시각끼리는 들어온 순서를 지킨다(그 순서대로 쓴다). 들어온 seq 는 믿지 않는다.
     */
    private static void attachActions(TestPlan plan, List<TestPlanAction> given) {
        if (given == null) return;
        List<TestPlanAction> sorted = new ArrayList<>(given);
        sorted.sort(Comparator.comparingInt(TestPlanAction::getAtSeconds));   // 안정 정렬
        int seq = 0;
        for (TestPlanAction src : sorted) {
            TestPlanAction a = new TestPlanAction();
            a.setPlan(plan);
            a.setSeq(seq++);
            a.setDeviceId(src.getDeviceId());
            a.setTarget(src.getTarget());
            a.setValue(src.getValue());
            a.setDataType(src.getDataType() == null || src.getDataType().isEmpty() ? null : src.getDataType());
            a.setLockSeconds(src.getLockSeconds());
            a.setAtSeconds(src.getAtSeconds());
            a.setStopOnFailure(src.getStopOnFailure());
            plan.getActions().add(a);
        }
    }

    // ── 실행 이력 ────────────────────────────────────────────────────────

    public List<TestPlanRun> selectRuns(Integer planId, int limit) {
        return testPlanRunRepository.findByPlan_IdOrderByIdDesc(planId, PageRequest.of(0, Math.max(1, limit)));
    }

    public Optional<TestPlanRun> selectRun(Long runId) {
        return runId == null ? Optional.empty() : testPlanRunRepository.findById(runId);
    }

    public List<TestPlanRunAction> selectRunActions(Long runId) {
        return testPlanRunActionRepository.findByRunIdOrderBySeq(runId);
    }

    @Transactional
    public TestPlanRun startRun(TestPlan plan, TestPlanTrigger trigger) {
        TestPlanRun run = new TestPlanRun();
        run.setPlan(plan);
        run.setTriggerType(trigger);
        run.setStartedAt(LocalDateTime.now());
        run.setResult(TestPlanRunResult.RUNNING);
        run.setTotalActions(plan.getActions().size());
        return testPlanRunRepository.save(run);
    }

    @Transactional
    public TestPlanRunAction recordAction(TestPlanRunAction action) {
        return testPlanRunActionRepository.save(action);
    }

    /** 실행을 끝내고, 보관 개수를 넘는 오래된 이력을 지운다. */
    @Transactional
    public TestPlanRun finishRun(Long runId, TestPlanRunResult result, String message, int failedActions) {
        TestPlanRun run = testPlanRunRepository.findById(runId).orElseThrow(EntityNotFoundException::new);
        run.setResult(result);
        run.setMessage(message);
        run.setFailedActions(failedActions);
        run.setEndedAt(LocalDateTime.now());
        trimRuns(run.getPlanId());
        return run;
    }

    /** 최근 {@link #KEEP_RUNS} 회만 남긴다. */
    @Transactional
    public int trimRuns(Integer planId) {
        List<Long> boundary = testPlanRunRepository.findIdsByPlanIdNewestFirst(
                planId, PageRequest.of(KEEP_RUNS - 1, 1));
        if (boundary.isEmpty()) return 0;
        return testPlanRunRepository.deleteOlderThan(planId, boundary.get(0));
    }

    /**
     * interval 로 저장된 예전 Plan 을 이력과 함께 지운다. 실행 시각(at_seconds)으로 바꾸며 옮기지 않기로 했다.
     * 예전 열 interval_seconds 는 NOT NULL 이라 남겨 두면 새 Action 을 넣지 못하므로 열도 지운다.
     * 2026-10-23~28 개발 빌드가 만든 DB 에만 있는 열이다. 기동 시 BootMigrations 가 한 트랜잭션으로 부른다.
     * MariaDB 는 ALTER 에서 커밋되지만 그 앞의 DELETE 는 다시 해도 같으므로 중간에 멈춰도 다음 기동에 마저 한다.
     *
     * @return 지운 Plan 수. 예전 열이 없으면(이미 했으면) -1
     */
    public int dropIntervalPlans() {
        if (!schemaInspector.hasColumn("test_plan_actions", "interval_seconds")) return -1;
        Integer plans = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_plans", Integer.class);
        jdbcTemplate.update("DELETE FROM test_plan_run_actions");
        jdbcTemplate.update("DELETE FROM test_plan_runs");
        jdbcTemplate.update("DELETE FROM test_plan_actions");
        jdbcTemplate.update("DELETE FROM test_plans");
        jdbcTemplate.execute("ALTER TABLE test_plan_actions DROP COLUMN interval_seconds");
        return plans == null ? 0 : plans;
    }

    /**
     * 예전 열 skip_on_failure(1 = 실패해도 계속)를 stop_on_failure(1 = 실패하면 멈춤)로 뒤집어 옮긴다.
     * 기본이 "실패해도 계속" 으로 바뀌었다. 저장된 Plan 의 동작은 그대로 둔다.
     *
     * stop_on_failure 를 skip_on_failure 에서 매번 새로 계산하므로 ALTER 전에 멈춰도 다시 해서 같은 값이 된다.
     *
     * @return 옮긴 Action 수. 예전 열이 없으면(이미 했으면) -1
     */
    public int migrateSkipToStop() {
        if (!schemaInspector.hasColumn("test_plan_actions", "skip_on_failure")) return -1;
        int n = jdbcTemplate.update("UPDATE test_plan_actions SET stop_on_failure = 1 - skip_on_failure");
        jdbcTemplate.execute("ALTER TABLE test_plan_actions DROP COLUMN skip_on_failure");
        return n;
    }

    /**
     * 기동 시 부른다. 서버가 실행 도중 내려가 RUNNING 으로 남은 이력을 STOPPED 로 닫는다.
     * 실행은 이어서 하지 않는다(E4).
     */
    @Transactional
    public int closeInterruptedRuns() {
        List<TestPlanRun> dangling = testPlanRunRepository.findByResult(TestPlanRunResult.RUNNING);
        LocalDateTime now = LocalDateTime.now();
        for (TestPlanRun run : dangling) {
            run.setResult(TestPlanRunResult.STOPPED);
            run.setMessage("Server restarted");
            run.setEndedAt(now);
        }
        if (!dangling.isEmpty()) {
            LoggerFactory.getLogger("testplan").info("Closed {} test plan run(s) left RUNNING by a restart.", dangling.size());
        }
        return dangling.size();
    }
}
