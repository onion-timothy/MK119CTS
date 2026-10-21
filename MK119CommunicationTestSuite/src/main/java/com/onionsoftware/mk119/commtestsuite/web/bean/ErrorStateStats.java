package com.onionsoftware.mk119.commtestsuite.web.bean;

import lombok.Getter;
import lombok.Setter;

/**
 * 디바이스 서버 기동 이후 각 에러 상태가 점유한 누적 시간 (ms).
 * NORMAL 은 활성 시스템 에러가 없는 평시 상태.
 * SIMULATION 모드는 지속 상태가 아니라 tick 기반이라 포함하지 않는다.
 */
@Getter
@Setter
public class ErrorStateStats {
    /** 서버 기동 이후 총 경과 시간. */
    private long uptimeMs;
    private long normalMs;
    private long discardMs;
    private long timeoutMs;
    private long garbageMs;
    private long disconnectMs;
}
