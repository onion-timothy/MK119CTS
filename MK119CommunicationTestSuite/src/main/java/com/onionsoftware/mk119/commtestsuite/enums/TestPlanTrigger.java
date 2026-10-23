package com.onionsoftware.mk119.commtestsuite.enums;

/** Test Plan 실행을 시작한 쪽. */
public enum TestPlanTrigger {
    /** 화면이나 외부 REST 의 run 요청. */
    MANUAL,
    /** 스케줄 시각. */
    SCHEDULE
}
