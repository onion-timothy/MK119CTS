package com.onionsoftware.mk119.commtestsuite.web.logs;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Realtime log DTO transferred over WebSocket (/topic/logs).
 * 과거 MkLogger 가 들고 있던 필드와 호환되는 모양으로 유지해 UI 수정 폭을 줄인다.
 */
@Getter
@AllArgsConstructor
public class LogEvent {
    private final long timestamp;
    private final String level;
    private final String subject;
    private final String message;
}
