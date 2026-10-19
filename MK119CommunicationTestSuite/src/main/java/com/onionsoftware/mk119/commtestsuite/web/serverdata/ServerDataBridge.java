package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataEvent;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ServerData 변경 이벤트를 STOMP 토픽으로 중계한다. 각 IOServer 에 리스너로 부착된다.
 * <p>
 * 변경 1건당 메시지 1개를 즉시 보내면 큰 장비에서 버틸 수 없다. 장비 하나에 Simulation 이
 * 3400건 있고 주기가 같으면 {@code GroupedSimulationJob} 이 한 틱에 전부 처리하므로
 * 그 순간 메시지 3400개가 쏟아진다. 실제로 송신 버퍼(기본 512KB)를 넘겨
 * {@code SubProtocolWebSocketHandler} 가 세션을 끊었고, 화면은 첫 스냅샷만 받고 멈췄다.
 * <p>
 * 그래서 두 가지를 둔다.
 * <ul>
 *   <li><b>묶음 전송</b> — 변경을 target 단위로 모아 {@link #FLUSH_INTERVAL_MS} 마다 한 번에 보낸다.
 *       같은 target 이 그 사이 여러 번 바뀌면 마지막 값만 남는다. 화면도 한 묶음에 한 번만 그린다.</li>
 *   <li><b>구독자가 없으면 아무것도 하지 않는다</b> — 전에는 보는 사람이 없어도 항상 발행해서,
 *       화면을 열지 않아도 장비 하나가 초당 수백 건을 브로커에 밀어 넣었다.</li>
 *   <li><b>값이 그대로면 보내지 않는다</b> — STATIC 은 주기마다 같은 값을 다시 쓴다.
 *       화면에 보일 변화가 없는데도 전송하면 대역만 먹는다. STATIC 5851건을 3초마다 돌리는
 *       장비는 이것만으로 초당 1950건이 0건이 된다.</li>
 * </ul>
 */
@Component
public class ServerDataBridge implements ServerDataListener {

    /** 묶음을 비우는 간격 (ms). 사람 눈에는 즉시로 보이면서 메시지 수는 크게 준다. */
    public static final long FLUSH_INTERVAL_MS = 250L;

    /** 한 번에 보내는 변경 수 상한. 넘으면 나눠 보내 한 메시지가 버퍼를 넘기지 않게 한다. */
    private static final int MAX_CHANGES_PER_MESSAGE = 500;

    private static final Pattern EVENTS_DESTINATION =
            Pattern.compile("^/topic/devices/(\\d+)/serverdata/events$");

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /** deviceId -> (target -> 그 target 의 마지막 변경). 순서를 지키려고 LinkedHashMap 을 쓴다. */
    private final Map<Integer, Map<String, Map<String, Object>>> pending = new ConcurrentHashMap<>();

    /** deviceId -> 구독 키 집합. 비어 있으면 그 장비의 이벤트는 만들지도 않는다. */
    private final Map<Integer, Set<String>> subscribers = new ConcurrentHashMap<>();

    /** 구독 키 -> deviceId. UNSUBSCRIBE 에는 destination 이 없어 되찾을 길이 필요하다. */
    private final Map<String, Integer> subscriptionDevice = new ConcurrentHashMap<>();

    /**
     * deviceId -> (target -> 마지막으로 내보낸 "값|출처").
     * 값과 출처가 모두 그대로면 화면에 바뀔 것이 없으므로 보내지 않는다.
     * 출처까지 보는 이유는, 같은 값이라도 누가 썼는지가 달라지면 화면 표시가 달라지기 때문이다.
     */
    private final Map<Integer, Map<String, String>> lastSent = new ConcurrentHashMap<>();

    @Override
    public void onServerDataChanged(ServerDataEvent event) {
        int deviceId = event.getIoServer().getDevice().getId();
        if (!hasSubscriber(deviceId)) return;

        String fingerprint = event.getValue() + "|" + event.getSource().name();
        Map<String, String> sentOfDevice =
                lastSent.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>());
        if (fingerprint.equals(sentOfDevice.get(event.getTarget()))) {
            return;   // 값도 출처도 그대로다. 화면이 다시 그릴 이유가 없다.
        }
        sentOfDevice.put(event.getTarget(), fingerprint);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("target", event.getTarget());
        msg.put("value", event.getValue());
        msg.put("source", event.getSource().name());
        msg.put("ts", event.getTimestamp());
        // 한 칸짜리 변경은 target/value 만으로 충분하다. 32비트처럼 여러 칸을 건드릴 때만 싣는다.
        List<String> affected = event.getAffectedTargets();
        if (affected != null && affected.size() > 1) {
            msg.put("affectedTargets", affected);
            msg.put("targetValues", event.getTargetValues());
        }
        if (event.getSimulation() != null) {
            msg.put("simulationId", event.getSimulation().getId());
        }

        pending.computeIfAbsent(deviceId, k -> new LinkedHashMap<>());
        Map<String, Map<String, Object>> byTarget = pending.get(deviceId);
        synchronized (byTarget) {
            // 같은 target 이 또 바뀌면 마지막 것만 남긴다. 중간 값은 화면에 보여 줄 수 없다.
            byTarget.remove(event.getTarget());
            byTarget.put(event.getTarget(), msg);
        }
    }

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flush() {
        for (Integer deviceId : new ArrayList<>(pending.keySet())) {
            Map<String, Map<String, Object>> byTarget = pending.get(deviceId);
            if (byTarget == null) continue;
            List<Map<String, Object>> changes;
            synchronized (byTarget) {
                if (byTarget.isEmpty()) continue;
                changes = new ArrayList<>(byTarget.values());
                byTarget.clear();
            }
            if (!hasSubscriber(deviceId)) continue;   // 모으는 사이에 창이 닫혔다

            for (int from = 0; from < changes.size(); from += MAX_CHANGES_PER_MESSAGE) {
                List<Map<String, Object>> slice =
                        changes.subList(from, Math.min(from + MAX_CHANGES_PER_MESSAGE, changes.size()));
                Map<String, Object> batch = new LinkedHashMap<>();
                batch.put("type", "events");
                batch.put("ts", System.currentTimeMillis());
                batch.put("changes", new ArrayList<>(slice));
                messagingTemplate.convertAndSend(
                        "/topic/devices/" + deviceId + "/serverdata/events", batch);
            }
        }
    }

    private boolean hasSubscriber(int deviceId) {
        Set<String> s = subscribers.get(deviceId);
        return s != null && !s.isEmpty();
    }

    // ── 구독 추적 ────────────────────────────────────────────────────────
    // 브로커는 구독자 수를 알려 주지 않는다. STOMP 프레임을 직접 세어 둔다.

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        Integer deviceId = deviceIdOf(h.getDestination());
        if (deviceId == null) return;
        String key = subKey(h.getSessionId(), h.getSubscriptionId());
        subscribers.computeIfAbsent(deviceId, k -> ConcurrentHashMap.newKeySet()).add(key);
        subscriptionDevice.put(key, deviceId);
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        removeSubscription(subKey(h.getSessionId(), h.getSubscriptionId()));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) return;
        String prefix = sessionId + "|";
        for (String key : new ArrayList<>(subscriptionDevice.keySet())) {
            if (key.startsWith(prefix)) removeSubscription(key);
        }
    }

    private void removeSubscription(String key) {
        Integer deviceId = subscriptionDevice.remove(key);
        if (deviceId == null) return;
        Set<String> s = subscribers.get(deviceId);
        if (s != null) {
            s.remove(key);
            // 마지막 구독자가 나가면 모아 둔 것도 버린다. 다시 들어오면 스냅샷으로 받는다.
            if (s.isEmpty()) {
                subscribers.remove(deviceId);
                lastSent.remove(deviceId);
                Map<String, Map<String, Object>> byTarget = pending.remove(deviceId);
                if (byTarget != null) {
                    synchronized (byTarget) { byTarget.clear(); }
                }
            }
        }
    }

    private static String subKey(String sessionId, String subscriptionId) {
        return sessionId + "|" + subscriptionId;
    }

    private static Integer deviceIdOf(String destination) {
        if (destination == null) return null;
        Matcher m = EVENTS_DESTINATION.matcher(destination);
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }
}
