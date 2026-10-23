package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanActionStatus;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 실행된 Action 하나의 결과. 실행 당시 Action 의 값을 복사해 둔다 —
 * 나중에 Plan 을 고쳐도 이력은 그때 실행한 그대로 남아야 한다.
 */
@Getter
@Setter
@Entity
@Table(name = "test_plan_run_actions")
public class TestPlanRunAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private TestPlanRun run;

    private int seq;
    private Integer deviceId;
    @Column(length = 512)
    private String target;
    @Column(name = "`value`")
    private double value;
    @Column(length = 16)
    private String dataType;
    private int lockSeconds;

    private LocalDateTime executedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private TestPlanActionStatus status;

    /** {@code ServerDataWriteService.Result} 의 status. 쓰기까지 가지 못했으면(장비 삭제 등) null. */
    private Integer httpStatus;
    @Column(length = 1024)
    private String error;
}
