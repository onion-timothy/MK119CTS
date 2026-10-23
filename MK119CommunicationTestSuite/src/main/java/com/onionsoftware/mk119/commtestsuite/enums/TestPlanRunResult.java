package com.onionsoftware.mk119.commtestsuite.enums;

/** Test Plan 실행 1회의 결과. */
public enum TestPlanRunResult {
    /** 실행 중. */
    RUNNING,
    /** 모든 Action 성공. */
    COMPLETED,
    /** skip 이 켜진 Action 이 하나 이상 실패했지만 끝까지 실행했다. */
    COMPLETED_WITH_SKIPS,
    /** skip 이 꺼진 Action 이 실패해 중단했다. */
    FAILED,
    /** 사용자가 멈췄거나, 실행 중에 서버가 재시작됐다. */
    STOPPED
}
