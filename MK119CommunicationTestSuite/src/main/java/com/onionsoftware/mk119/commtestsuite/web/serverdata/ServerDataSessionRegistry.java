package com.onionsoftware.mk119.commtestsuite.web.serverdata;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ServerData 편집 세션 소유권 및 liveness 관리.
 * <p>
 * 기본은 디바이스당 편집 세션 1개다. {@code cts.serverdata.multi-session.enabled=true} 이면
 * 한 디바이스에 여러 세션이 동시에 들어올 수 있다.
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

        SessionInfo(int deviceId, String user) {
            this.deviceId = deviceId;
            this.user = user;
            this.lastAckTs = System.currentTimeMillis();
        }
    }

    @Value("${cts.serverdata.multi-session.enabled:false}")
    private boolean multiSessionEnabled;

    /**
     * deviceId → (user → SessionInfo). 세션이 하나도 없으면 키 자체가 없다.
     * 한 디바이스의 진입·이탈은 이 맵의 compute 로만 바꿔 서로 엇갈리지 않게 한다 —
     * STOMP 메시지와 disconnect 이벤트는 병렬로 처리된다.
     */
    private final Map<Integer, Map<String, SessionInfo>> activeByDevice = new ConcurrentHashMap<>();
    /** wsSessionId → SessionInfo. disconnect 이벤트가 sessionId 로 오므로 유지. */
    private final Map<String, SessionInfo> byWsSessionId = new ConcurrentHashMap<>();
    /** user(=Principal name) → SessionInfo. 메시지 핸들러가 user 로 조회. */
    private final Map<String, SessionInfo> byUser = new ConcurrentHashMap<>();
    /** 디바이스별 일련번호. 스냅샷과 이벤트 묶음이 같이 올리므로 세션이 여럿이어도 한 줄로 이어진다. */
    private final Map<Integer, AtomicLong> seqByDevice = new ConcurrentHashMap<>();

    @Autowired
    private IOServerInstances ioServerInstances;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    public Optional<SessionInfo> tryEnter(int deviceId, String wsSessionId, String user) {
        SessionInfo[] granted = new SessionInfo[1];
        boolean[] created = {false};
        activeByDevice.compute(deviceId, (k, m) -> {
            if (m == null) m = new ConcurrentHashMap<>();
            SessionInfo prev = m.get(user);
            if (prev != null) {
                granted[0] = prev;
                return m;
            }
            if (!multiSessionEnabled && !m.isEmpty()) return m;
            granted[0] = new SessionInfo(deviceId, user);
            created[0] = true;
            m.put(user, granted[0]);
            return m;
        });
        if (granted[0] == null) return Optional.empty();
        if (created[0]) {
            byWsSessionId.put(wsSessionId, granted[0]);
            byUser.put(user, granted[0]);
            LoggerFactory.getLogger("serverdata").info("ServerData edit session claimed: device={}, user={}, wsSession={}, multiSession={}",
                    deviceId, user, wsSessionId, multiSessionEnabled);
        }
        return Optional.of(granted[0]);
    }

    /** 이 디바이스의 살아있는 편집 세션 전부. 없으면 빈 목록. */
    public Collection<SessionInfo> getActiveSessions(int deviceId) {
        Map<String, SessionInfo> m = activeByDevice.get(deviceId);
        return m == null ? Collections.emptyList() : new ArrayList<>(m.values());
    }

    public long nextSeq(int deviceId) {
        return seqByDevice.computeIfAbsent(deviceId, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 이 디바이스에 살아있는 편집 세션이 하나라도 있는지.
     * 마지막 세션이 끝날 때 {@link #clearDeviceLocks(int)} 가 만료 없는 lock 을 회수하므로,
     * 만료 없는 lock 요청을 받아줄 수 있는지 판단하는 기준이 된다.
     */
    public boolean hasActiveSession(int deviceId) {
        return activeByDevice.containsKey(deviceId);
    }

    public boolean isActiveEditor(int deviceId, String user) {
        Map<String, SessionInfo> m = activeByDevice.get(deviceId);
        return m != null && m.containsKey(user);
    }

    public void touchByUser(String user) {
        SessionInfo info = byUser.get(user);
        if (info != null) info.lastAckTs = System.currentTimeMillis();
    }

    private void invalidate(SessionInfo info, String wsSessionId, String reason) {
        if (info == null) return;
        boolean[] lastOut = {false};
        activeByDevice.computeIfPresent(info.deviceId, (k, m) -> {
            m.remove(info.user, info);
            if (m.isEmpty()) {
                lastOut[0] = true;
                return null;
            }
            return m;
        });
        byUser.remove(info.user, info);
        if (wsSessionId != null) byWsSessionId.remove(wsSessionId, info);
        else byWsSessionId.values().removeIf(v -> v == info);
        // 다른 세션이 남아 있으면 그쪽이 건 lock 일 수 있으므로 마지막 세션이 나갈 때만 푼다.
        // compute 밖에서 부른다 — lock 해제는 ServerData 의 알림까지 이어진다.
        if (lastOut[0]) clearDeviceLocks(info.deviceId);
        LoggerFactory.getLogger("serverdata").info("ServerData edit session invalidated: device={}, user={}, reason={}, lastOut={}",
                info.deviceId, info.user, reason, lastOut[0]);
        try {
            java.util.Map<String, Object> msg = new java.util.HashMap<>();
            msg.put("type", "invalidated");
            msg.put("reason", reason);
            messagingTemplate.convertAndSendToUser(info.user, "/queue/serverdata/status", msg);
        } catch (Exception ignored) { }
    }

    /** 세션들이 건 만료 없는 lock 만 푼다. 초 단위 lock 은 만료될 때까지 둔다. */
    private void clearDeviceLocks(int deviceId) {
        Optional<IOServer> opt = ioServerInstances.getServerInstance(deviceId);
        opt.ifPresent(s -> {
            if (s.getServerData() != null) {
                s.getServerData().clearPermanentLocks();
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
