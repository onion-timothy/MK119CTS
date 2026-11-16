package com.onionsoftware.mk119.commtestsuite.db.repository;

import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TestPlanRepository extends JpaRepository<TestPlan, Integer> {
    Optional<TestPlan> findByName(String name);
    boolean existsByName(String name);
    /** 이름을 바꿀 때 자기 자신을 빼고 중복을 본다. */
    boolean existsByNameAndIdNot(String name, Integer id);

    /** 이 디바이스를 쓰는 Action 을 가진 Plan. 디바이스를 지울 때 정리 대상을 찾는다. */
    @Query("select distinct a.plan.id from TestPlanAction a where a.deviceId = :deviceId")
    List<Integer> findPlanIdsByActionDeviceId(@Param("deviceId") Integer deviceId);
}
