package com.onionsoftware.mk119.commtestsuite.web.logs;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * {@link WebRealtimeAppender} 가 받은 로그를 STOMP {@code /topic/logs} 로 흘려보내는 bridge.
 */
@Component
public class LogBroadcaster {

    public static final String TOPIC = "/topic/logs";

    private final SimpMessagingTemplate messagingTemplate;

    public LogBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    void register() {
        WebRealtimeAppender.bind(this);
    }

    public void broadcast(LogEvent event) {
        messagingTemplate.convertAndSend(TOPIC, event);
    }
}
