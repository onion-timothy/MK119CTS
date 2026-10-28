package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *   <li><b>값이 그대로면 본문을 싣지 않는다</b> — STATIC 은 주기마다 같은 값을 다시 쓴다.
 *       바뀐 것이 없는데 변경 본문을 보내면 대역만 먹으므로, target 이름만 touched 로 모아
 *       보내 화면이 깜빡이게만 한다. 본문 90바이트가 문자열 9바이트로 줄어든다.</li>
 * </ul>
 */
@Component
public class ServerDataBridge implements ServerDataListener {

    /** 묶음을 비우는 간격 (ms). 사람 눈에는 즉시로 보이면서 메시지 수는 크게 준다. */
    public static final long FLUSH_INTERVAL_MS = 250L;

    /** 한 번에 보내는 변경 수 상한. 넘으면 나눠 보내 한 메시지가 버퍼를 넘기지 않게 한다. */
    private static final int MAX_CHANGES_PER_MESSAGE = 500;

    /**
     * 한 묶음에 실을 깜빡임 전용 값 묶음 수의 상한.
     * target 이름 하나가 9바이트 남짓이라 10000개를 실어도 90KB 다. 변경 본문으로 같은 수를
     * 보내면 900KB 라 자리수가 다르다. 3400건짜리 장비가 통째로 들어가는 선으로 잡았다.
     */
    private static final int MAX_TOUCHED_PER_MESSAGE = 10_000;

    private static final Pattern EVENTS_DESTINATION =
            Pattern.compile("^/topic/devices/(\\d+)/serverdata/events$");

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 묶음에도 스냅샷과 같은 일련번호를 붙이기 위해 쓴다.
     * 편집 세션은 장비당 하나이므로 그 세션의 카운터를 공유하면 번호가 끊기지 않는다.
     */
    @Autowired
    private ServerDataSessionRegistry sessionRegistry;

    /** deviceId -> (target -> 그 target 의 마지막 변경). 순서를 지키려고 LinkedHashMap 을 쓴다. */
    private final Map<Integer, Map<String, Map<String, Object>>> pending = new ConcurrentHashMap<>();

    /** deviceId -> 구독 키 집합. 비어 있으면 그 장비의 이벤트는 만들지도 않는다. */
    private final Map<Integer, Set<String>> subscribers = new ConcurrentHashMap<>();

    /** 구독 키 -> deviceId. UNSUBSCRIBE 에는 destination 이 없어 되찾을 길이 필요하다. */
    private final Map<String, Integer> subscriptionDevice = new ConcurrentHashMap<>();

    /**
     * deviceId -> (칸 -> 마지막으로 내보낸 "값|출처").
     * 값과 출처가 모두 그대로면 화면에 바뀔 것이 없으므로 보내지 않는다.
     * 출처까지 보는 이유는, 같은 값이라도 누가 썼는지가 달라지면 화면 표시가 달라지기 때문이다.
     * 키는 쓰기의 base target 이 아니라 그 쓰기가 덮는 칸 하나하나다 — {@link #unchangedSinceLastSent}.
     */
    private final Map<Integer, Map<String, String>> lastSent = new ConcurrentHashMap<>();

    /**
     * deviceId -> 값이 그대로여서 본문을 싣지 않은 target 집합.
     * <p>
     * STATIC 은 주기마다 같은 값을 다시 쓴다. 값이 안 바뀌니 보낼 것이 없다고 끊어 버리면
     * 화면에서 그 셀이 아무 반응을 하지 않아, 시뮬레이션이 도는지 멈췄는지 구분할 수 없다.
     * 값 없이 target 이름만 모아 보내 깜빡임만 일으킨다. 건당 90바이트 남짓인 변경 본문 대신
     * 9바이트짜리 문자열 하나라 3400건을 10초마다 써도 3KB/s 다.
     * <p>
     * 한 값이 차지하는 칸들을 묶음으로 유지한다 (키는 그 값의 base target). 3_0 에 쓴 UI32AD 는
     * 3_0 과 3_1 을 함께 덮으므로, 화면이 두 칸을 하나로 감싸 표시하려면 어디까지가 한 값인지
     * 알아야 한다. 평평한 목록으로 보내면 이웃한 별개의 값끼리 붙어 버린다.
     */
    private final Map<Integer, Map<String, List<String>>> pendingTouched = new ConcurrentHashMap<>();

    /**
     * 이 쓰기가 덮는 칸 모두가 마지막으로 내보낸 값·출처 그대로인지. 비교하면서 기록도 새로 한다.
     * <p>
     * base target 하나로만 비교하면 안 된다. 3_0 의 UI32 simulation 은 3_0, 3_1 을 함께 쓰는데,
     * 그 사이 Test Plan 이 3_1 만 API_WRITE 로 썼다가 lock 이 풀리면 simulation 이 3_1 을 되돌린다.
     * 3_0 의 값·출처는 그대로라 "변경 없음" 으로 걸러져, 화면의 3_1 은 API_WRITE 색으로 남았다.
     */
    static boolean unchangedSinceLastSent(Map<String, String> sentOfDevice, ServerDataEvent event) {
        String source = event.getSource().name();
        Map<String, Number> values = event.getTargetValues();
        boolean unchanged = true;
        for (String cell : event.getAffectedTargets()) {
            Number v = values.get(cell);
            // 값을 칸 단위로 읽을 수 없는 프로토콜(SNMP 등)은 base target 에 쓴 값으로 본다
            Object shown = v != null ? v : (cell.equals(event.getTarget()) ? event.getValue() : null);
            String fingerprint = shown + "|" + source;
            if (!fingerprint.equals(sentOfDevice.put(cell, fingerprint))) unchanged = false;
        }
        return unchanged;
    }

    @Override
    public void onServerDataChanged(ServerDataEvent event) {
        int deviceId = event.getIoServer().getDevice().getId();
        if (!hasSubscriber(deviceId)) return;

        Map<String, String> sentOfDevice =
                lastSent.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>());
        List<String> affected = event.getAffectedTargets();
        boolean spansSeveral = affected != null && affected.size() > 1;

        if (unchangedSinceLastSent(sentOfDevice, event)) {
            // 값도 출처도 그대로다. 다시 그릴 것은 없지만 "썼다" 는 사실은 알려 준다.
            // 32비트처럼 여러 칸을 차지하는 값은 그 칸들이 모두 깜빡여야 한다. 3_0 에 쓴
            // UI32AD 는 3_0 과 3_1 을 함께 덮으므로 한 칸만 깜빡이면 범위가 거짓이 된다.
            pendingTouched
                    .computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>())
                    .put(event.getTarget(),
                         spansSeveral ? new ArrayList<>(affected)
                                      : Collections.singletonList(event.getTarget()));
            return;
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("target", event.getTarget());
        msg.put("value", event.getValue());
        msg.put("source", event.getSource().name());
        msg.put("ts", event.getTimestamp());
        // 한 칸짜리 변경은 target/value 만으로 충분하다. 32비트처럼 여러 칸을 건드릴 때만 싣는다.
        if (spansSeveral) {
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

    /**
     * lock 이 바뀌면 묶음 주기를 기다리지 않고 바로 보낸다. 그 장비에 모아 둔 값 변경도 함께 내보낸다 —
     * API 쓰기는 값을 쓰고 나서 lock 알림을 내므로, 방금 쓴 값과 lock 이 한 메시지로 같이 바뀐다.
     * lock 은 쓰기·정지·해제·세션 종료 때 그 자리에서, 만료는 만료 시각에 온다(ServerData.notifyLocksIfChanged).
     */
    @Override
    public void onLocksChanged(IOServer ioServer, Set<String> locks) {
        int deviceId = ioServer.getDevice().getId();
        if (!hasSubscriber(deviceId)) return;
        List<String> sorted = new ArrayList<>(locks);
        Collections.sort(sorted);
        flushDevice(deviceId, sorted);
    }

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flush() {
        Set<Integer> deviceIds = new LinkedHashSet<>(pending.keySet());
        deviceIds.addAll(pendingTouched.keySet());
        for (Integer deviceId : deviceIds) flushDevice(deviceId, null);
    }

    /**
     * 한 장비의 모아 둔 변경을 보낸다. 주기 flush 와 lock 알림이 서로 다른 스레드에서 부르므로 한 번에 하나씩 —
     * 그러지 않으면 먼저 꺼낸 묶음이 나중에 나가 화면의 값이 과거로 돌아갈 수 있다.
     *
     * @param locks 지금 잠긴 칸 전체. lock 이 바뀌지 않았으면 null
     */
    private synchronized void flushDevice(int deviceId, List<String> locks) {
        List<Map<String, Object>> changes = drainChanges(deviceId);
        List<List<String>> touched = drainTouched(deviceId);
        if (!hasSubscriber(deviceId)) return;   // 모으는 사이에 창이 닫혔다
        if (changes.isEmpty() && touched.isEmpty() && locks == null) return;

        // changes 가 비어도 한 번은 보낸다. touched 만 있는 묶음이 STATIC 장비의 평상시다.
        int slices = Math.max(1, (changes.size() + MAX_CHANGES_PER_MESSAGE - 1) / MAX_CHANGES_PER_MESSAGE);
        for (int i = 0; i < slices; i++) {
            int from = i * MAX_CHANGES_PER_MESSAGE;
            List<Map<String, Object>> slice = changes.isEmpty()
                    ? Collections.emptyList()
                    : changes.subList(from, Math.min(from + MAX_CHANGES_PER_MESSAGE, changes.size()));
            Map<String, Object> batch = new LinkedHashMap<>();
            batch.put("type", "events");
            batch.put("ts", System.currentTimeMillis());
            // 스냅샷만 번호를 올리면 30초에 한 번씩만 움직여, 화면이 갱신되고 있는지
            // 알기 어렵다. 시뮬레이션이 쓴 묶음도 같은 카운터를 올린다.
            sessionRegistry.getActive(deviceId)
                    .ifPresent(info -> batch.put("seq", info.seq.incrementAndGet()));
            batch.put("changes", new ArrayList<>(slice));
            if (i == 0 && !touched.isEmpty()) batch.put("touched", touched);
            // 바뀐 칸만이 아니라 지금 잠긴 칸 전체. 화면은 이걸로 통째로 바꾼다
            if (i == 0 && locks != null) batch.put("locks", locks);
            messagingTemplate.convertAndSend(
                    "/topic/devices/" + deviceId + "/serverdata/events", batch);
        }
    }

    private List<Map<String, Object>> drainChanges(int deviceId) {
        Map<String, Map<String, Object>> byTarget = pending.get(deviceId);
        if (byTarget == null) return Collections.emptyList();
        synchronized (byTarget) {
            if (byTarget.isEmpty()) return Collections.emptyList();
            List<Map<String, Object>> changes = new ArrayList<>(byTarget.values());
            byTarget.clear();
            return changes;
        }
    }

    /**
     * 깜빡임만 필요한 target 들을 꺼낸다.
     * 한 묶음에 너무 많으면 문자열만으로도 메시지가 커지므로 잘라 낸다. 잘린 것은 버린다 —
     * 다음 주기에 다시 쓰이면 그때 깜빡인다.
     */
    private List<List<String>> drainTouched(int deviceId) {
        Map<String, List<String>> groups = pendingTouched.get(deviceId);
        if (groups == null || groups.isEmpty()) return Collections.emptyList();
        List<List<String>> all = new ArrayList<>(groups.values());
        groups.clear();
        return all.size() <= MAX_TOUCHED_PER_MESSAGE
                ? all : new ArrayList<>(all.subList(0, MAX_TOUCHED_PER_MESSAGE));
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
        // 새 화면은 출처를 하나도 모른다 — 스냅샷에는 값만 있다. "보낸 적 있음" 기록을 지워
        // 칸마다 다음 쓰기에 본문(값·출처)이 다시 실리게 한다. 지우지 않으면 STATIC 처럼 같은 값을
        // 다시 쓰는 칸은 깜빡임만 받아, 다른 화면이 먼저 열려 있던 장비에서 색이 끝내 칠해지지 않았다.
        lastSent.remove(deviceId);
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
                pendingTouched.remove(deviceId);
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
