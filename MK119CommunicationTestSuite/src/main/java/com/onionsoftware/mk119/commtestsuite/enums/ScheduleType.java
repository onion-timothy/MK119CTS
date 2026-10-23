package com.onionsoftware.mk119.commtestsuite.enums;

/**
 * Test Plan 의 스케줄 종류. 값은 하나씩만 받는다 (여러 시각이 필요하면 Plan 을 여러 개 둔다).
 */
public enum ScheduleType {
    /** 스케줄 없음. 수동 실행만 한다. */
    NONE(0),
    /** 매분 S초. 주기 1분. */
    SECOND(60),
    /** 매시 M분 S초. 주기 1시간. */
    MINUTE(3600),
    /** 매일 H시 M분 S초. 주기 1일. */
    HOUR(86400);

    private final int periodSeconds;

    ScheduleType(int periodSeconds) {
        this.periodSeconds = periodSeconds;
    }

    /** 스케줄 주기(초). NONE 은 0. */
    public int periodSeconds() {
        return periodSeconds;
    }
}
