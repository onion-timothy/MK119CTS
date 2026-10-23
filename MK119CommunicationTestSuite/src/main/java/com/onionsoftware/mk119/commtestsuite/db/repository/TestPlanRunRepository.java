package com.onionsoftware.mk119.commtestsuite.db.repository;

import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRun;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanRunResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestPlanRunRepository extends JpaRepository<TestPlanRun, Long> {
    /**
     * 최근 것부터. id 는 IDENTITY 라 시작 순서와 같다.
     * {@code Plan_Id} 로 경로를 적는다 — TestPlanRun 에 JSON 용 getPlanId() 가 있어
     * {@code PlanId} 로 쓰면 Spring Data 가 없는 속성 planId 를 찾는다.
     */
    List<TestPlanRun> findByPlan_IdOrderByIdDesc(Integer planId, Pageable pageable);

    List<TestPlanRun> findByResult(TestPlanRunResult result);

    /** 최근 것부터 센 id 목록. 보관 개수를 넘는 경계를 찾는 데 쓴다. */
    @Query("select r.id from TestPlanRun r where r.plan.id = :planId order by r.id desc")
    List<Long> findIdsByPlanIdNewestFirst(@Param("planId") Integer planId, Pageable pageable);

    /** id 가 boundary 보다 작은(더 오래된) 실행을 지운다. Action 결과는 DB 의 ON DELETE CASCADE 가 지운다. */
    @Modifying
    @Query("delete from TestPlanRun r where r.plan.id = :planId and r.id < :boundary")
    int deleteOlderThan(@Param("planId") Integer planId, @Param("boundary") Long boundary);
}
