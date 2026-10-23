package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanRunResult;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanTrigger;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Test Plan 실행 1회. Plan 마다 최근 {@code TestPlanService.KEEP_RUNS} 회만 남긴다. */
@Getter
@Setter
@Entity
/*
 * 이력은 plan_id 로 걸러 id 순으로 읽는다. plan_id 의 FK 인덱스가 InnoDB 에서 PK 를 함께 담으므로
 * 별도 인덱스를 두지 않는다.
 */
@Table(name = "test_plan_runs")
public class TestPlanRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private TestPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private TestPlanTrigger triggerType;

    private LocalDateTime startedAt;
    /** 실행 중이면 null. */
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private TestPlanRunResult result;

    /** 중단 사유, "Stopped by user", "Server restarted" 등. */
    @Column(length = 512)
    private String message;

    private int totalActions;
    private int failedActions;

    /**
     * Action 결과. 화면은 저장소로 따로 읽는다. 여기 둔 것은 FK 가 ON DELETE CASCADE 로
     * 만들어지게 하기 위해서다({@link TestPlan#getRuns()} 와 같은 이유).
     */
    @JsonIgnore
    @OneToMany(mappedBy = "run")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<TestPlanRunAction> actions = new ArrayList<>();

    /** JSON 응답용. */
    public Integer getPlanId() {
        return plan == null ? null : plan.getId();
    }
}
