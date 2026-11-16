package com.onionsoftware.mk119.commtestsuite.web.bean;

import lombok.Getter;
import lombok.Setter;

/**
 * 누적 시작(디바이스 서버 기동 또는 마지막 에러 구성 변경) 이후 각 에러 상태가 점유한 누적 시간 (ms).
 * NORMAL 은 활성 시스템 에러가 없는 평시 상태.
 * SIMULATION 모드는 지속 상태가 아니라 tick 기반이라 포함하지 않는다.
 */
@Getter
@Setter
public class ErrorStateStats {
    /** 누적 시작 이후 총 경과 시간. */
    private long uptimeMs;
    /** 누적 시작이 서버 기동이 아니라 에러 구성 변경(추가·수정·삭제·켜고 끄기)이었는지. */
    private boolean sinceErrorChange;
    private long normalMs;
    private long discardMs;
    private long timeoutMs;
    private long garbageMs;
    private long disconnectMs;
}
