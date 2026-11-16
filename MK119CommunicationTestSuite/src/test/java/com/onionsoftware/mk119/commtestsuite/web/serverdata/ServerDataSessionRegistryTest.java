package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServerDataSessionRegistryTest {

    private static final int DEVICE = 7;

    private ServerDataSessionRegistry registry;
    private ServerData serverData;

    @BeforeEach
    void setUp() {
        registry = new ServerDataSessionRegistry();
        serverData = mock(ServerData.class);
        IOServer server = mock(IOServer.class);
        when(server.getServerData()).thenReturn(serverData);
        IOServerInstances instances = mock(IOServerInstances.class);
        when(instances.getServerInstance(DEVICE)).thenReturn(Optional.of(server));
        ReflectionTestUtils.setField(registry, "ioServerInstances", instances);
        ReflectionTestUtils.setField(registry, "messagingTemplate", mock(SimpMessagingTemplate.class));
    }

    private void multiSession(boolean enabled) {
        ReflectionTestUtils.setField(registry, "multiSessionEnabled", enabled);
    }

    private void disconnect(String wsSessionId) {
        Message<byte[]> msg = MessageBuilder.withPayload(new byte[0]).build();
        registry.onDisconnect(new SessionDisconnectEvent(new Object(), msg, wsSessionId, CloseStatus.NORMAL));
    }

    @Test
    void singleModeRefusesASecondEditor() {
        multiSession(false);
        assertTrue(registry.tryEnter(DEVICE, "ws-a", "A").isPresent());
        assertFalse(registry.tryEnter(DEVICE, "ws-b", "B").isPresent());
        assertTrue(registry.isActiveEditor(DEVICE, "A"));
        assertFalse(registry.isActiveEditor(DEVICE, "B"));
    }

    @Test
    void sameUserReentersTheSameSession() {
        multiSession(false);
        ServerDataSessionRegistry.SessionInfo first = registry.tryEnter(DEVICE, "ws-a", "A").get();
        assertSame(first, registry.tryEnter(DEVICE, "ws-a", "A").get());
        assertEquals(1, registry.getActiveSessions(DEVICE).size());
    }

    @Test
    void multiModeAdmitsEveryEditor() {
        multiSession(true);
        assertTrue(registry.tryEnter(DEVICE, "ws-a", "A").isPresent());
        assertTrue(registry.tryEnter(DEVICE, "ws-b", "B").isPresent());
        assertTrue(registry.tryEnter(DEVICE, "ws-c", "C").isPresent());
        assertEquals(3, registry.getActiveSessions(DEVICE).size());
        assertTrue(registry.isActiveEditor(DEVICE, "B"));
    }

    /** 다른 편집자가 남아 있으면 그쪽 lock 일 수 있으므로 풀지 않는다. 마지막이 나갈 때 푼다. */
    @Test
    void permanentLocksAreClearedOnlyWhenTheLastSessionLeaves() {
        multiSession(true);
        registry.tryEnter(DEVICE, "ws-a", "A");
        registry.tryEnter(DEVICE, "ws-b", "B");

        disconnect("ws-a");
        verify(serverData, never()).clearPermanentLocks();
        assertTrue(registry.hasActiveSession(DEVICE));

        disconnect("ws-b");
        verify(serverData, times(1)).clearPermanentLocks();
        assertFalse(registry.hasActiveSession(DEVICE));
    }

    @Test
    void singleModeClearsLocksWhenTheEditorLeaves() {
        multiSession(false);
        registry.tryEnter(DEVICE, "ws-a", "A");
        disconnect("ws-a");
        verify(serverData, times(1)).clearPermanentLocks();
        assertTrue(registry.tryEnter(DEVICE, "ws-b", "B").isPresent());
    }

    @Test
    void singleModeGrantsOnlyOneOfConcurrentEnters() throws Exception {
        multiSession(false);
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = "U" + i;
            results.add(pool.submit(() -> {
                start.await();
                return registry.tryEnter(DEVICE, "ws-" + id, id).isPresent();
            }));
        }
        start.countDown();
        int granted = 0;
        for (Future<Boolean> f : results) if (f.get(5, TimeUnit.SECONDS)) granted++;
        pool.shutdownNow();
        assertEquals(1, granted);
        assertEquals(1, registry.getActiveSessions(DEVICE).size());
    }

    /** 새로고침: 이전 연결이 끊기는 것과 새 연결의 진입이 엇갈려도 새 세션은 보이는 상태로 남는다. */
    @Test
    void reenterRacingWithDisconnectLeavesTheNewSessionVisible() throws Exception {
        multiSession(false);
        for (int round = 0; round < 200; round++) {
            String oldWs = "old-" + round, newWs = "new-" + round;
            String oldUser = "O" + round, newUser = "N" + round;
            assertTrue(registry.tryEnter(DEVICE, oldWs, oldUser).isPresent());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> leave = pool.submit(() -> { start.await(); disconnect(oldWs); return null; });
            Future<Boolean> enter = pool.submit(() -> { start.await(); return registry.tryEnter(DEVICE, newWs, newUser).isPresent(); });
            start.countDown();
            leave.get(5, TimeUnit.SECONDS);
            boolean granted = enter.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();

            // 거절됐으면(이전 세션이 아직 있었으면) 다시 시도하는 것이 화면의 Retry 와 같다
            if (!granted) assertTrue(registry.tryEnter(DEVICE, newWs, newUser).isPresent());
            assertTrue(registry.isActiveEditor(DEVICE, newUser), "round " + round);
            assertTrue(registry.hasActiveSession(DEVICE), "round " + round);

            disconnect(newWs);
            assertFalse(registry.hasActiveSession(DEVICE));
        }
    }

    @Test
    void seqIsSharedPerDevice() {
        assertEquals(1, registry.nextSeq(DEVICE));
        assertEquals(2, registry.nextSeq(DEVICE));
        assertEquals(1, registry.nextSeq(DEVICE + 1));
    }
}
