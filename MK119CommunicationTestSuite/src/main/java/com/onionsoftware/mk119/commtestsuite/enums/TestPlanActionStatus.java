package com.onionsoftware.mk119.commtestsuite.enums;

/** 실행된 Action 하나의 결과. 실행하지 못한(중단 뒤) Action 은 기록하지 않는다. */
public enum TestPlanActionStatus {
    /** 썼다. */
    OK,
    /** 실패했고 skip 이 켜져 있어 다음 Action 으로 넘어갔다. */
    SKIPPED,
    /** 실패했고 skip 이 꺼져 있어 Plan 을 중단했다. */
    FAILED
}
