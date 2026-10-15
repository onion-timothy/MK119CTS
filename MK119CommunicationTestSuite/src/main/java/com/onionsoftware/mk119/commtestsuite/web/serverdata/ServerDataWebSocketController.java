package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import com.onionsoftware.mk119.commtestsuite.web.bean.ServerDataSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ServerData 편집 WebSocket 컨트롤러. enter/write/ack 처리 + 주기적 스냅샷 push.
 * 세션 식별자로 Principal 이름(Handshake 시 UUID 부여) 을 사용한다.
 */
@Controller
public class ServerDataWebSocketController {

    /**
     * 주기적 스냅샷 push 간격 (ms).
     * 개별 target 변경은 이벤트 스트림으로 이미 전달되므로 스냅샷은 “주기적 재동기화 + liveness 신호”
     * 목적으로만 사용한다. 너무 짧으면 클라 렌더가 밀리고 ack 도 밀린다.
     */
    public static final long SNAPSHOT_INTERVAL_MS = 5_000L;

    @Autowired
    private ServerDataSessionRegistry sessionRegistry;

    @Autowired
    private IOServerInstances ioServerInstances;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/devices/{deviceId}/serverdata/enter")
    public void enter(@DestinationVariable int deviceId, SimpMessageHeaderAccessor headers) {
        String wsSessionId = headers.getSessionId();
        Principal principal = headers.getUser();
        String user = ServerDataSessionRegistry.userOf(principal);
        MkLogger.log("serverdata", LogLevel.INFO,
                "ENTER received: device={0}, user={1}, wsSession={2}", deviceId, user, wsSessionId);
        if (user == null) {
            MkLogger.log("serverdata", LogLevel.WARNING,
                    "ENTER without Principal (HandshakeHandler misconfigured?)");
            return;
        }
        Optional<IOServer> serverOpt = ioServerInstances.getServerInstance(deviceId);
        if (!serverOpt.isPresent() || serverOpt.get().getServerData() == null) {
            replyToUser(user, statusMsg("denied", "device_not_available"));
            return;
        }
        Optional<ServerDataSessionRegistry.SessionInfo> claimed =
                sessionRegistry.tryEnter(deviceId, wsSessionId, user);
        if (!claimed.isPresent()) {
            replyToUser(user, statusMsg("denied", "another_session_active"));
            return;
        }
        replyToUser(user, statusMsg("granted", null));
        pushSnapshot(deviceId, claimed.get());
    }

    // ------------------------------------------------------------------
    // WebSocket write path — INTENTIONALLY DISABLED.
    // All ServerData writes now go through REST (POST /api/devices/{id}/serverdata).
    // The REST handler fires the same change event, so this WS instance still
    // broadcasts the update to subscribers on /topic/devices/{id}/serverdata/events.
    // Preserved as reference in case we ever want to re-enable a WS-side write channel.
    // ------------------------------------------------------------------
    /*
    @MessageMapping("/devices/{deviceId}/serverdata/write")
    public void write(@DestinationVariable int deviceId,
                      @Payload WriteRequest req,
                      SimpMessageHeaderAccessor headers) {
        String user = ServerDataSessionRegistry.userOf(headers.getUser());
        if (user == null || !sessionRegistry.isActiveEditor(deviceId, user)) return;
        sessionRegistry.touchByUser(user);

        Optional<IOServer> serverOpt = ioServerInstances.getServerInstance(deviceId);
        if (!serverOpt.isPresent()) { replyWriteError(user, req, "device_not_available"); return; }
        ServerData sd = serverOpt.get().getServerData();
        if (sd == null) { replyWriteError(user, req, "server_data_not_available"); return; }

        try {
            DataType dt = (req.dataType == null || req.dataType.isEmpty()) ? null : DataType.valueOf(req.dataType);
            sd.setServerData(req.target, req.value, dt, ServerDataChangeSource.API_WRITE);
            java.util.List<String> affected = sd.computeAffectedTargets(req.target, dt);
            Map<String, Object> targetValues = new LinkedHashMap<>();
            for (String t : affected) {
                Number v = sd.readValueAt(t);
                if (v != null) targetValues.put(t, v);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("type", "write_ack");
            resp.put("ok", true);
            resp.put("reqId", req.reqId);
            resp.put("target", req.target);
            resp.put("value", req.value);
            resp.put("dataType", req.dataType);
            resp.put("source", ServerDataChangeSource.API_WRITE.name());
            resp.put("affectedTargets", affected);
            resp.put("targetValues", targetValues);
            resp.put("ts", System.currentTimeMillis());
            replyToUser(user, resp);
        } catch (Exception e) {
            MkLogger.log("serverdata", e,
                    "API_WRITE failed: device={0}, target={1}, value={2}, dataType={3}",
                    deviceId, req.target, req.value, req.dataType);
            replyWriteError(user, req, e.getMessage());
        }
    }

    private void replyWriteError(String user, WriteRequest req, String error) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("type", "write_ack");
        resp.put("ok", false);
        resp.put("reqId", req == null ? null : req.reqId);
        resp.put("target", req == null ? null : req.target);
        resp.put("error", error);
        resp.put("ts", System.currentTimeMillis());
        replyToUser(user, resp);
    }
    */

    @MessageMapping("/devices/{deviceId}/serverdata/ack")
    public void ack(@DestinationVariable int deviceId,
                    SimpMessageHeaderAccessor headers) {
        String user = ServerDataSessionRegistry.userOf(headers.getUser());
        String activeUser = sessionRegistry.getActive(deviceId).map(i -> i.user).orElse("<none>");
        MkLogger.log("serverdata", LogLevel.INFO,
                "ACK received: device={0}, user={1}, activeUser={2}, wsSession={3}",
                deviceId, user, activeUser, headers.getSessionId());
        if (user == null || !sessionRegistry.isActiveEditor(deviceId, user)) return;
        sessionRegistry.touchByUser(user);
    }

    @Scheduled(fixedDelay = SNAPSHOT_INTERVAL_MS)
    public void pushSnapshotsToActiveSessions() {
        for (Map.Entry<Integer, IOServer> e : ioServerInstances.getServers().entrySet()) {
            int deviceId = e.getKey();
            sessionRegistry.getActive(deviceId).ifPresent(info -> pushSnapshot(deviceId, info));
        }
    }

    private void pushSnapshot(int deviceId, ServerDataSessionRegistry.SessionInfo info) {
        Optional<IOServer> serverOpt = ioServerInstances.getServerInstance(deviceId);
        if (!serverOpt.isPresent() || serverOpt.get().getServerData() == null) return;
        long seq = info.seq.incrementAndGet();
        ServerDataSnapshot snap = ServerDataSnapshot.of(seq, serverOpt.get().getServerData());
        MkLogger.log("serverdata", LogLevel.INFO,
                "SNAPSHOT push: device={0}, user={1}, seq={2}, lastAckAgo={3}ms",
                deviceId, info.user, seq, System.currentTimeMillis() - info.lastAckTs);
        replyToUser(info.user, snap);
    }

    private Map<String, Object> statusMsg(String status, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "status");
        m.put("status", status);
        if (reason != null) m.put("reason", reason);
        return m;
    }

    private void replyToUser(String user, Object payload) {
        messagingTemplate.convertAndSendToUser(user, "/queue/serverdata/status", payload);
    }

    // WriteRequest DTO — kept commented alongside the disabled write() handler above.
    /*
    public static class WriteRequest {
        public String target;
        public double value;
        public String dataType;
        public String reqId;
    }
    */

    public static class AckRequest {
        public long seq;
    }
}
