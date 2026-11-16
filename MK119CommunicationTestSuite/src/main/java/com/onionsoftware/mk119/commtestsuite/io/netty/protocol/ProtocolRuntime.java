package com.onionsoftware.mk119.commtestsuite.io.netty.protocol;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.ProtocolDefinitionService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.protocol.DefinitionRegistry;
import com.onionsoftware.mk119.commtestsuite.protocol.ProtocolEngine;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 장비 하나의 엔진을 만든다 (docs/PROTOCOL_ENGINE_DESIGN.md 10절).
 * 정의는 요청마다 Registry 에서 읽으므로 정의를 저장하면 다음 요청부터 새 판이다.
 * 장비에 정의가 없으면 {@link #engineFor} 가 null 을 준다.
 */
public final class ProtocolRuntime {
    private ProtocolRuntime() {}

    public static ProtocolEngine engineFor(IOServer server, DefinitionRegistry registry, SimulationService simulationService) {
        Device d = server.getDevice();
        Integer id = ProtocolDefinitionService.definitionIdOf(d);
        if (id == null || registry == null) return null;
        return new ProtocolEngine(() -> registry.get(id), paramsOf(d), new DeviceStore(server, simulationService));
    }

    /** 장비 설정의 params. 정의에 없는 이름은 엔진이 쓰지 않는다 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> paramsOf(Device d) {
        Object p = d.getCommunicationSettings() == null ? null : d.getCommunicationSettings().get("params");
        if (!(p instanceof Map)) return Collections.emptyMap();
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<Object, Object> e : ((Map<Object, Object>) p).entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) {
                String s = ((String) v).trim();
                try {
                    v = s.matches("[+-]?\\d+") ? (Object) Long.parseLong(s) : (Object) Double.parseDouble(s);
                } catch (NumberFormatException ex) {
                    continue;   // 빈 칸 = 기본값
                }
            }
            if (v instanceof Number) out.put(String.valueOf(e.getKey()), v);
        }
        return out;
    }

    /**
     * 엔진의 저장소 창구 = 장비의 ServerData.
     * 쓰기는 PROTOCOL_WRITE 로 들어가 지금의 잠금 규칙을 따르고, 쓴 타겟에 Simulation 이 있으면 stopOnControl 을 적용한다.
     */
    static final class DeviceStore implements ProtocolEngine.Store {
        private final IOServer server;
        private final SimulationService simulationService;

        DeviceStore(IOServer server, SimulationService simulationService) {
            this.server = server;
            this.simulationService = simulationService;
        }

        @Override
        public Double get(String target) {
            Number v = server.getServerData().readValueAt(target);
            return v == null ? null : v.doubleValue();
        }

        @Override
        public boolean write(String target, double value, DataType dataType) {
            ServerData sd = server.getServerData();
            try {
                boolean ok = sd.setServerData(target, value, dataType, ServerDataChangeSource.PROTOCOL_WRITE);
                if (ok && simulationService != null) {
                    Simulation sim = sd.simulationFor(target);
                    if (sim != null) simulationService.applyStopOnControl(sim);
                }
                return ok;
            } catch (Exception e) {
                LoggerFactory.getLogger("io").error("Device {} protocol write to \"{}\" failed", server.getDevice().getName(), target, e);
                return false;
            }
        }
    }
}
