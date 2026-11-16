package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 변경 없음 판정은 쓰기가 덮는 칸마다 한다. base target 하나로 보면 다른 칸의 출처가 화면에 남는다. */
class ServerDataBridgeTest {

    private static ServerDataEvent event(ServerDataChangeSource source, String target, double value, Object... cellValues) {
        Map<String, Number> values = new LinkedHashMap<>();
        for (int i = 0; i < cellValues.length; i += 2) values.put((String) cellValues[i], (Number) cellValues[i + 1]);
        return new ServerDataEvent(null, source, target, value,
                Arrays.asList(values.keySet().toArray(new String[0])), values, null);
    }

    @Test
    void sameValueAndSourceIsUnchanged() {
        Map<String, String> sent = new HashMap<>();
        assertFalse(ServerDataBridge.unchangedSinceLastSent(sent,
                event(ServerDataChangeSource.VALUE_SIMULATION, "3_0", 501138, "3_0", 42386, "3_1", 7)));
        assertTrue(ServerDataBridge.unchangedSinceLastSent(sent,
                event(ServerDataChangeSource.VALUE_SIMULATION, "3_0", 501138, "3_0", 42386, "3_1", 7)));
    }

    @Test
    void simulationRestoringASecondRegisterWrittenByTheApiIsAChange() {
        Map<String, String> sent = new HashMap<>();
        ServerDataBridge.unchangedSinceLastSent(sent,
                event(ServerDataChangeSource.VALUE_SIMULATION, "3_0", 501138, "3_0", 42386, "3_1", 7));
        // Test Plan 이 3_1 만 쓴다
        assertFalse(ServerDataBridge.unchangedSinceLastSent(sent,
                event(ServerDataChangeSource.API_WRITE, "3_1", 10, "3_1", 10)));
        // lock 이 풀린 뒤 simulation 이 같은 값을 다시 쓴다. 3_0 은 그대로지만 3_1 의 값·출처가 바뀐다
        assertFalse(ServerDataBridge.unchangedSinceLastSent(sent,
                event(ServerDataChangeSource.VALUE_SIMULATION, "3_0", 501138, "3_0", 42386, "3_1", 7)));
        assertTrue(ServerDataBridge.unchangedSinceLastSent(sent,
                event(ServerDataChangeSource.VALUE_SIMULATION, "3_0", 501138, "3_0", 42386, "3_1", 7)));
    }

    private static SessionSubscribeEvent subscribe(String session, int deviceId) {
        StompHeaderAccessor h = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        h.setSessionId(session);
        h.setSubscriptionId("sub-0");
        h.setDestination("/topic/devices/" + deviceId + "/serverdata/events");
        return new SessionSubscribeEvent(new Object(), MessageBuilder.createMessage(new byte[0], h.getMessageHeaders()));
    }

    /** 다른 화면이 먼저 열려 있어도, 새로 들어온 화면은 같은 값을 다시 쓰는 칸의 출처를 받는다. */
    @Test
    @SuppressWarnings("unchecked")
    void aNewSubscriberGetsTheSourceOfCellsThatKeepTheSameValue() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        ServerDataSessionRegistry registry = mock(ServerDataSessionRegistry.class);
        ServerDataBridge bridge = new ServerDataBridge();
        ReflectionTestUtils.setField(bridge, "messagingTemplate", template);
        ReflectionTestUtils.setField(bridge, "sessionRegistry", registry);
        Device device = new Device();
        device.setId(34);
        IOServer server = mock(IOServer.class);
        when(server.getDevice()).thenReturn(device);
        ServerDataEvent staticWrite = new ServerDataEvent(server, ServerDataChangeSource.VALUE_SIMULATION, "3_10", 5,
                Collections.singletonList("3_10"), Collections.singletonMap("3_10", 5), null);

        bridge.onSubscribe(subscribe("A", 34));
        bridge.onServerDataChanged(staticWrite);
        bridge.flush();                            // 본문
        bridge.onServerDataChanged(staticWrite);
        bridge.flush();                            // 같은 값 — 깜빡임만
        bridge.onSubscribe(subscribe("B", 34));    // A 가 열린 채 B 가 들어온다
        bridge.onServerDataChanged(staticWrite);
        bridge.flush();

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(template, times(3)).convertAndSend(eq("/topic/devices/34/serverdata/events"), sent.capture());
        List<Integer> bodies = new ArrayList<>();
        for (Object m : sent.getAllValues()) bodies.add(((List<?>) ((Map<String, Object>) m).get("changes")).size());
        assertEquals(Arrays.asList(1, 0, 1), bodies);   // B 가 들어온 뒤의 쓰기는 다시 본문(출처)을 싣는다
    }

    @Test
    void sameValueFromAnotherSourceIsAChange() {
        Map<String, String> sent = new HashMap<>();
        ServerDataBridge.unchangedSinceLastSent(sent, event(ServerDataChangeSource.VALUE_SIMULATION, "3_5", 1, "3_5", 1));
        assertFalse(ServerDataBridge.unchangedSinceLastSent(sent, event(ServerDataChangeSource.API_WRITE, "3_5", 1, "3_5", 1)));
    }
}
