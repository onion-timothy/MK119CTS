package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public class ServerDataEvent {
    private final IOServer ioServer;
    private final ServerDataChangeSource source;
    private final String target;
    private final double value;
    /**
     * 이 쓰기 동작이 영향을 준 전체 target 목록. 단일 셀 쓰기라면 {@link #target} 하나.
     * 예: Modbus UI32 는 2개 레지스터를 함께 씀 → ["3_10", "3_11"].
     */
    private final List<String> affectedTargets;
    /**
     * 각 target 의 실제 반영된 값. FP32/UI32 등 여러 register 를 나눠 쓰는 경우
     * client 가 raw double 이 아닌 register 별 정확한 값으로 표시하도록 한다.
     */
    private final Map<String, Number> targetValues;
    /** VALUE_SIMULATION / ERROR_SIMULATION 일 때 유효. PROTOCOL_WRITE 일 땐 null 가능. */
    private final Simulation simulation;
    private final long timestamp;

    public ServerDataEvent(IOServer ioServer, ServerDataChangeSource source,
                           String target, double value, Simulation simulation) {
        this(ioServer, source, target, value, Collections.singletonList(target), null, simulation);
    }

    public ServerDataEvent(IOServer ioServer, ServerDataChangeSource source,
                           String target, double value, List<String> affectedTargets,
                           Map<String, Number> targetValues, Simulation simulation) {
        this.ioServer = ioServer;
        this.source = source;
        this.target = target;
        this.value = value;
        this.affectedTargets = affectedTargets == null || affectedTargets.isEmpty()
                ? Collections.singletonList(target)
                : affectedTargets;
        this.targetValues = targetValues == null ? new LinkedHashMap<>() : targetValues;
        this.simulation = simulation;
        this.timestamp = System.currentTimeMillis();
    }
}
