package com.onionsoftware.mk119.commtestsuite.db.repository;

import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRunAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestPlanRunActionRepository extends JpaRepository<TestPlanRunAction, Long> {
    List<TestPlanRunAction> findByRunIdOrderBySeq(Long runId);
}
