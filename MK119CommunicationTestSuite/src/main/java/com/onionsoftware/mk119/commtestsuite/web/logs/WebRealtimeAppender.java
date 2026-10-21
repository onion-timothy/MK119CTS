package com.onionsoftware.mk119.commtestsuite.web.logs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;

/**
 * logback appender 로서 모든 로그 이벤트를 받아 {@link LogBroadcaster} 로 흘려보낸다.
 * 버퍼링 없음 — Settings 화면이 구독하기 전의 로그는 보지 않는다.
 *
 * logback 은 Spring 보다 먼저 초기화되므로, broadcaster 는 Spring 이 뜬 뒤
 * {@link LogBroadcaster} 의 {@code @PostConstruct} 에서 static 으로 bind 한다.
 */
public class WebRealtimeAppender extends AppenderBase<ILoggingEvent> {

    private static volatile LogBroadcaster broadcaster;
    /**
     * STOMP broadcast 가 Spring·Netty 내부에서 로그를 다시 뱉어 재귀로 들어오는 걸 막는 가드.
     */
    private static final ThreadLocal<Boolean> IN_BROADCAST = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void bind(LogBroadcaster b) {
        broadcaster = b;
    }

    @Override
    public void start() {
        super.start();
        addInfo("WebRealtimeAppender started");
    }

    @Override
    protected void append(ILoggingEvent event) {
        LogBroadcaster b = broadcaster;
        if (b == null || IN_BROADCAST.get()) return;
        LogEvent le = new LogEvent(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLoggerName(),
                formatMessage(event));
        IN_BROADCAST.set(Boolean.TRUE);
        try {
            b.broadcast(le);
        } catch (Throwable ignored) {
            // broadcast 실패가 로깅 전체를 막아선 안 된다.
        } finally {
            IN_BROADCAST.set(Boolean.FALSE);
        }
    }

    private static String formatMessage(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp == null) return msg;
        StringBuilder sb = new StringBuilder(msg == null ? "" : msg);
        sb.append('\n').append(tp.getClassName());
        if (tp.getMessage() != null) sb.append(": ").append(tp.getMessage());
        for (StackTraceElementProxy ste : tp.getStackTraceElementProxyArray()) {
            sb.append("\n\tat ").append(ste.getSTEAsString());
        }
        return sb.toString();
    }
}
