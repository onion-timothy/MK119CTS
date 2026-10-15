package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * 각 WebSocket 연결마다 UUID Principal 을 발급한다.
 * Spring 의 {@code /user/**} 라우팅은 Principal 이 있을 때 안정적으로 동작하므로,
 * 익명 사용자에게도 세션당 유일한 Principal 을 부여해 개인 큐 전달을 보장한다.
 */
public class PrincipalHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        final String name = UUID.randomUUID().toString();
        return () -> name;
    }
}
