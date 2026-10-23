package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanAction;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRun;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRunAction;
import com.onionsoftware.mk119.commtestsuite.db.repository.TestPlanRepository;
import com.onionsoftware.mk119.commtestsuite.db.repository.TestPlanRunActionRepository;
import com.onionsoftware.mk119.commtestsuite.db.repository.TestPlanRunRepository;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanRunResult;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanTrigger;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /**
     * Action 을 새 행으로 복사해 붙인다. seq 는 목록 위치로 다시 매긴다 —
     * 들어온 seq 를 믿으면 빈 번호나 중복이 실행 순서를 흐린다.
     */
    private static void attachActions(TestPlan plan, List<TestPlanAction> given) {
        if (given == null) return;
        int seq = 0;
        for (TestPlanAction src : given) {
            TestPlanAction a = new TestPlanAction();
            a.setPlan(plan);
            a.setSeq(seq++);
            a.setDeviceId(src.getDeviceId());
            a.setTarget(src.getTarget());
            a.setValue(src.getValue());
            a.setDataType(src.getDataType() == null || src.getDataType().isEmpty() ? null : src.getDataType());
            a.setLockSeconds(src.getLockSeconds());
            a.setIntervalSeconds(src.getIntervalSeconds());
            a.setSkipOnFailure(src.getSkipOnFailure());
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
