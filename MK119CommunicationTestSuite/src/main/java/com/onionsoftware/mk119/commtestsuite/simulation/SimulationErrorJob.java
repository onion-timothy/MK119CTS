package com.onionsoftware.mk119.commtestsuite.simulation;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.io.db.DBDeviceService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import lombok.RequiredArgsConstructor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * SIMULATION 모드 Error를 자기 자신의 Error.interval로 독립 스케줄링 실행하는 Job.
 * 시스템 에러(ErrorJob)와 달리 IOServer 플래그를 켜는 대신 ServerData/SQL 에 errorValue를 밀어넣는다.
 */
@RequiredArgsConstructor
public class SimulationErrorJob implements Runnable {
    private final Error error;
    private final IOServerInstances ioServerInstances;
    private final DBDeviceService dbDeviceService;
    private final SimulationService simulationService;

    @Override
    public void run() {
        // Gate: 필수 조건이 하나라도 어긋나면 롤 없이 종료
        if (error.getMode() != ErrorMode.SIMULATION
                || error.getEnabled() != Error.ERROR_ENABLED
                || error.getErrorValue() == null
                || error.getSimulation() == null) {
            return;
        }

        if (!error.shouldFire()) return;

        Simulation simulation = error.getSimulation();
        double errorValue = error.getErrorValue();
        Protocol protocol = simulation.getDevice().getProtocol();

        boolean applied;
        if (protocol == Protocol.DB) {
            applied = applyToDb(simulation, errorValue);
        } else if (protocol == Protocol.BACNET || Protocol.NETTY_PROTOCOLS.contains(protocol)) {
            applied = applyToServerData(simulation, errorValue);
        } else {
            LoggerFactory.getLogger("simulation").warn("SimulationErrorJob: unsupported protocol {} for error {}", protocol, error.getId());
            return;
        }

        // 쓰지 못했으면 발생하지 않은 것이다. 로그도 정지도 남기지 않는다.
        if (!applied) return;

        LoggerFactory.getLogger("simulation").info("Simulation error occurred on device {}({}) target {} with value {}", simulation.getDevice().getName(), simulation.getDevice().getId(), simulation.getTarget(), errorValue);

        // simulation_stop 은 쓰기 보호로 건다. API write 의 lock 과 같은 수단이라
        // 정지 중에는 ACCUMULATE 누적과 SEQUENCE 위치도 나아가지 않는다.
        // 0 이하는 "정지 없음" 이다 — 음수 동작은 정의하지 않는다.
        // DB 장비는 ServerData 가 없어 걸 대상이 없으므로 그대로 넘어간다.
        int simStop = error.getSimulation_stop() == null ? 0 : error.getSimulation_stop();
        if (simStop > 0 && protocol != Protocol.DB) {
            simulationService.stopSimulationFor(simulation.getDevice(), simulation, simStop);
            LoggerFactory.getLogger("simulation").info("Simulation stopped for device {}({}) target {} for {}s.", simulation.getDevice().getName(), simulation.getDevice().getId(), simulation.getTarget(), simStop);
        }
    }

    /**
     * ServerData 에 에러 값을 쓴다.
     * <p>
     * 사용자가 Server Data 화면에서 건 잠금이 최우선이다. 거기에 막히면 쓰기는 실패하고,
     * 그 사실을 남긴다. 예전에는 막혀도 성공으로 보고 "에러 발생" 로그와 정지를 함께 남겨
     * 쓰지도 못한 에러가 시뮬레이션을 멈춰 세웠다.
     */
    private boolean applyToServerData(Simulation simulation, double value) {
        return ioServerInstances.getServerInstance(simulation.getDevice().getId())
                .map(server -> {
                    ServerData serverData = server.getServerData();
                    if (serverData == null) return false;
                    try {
                        if (serverData.setServerData(simulation, value,
                                ServerDataChangeSource.ERROR_SIMULATION)) {
                            return true;
                        }
                        LoggerFactory.getLogger("simulation").warn("Simulation error rejected on device {}({}) target {}: "
                                        + "the target is locked. The error value was not written.", simulation.getDevice().getName(), simulation.getDevice().getId(), simulation.getTarget());
                        return false;
                    } catch (Exception e) {
                        LoggerFactory.getLogger("simulation").error("SimulationErrorJob setServerData failed on device {}({}) target {}", simulation.getDevice().getName(), simulation.getDevice().getId(), simulation.getTarget(), e);
                        return false;
                    }
                }).orElse(false);
    }

    private boolean applyToDb(Simulation simulation, double value) {
        Connection connection = dbDeviceService.getConnection(simulation.getDevice());
        if (connection == null) {
            LoggerFactory.getLogger("simulation").warn("SimulationErrorJob: no DB connection for device {}", simulation.getDevice().getName());
            return false;
        }
        String replaced = simulation.getTarget();
        for (SimulationPolicy policy : simulation.getPolicies()) {
            replaced = replaced.replaceAll("\\$\\{" + policy.getId() + "}", Double.toString(value));
        }
        try {
            PreparedStatement pstmt = connection.prepareStatement(replaced);
            int res = pstmt.executeUpdate();
            if (res == 0) {
                LoggerFactory.getLogger("simulation").warn("SimulationErrorJob DB device {} target {} has no effect.", simulation.getDevice().getName(), simulation.getTarget());
            }
            return true;
        } catch (SQLException e) {
            LoggerFactory.getLogger("simulation").error("SimulationErrorJob DB device {} SQL error : {}", simulation.getDevice().getName(), simulation.getTarget(), e);
            return false;
        }
    }
}
