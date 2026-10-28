package com.onionsoftware.mk119.commtestsuite.enums;

/**
 * Test Plan 의 스케줄 종류. 두 갈래다.
 * <ul>
 *   <li>cron — SECOND, MINUTE, HOUR. 정해진 시각에 돈다. 값은 하나씩만 받는다 (여러 시각이 필요하면 Plan 을 여러 개 둔다)</li>
 *   <li>interval — INTERVAL. 켜는 순간 돌고, 그 뒤 사용자가 정한 초마다 돈다</li>
 * </ul>
 */
public enum ScheduleType {
    /** 스케줄 없음. 수동 실행만 한다. */
    NONE(0),
    /** 매분 S초. 주기 1분. */
    SECOND(60),
    /** 매시 M분 S초. 주기 1시간. */
    MINUTE(3600),
    /** 매일 H시 M분 S초. 주기 1일. */
    HOUR(86400),
    /** N초마다. 주기는 Plan 의 scheduleIntervalSeconds 다(여기의 값은 0). */
    INTERVAL(0);

    private final int periodSeconds;

    ScheduleType(int periodSeconds) {
        this.periodSeconds = periodSeconds;
    }

    /** cron 스케줄의 주기(초). NONE 과 INTERVAL 은 0 — INTERVAL 은 Plan 마다 다르다. */
    public int periodSeconds() {
        return periodSeconds;
    }

    public boolean isCron() {
        return this == SECOND || this == MINUTE || this == HOUR;
    }
}
