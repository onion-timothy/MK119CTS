package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/**
 * Test Plan 의 Action. ServerData Write API 의 파라미터와 다음 Action 까지의 interval 이다.
 * <p>
 * 검증 규칙은 docs/TEST_PLAN_VALIDATION.md 의 A 항목이다.
 */
@Getter
@Setter
@Entity
@Table(name = "test_plan_actions")
public class TestPlanAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private TestPlan plan;

    /**
     * 실행 순서(0부터). (plan_id, seq) 에 UNIQUE 를 두지 않는다 — 목록을 통째로 갈아 끼울 때
     * Hibernate 가 새 행 insert 를 옛 행 delete 보다 먼저 flush 해서 제약에 걸린다.
     * 순서는 {@code TestPlanService} 가 목록 위치로 매긴다.
     */
    private int seq;

    /**
     * 대상 장비. FK 를 두지 않는다 — FK 가 있으면 장비 삭제가 막힌다.
     * 장비가 지워진 Action 은 실행 시 실패로 보고 skip 정책을 따른다(R1).
     */
    private Integer deviceId;

    @Column(length = 512, nullable = false)
    private String target;

    @Column(name = "`value`", nullable = false)
    private double value;

    /** DataType enum 이름. 비어 있으면 지정하지 않은 것. */
    @Column(length = 16)
    private String dataType;

    /** 0 이상. 0 이면 lock 하지 않는다(있던 lock 은 푼다). 만료 없는 lock(음수)은 받지 않는다. */
    private int lockSeconds;

    /** 이 Action 뒤 다음 Action 까지 기다리는 시간(초). 0 이면 곧바로 다음 Action. */
    private int intervalSeconds;

    /** 1 이면 실패해도 기록하고 다음 Action 으로, 0 이면 Plan 을 중단한다. */
    private int skipOnFailure;
}
