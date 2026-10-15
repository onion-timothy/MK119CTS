package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataEvent;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ServerData 변경 이벤트를 STOMP 토픽으로 브로드캐스트. 각 IOServer 에 리스너로 부착된다.
 * 편집 세션 유무와 무관하게 항상 발행하되, 구독자가 없으면 브로커가 자연스럽게 폐기한다.
 */
@Component
public class ServerDataBridge implements ServerDataListener {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Override
    public void onServerDataChanged(ServerDataEvent event) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("target", event.getTarget());
        msg.put("value", event.getValue());
        msg.put("source", event.getSource().name());
        msg.put("ts", event.getTimestamp());
        msg.put("affectedTargets", event.getAffectedTargets());
        msg.put("targetValues", event.getTargetValues());
        if (event.getSimulation() != null) {
            msg.put("simulationId", event.getSimulation().getId());
        }
        int deviceId = event.getIoServer().getDevice().getId();
        messagingTemplate.convertAndSend("/topic/devices/" + deviceId + "/serverdata/events", msg);
    }
}
