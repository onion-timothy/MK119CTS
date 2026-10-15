package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ServerData 편집 세션(디바이스당 최대 1개) 소유권 및 liveness 관리.
 * <p>
 * 세션 식별자로는 Handshake 시 부여된 Principal 이름(UUID)을 사용한다.
 * Spring 의 {@code /user/**} 라우팅과 완전히 정합하므로 개인 큐 전달이 안정된다.
 */
@Component
public class ServerDataSessionRegistry {

    /**
     * ack 미수신 무효화 임계 (ms).
     * 브라우저 백그라운드 throttling / 메인스레드 정체 마진을 감안해 여유있게 잡는다.
     * (활성 사용자를 잘못 죽이는 비용 >> 죽은 세션이 조금 더 남아있는 비용)
     */
    public static final long ACK_TIMEOUT_MS = 60_000L;

    static class SessionInfo {
        final int deviceId;
        /** 이 세션의 Principal 이름 = 개인 큐 라우팅 키. */
        final String user;
        volatile long lastAckTs;
        final AtomicLong seq = new AtomicLong(0);

        SessionInfo(int deviceId, String user) {
            this.deviceId = deviceId;
            this.user = user;
            this.lastAckTs = System.currentTimeMillis();
        }
    }

    private final Map<Integer, SessionInfo> activeByDevice = new ConcurrentHashMap<>();
    /** wsSessionId → SessionInfo. disconnect 이벤트가 sessionId 로 오므로 유지. */
    private final Map<String, SessionInfo> byWsSessionId = new ConcurrentHashMap<>();
    /** user(=Principal name) → SessionInfo. 메시지 핸들러가 user 로 조회. */
    private final Map<String, SessionInfo> byUser = new ConcurrentHashMap<>();

    @Autowired
    private IOServerInstances ioServerInstances;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    public Optional<SessionInfo> tryEnter(int deviceId, String wsSessionId, String user) {
        SessionInfo prev = activeByDevice.get(deviceId);
        if (prev != null && prev.user.equals(user)) {
            return Optional.of(prev);
        }
        SessionInfo created = new SessionInfo(deviceId, user);
        SessionInfo existing = activeByDevice.putIfAbsent(deviceId, created);
        if (existing != null) {
            return Optional.empty();
        }
        byWsSessionId.put(wsSessionId, created);
        byUser.put(user, created);
        MkLogger.log("serverdata", LogLevel.INFO,
                "ServerData edit session claimed: device={0}, user={1}, wsSession={2}",
                deviceId, user, wsSessionId);
        return Optional.of(created);
    }

    public Optional<SessionInfo> getActive(int deviceId) {
        return Optional.ofNullable(activeByDevice.get(deviceId));
    }

    /**
     * 이 디바이스에 살아있는 편집 세션이 있는지.
     * <p>
     * {@link #getActive(int)} 가 package-private {@code SessionInfo} 를 노출하므로
     * 다른 패키지(REST 컨트롤러)에서는 이 메서드를 쓴다.
     * 세션이 있으면 종료/무효화 시 {@link #clearDeviceLocks(int)} 가 lock 을 회수하므로,
     * 만료 없는 lock 요청을 받아줄 수 있는지 판단하는 기준이 된다.
     */
    public boolean hasActiveSession(int deviceId) {
        return activeByDevice.containsKey(deviceId);
    }

    public boolean isActiveEditor(int deviceId, String user) {
        SessionInfo info = activeByDevice.get(deviceId);
        return info != null && info.user.equals(user);
    }

    public void touchByUser(String user) {
        SessionInfo info = byUser.get(user);
        if (info != null) info.lastAckTs = System.currentTimeMillis();
    }

    private void invalidate(SessionInfo info, String wsSessionId, String reason) {
        if (info == null) return;
        activeByDevice.remove(info.deviceId, info);
        byUser.remove(info.user, info);
        if (wsSessionId != null) byWsSessionId.remove(wsSessionId, info);
        else byWsSessionId.values().removeIf(v -> v == info);
        clearDeviceLocks(info.deviceId);
        MkLogger.log("serverdata", LogLevel.INFO,
                "ServerData edit session invalidated: device={0}, user={1}, reason={2}",
                info.deviceId, info.user, reason);
        try {
            java.util.Map<String, Object> msg = new java.util.HashMap<>();
            msg.put("type", "invalidated");
            msg.put("reason", reason);
            messagingTemplate.convertAndSendToUser(info.user, "/queue/serverdata/status", msg);
        } catch (Exception ignored) { }
    }

    private void clearDeviceLocks(int deviceId) {
        Optional<IOServer> opt = ioServerInstances.getServerInstance(deviceId);
        opt.ifPresent(s -> {
            if (s.getServerData() != null) {
                s.getServerData().clearAllLocks();
            }
        });
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String wsSessionId = event.getSessionId();
        SessionInfo info = byWsSessionId.get(wsSessionId);
        if (info != null) invalidate(info, wsSessionId, "disconnect");
    }

    @Scheduled(fixedDelay = 1_000L)
    public void reapDeadSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, SessionInfo>> it = byUser.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SessionInfo> e = it.next();
            SessionInfo info = e.getValue();
            if (now - info.lastAckTs > ACK_TIMEOUT_MS) {
                invalidate(info, null, "ack timeout");
            }
        }
    }

    /** 편의: Principal 로 조회. null 안전. */
    public static String userOf(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
