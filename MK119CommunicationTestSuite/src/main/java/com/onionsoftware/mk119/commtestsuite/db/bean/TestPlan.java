package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.onionsoftware.mk119.commtestsuite.enums.ScheduleType;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Test Plan. Action 배열이며, 실행기는 Action 을 실행하고 그 Action 의 interval 만큼 기다린 뒤
 * 다음 Action 으로 넘어간다. Action 마다 장비가 다를 수 있다.
 * <p>
 * 검증 규칙은 docs/TEST_PLAN_VALIDATION.md 의 P·S 항목이다.
 */
@Getter
@Setter
@Entity
@Table(name = "test_plans")
public class TestPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(length = 100, nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private ScheduleType scheduleType = ScheduleType.NONE;

    /** HOUR 에서만 쓴다. 0~23. */
    private Integer scheduleHour;
    /** MINUTE, HOUR 에서 쓴다. 0~59. */
    private Integer scheduleMinute;
    /** SECOND, MINUTE, HOUR 에서 쓴다. 0~59. */
    private Integer scheduleSecond;
    /** INTERVAL 에서만 쓴다. 실행 간격(초). */
    private Integer scheduleIntervalSeconds;

    /** 스케줄을 켜 두었는지. 꺼져 있으면 수동 실행만 된다. */
    private int scheduleEnabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 실행 순서대로. 편집은 목록을 통째로 갈아 끼운다(편집기가 Plan 전체를 저장한다).
     * Plan 을 지우면 DB 의 ON DELETE CASCADE 가 지운다.
     */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("seq")
    private List<TestPlanAction> actions = new ArrayList<>();

    /**
     * 실행 이력. 화면은 저장소로 따로 읽는다. 여기 둔 것은 Plan 을 지울 때
     * test_plan_runs 의 FK 가 ON DELETE CASCADE 로 만들어지게 하기 위해서다.
     * Hibernate 5.4 는 {@code @ManyToOne} 쪽의 {@code @OnDelete} 를 DDL 에 반영하지 않는다.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "plan")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<TestPlanRun> runs = new ArrayList<>();

    public static final int SCHEDULE_ENABLED = 1;
    public static final int SCHEDULE_DISABLED = 0;

    /** 스케줄 주기(초). cron 은 종류가 정하고, INTERVAL 은 Plan 이 정한다. 스케줄이 없으면 0. */
    public long schedulePeriodSeconds() {
        if (scheduleType == ScheduleType.INTERVAL) return scheduleIntervalSeconds == null ? 0 : scheduleIntervalSeconds;
        return scheduleType == null ? 0 : scheduleType.periodSeconds();
    }
}
