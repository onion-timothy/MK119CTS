package com.onionsoftware.mk119.commtestsuite.db.repository;

import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TestPlanRepository extends JpaRepository<TestPlan, Integer> {
    Optional<TestPlan> findByName(String name);
    boolean existsByName(String name);
    /** 이름을 바꿀 때 자기 자신을 빼고 중복을 본다. */
    boolean existsByNameAndIdNot(String name, Integer id);
}
