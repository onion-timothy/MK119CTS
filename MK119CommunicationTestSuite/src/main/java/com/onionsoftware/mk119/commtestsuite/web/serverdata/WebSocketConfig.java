package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket 구성. ServerData 편집·구독용.
 * <ul>
 *   <li>C→S 프리픽스: {@code /app}</li>
 *   <li>S→C 프리픽스: {@code /topic}, {@code /queue}(개인)</li>
 *   <li>엔드포인트: {@code /ws} (SockJS)</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    /**
     * 전송 한계. 기본값(송신 버퍼 512KB)으로는 큰 장비를 감당하지 못한다.
     * 레지스터 30000칸 장비의 스냅샷 하나가 255KB 라, 묶음 이벤트가 겹치면 바로 넘어가
     * {@code SubProtocolWebSocketHandler} 가 세션을 끊어 버렸다 (화면이 첫 스냅샷에서 멈춤).
     * 묶음 전송으로 양을 줄인 뒤에도, 순간 몰림에 대비해 여유를 둔다.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendBufferSizeLimit(4 * 1024 * 1024);
        registration.setMessageSizeLimit(1024 * 1024);
        registration.setSendTimeLimit(20 * 1000);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .setHandshakeHandler(new PrincipalHandshakeHandler())
                .withSockJS();
    }
}
