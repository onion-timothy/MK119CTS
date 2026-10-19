package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.repository.SimulationRepository;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerData;
import com.onionsoftware.mk119.commtestsuite.simulation.GroupedSimulationJob;
import com.onionsoftware.mk119.commtestsuite.simulation.JitteredTrigger;
import com.onionsoftware.mk119.commtestsuite.simulation.SimulationJobFactory;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.persistence.EntityNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Service
@RequiredArgsConstructor
public class SimulationService {
    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger("simulation");

    private final SimulationRepository simulationRepository;
    private final ThreadPoolTaskScheduler threadPoolTaskScheduler;
    private final DeviceService deviceService;
    private final SimulationJobFactory simulationJobFactory;
    private final BACnetService bacnetService;
    /** stopOnControl 의 시간 정지를 ServerData lock 으로 걸기 위해 필요하다. */
    private final IOServerInstances ioServerInstances;

    /** Key: "deviceId_interval" (예: "5_3") */
    private final Map<String, GroupedSimulationJob> groupedJobs = new HashMap<>();
    /** Key: "deviceId_interval" */
    /** Key: "deviceId_interval" */
    private final Map<String, ScheduledFuture<?>> groupedSchedules = new HashMap<>();

    public List<Simulation> selectSimulationsByDeviceId(Integer id) {
        return simulationRepository.findSimulationsByDeviceId(id);
    }

    @PostConstruct
    public void init() {
        MkLogger.log("simulation", LogLevel.INFO, "ThreadPoolTaskScheduler pool size: {0}",
                threadPoolTaskScheduler.getPoolSize());
    }

    /**
     * Simulation을 시작한다. 동일 Device+Interval 그룹이 있으면 해당 그룹에 추가하고,
     * 없으면 새 그룹을 생성하여 스케줄에 등록한다.
     */
    public void startSimulation(Device device, Simulation simulation) {
        if (simulation.getEnabled() != Simulation.ENABLED)
            return;

        String groupKey = GroupedSimulationJob.makeGroupKey(device.getId(), simulation.getInterval());

        synchronized (groupedJobs) {
            GroupedSimulationJob groupJob = groupedJobs.get(groupKey);
            if (groupJob != null) {
                // 중복 검사는 addSimulation 내부의 id 집합이 O(1) 로 처리한다.
                // 대량 적재 시 건당 로그를 남기면 수천 줄이 쌓이므로 DEBUG 로 낮춘다.
                if (groupJob.addSimulation(simulation)) {
                    logger.debug("Simulation {} added to existing group [{}]. Group size: {}",
                            simulation.getId(), groupKey, groupJob.size());
                }
            } else {
                // 새 그룹 생성 & 스케줄 등록
                groupJob = simulationJobFactory.create(device, simulation.getInterval());
                groupJob.addSimulation(simulation);
                groupedJobs.put(groupKey, groupJob);

                // 같은 주기의 그룹이 모두 같은 순간에 돌면 변경이 한 덩어리로 몰린다. 첫 실행만 흩는다.
                PeriodicTrigger trigger = JitteredTrigger.ofSeconds(simulation.getInterval());
                ScheduledFuture<?> future = threadPoolTaskScheduler.schedule(groupJob, trigger);
                groupedSchedules.put(groupKey, future);

                adjustPoolSize();

                MkLogger.log("simulation", LogLevel.INFO,
                        "New simulation group [{0}] created with simulation {1}.",
                        groupKey, simulation.getId());
            }
        }

        if (device.getProtocol() == Protocol.BACNET) {
            bacnetService.addSimulation(device.getId(), simulation);
        }
    }

    /**
     * Simulation을 중지한다. 그룹에서 해당 Simulation을 제거하고,
     * 그룹이 비면 스케줄을 취소하고 그룹을 제거한다.
     */
    public void stopSimulation(Simulation simulation) {
        synchronized (groupedJobs) {
            String targetKey = null;
            for (Map.Entry<String, GroupedSimulationJob> entry : groupedJobs.entrySet()) {
                if (entry.getValue().removeSimulation(simulation.getId())) {
                    targetKey = entry.getKey();
                    break;
                }
            }

            if (targetKey != null) {
                GroupedSimulationJob groupJob = groupedJobs.get(targetKey);
                if (groupJob.isEmpty()) {
                    ScheduledFuture<?> future = groupedSchedules.remove(targetKey);
                    if (future != null) {
                        future.cancel(true);
                    }
                    groupedJobs.remove(targetKey);
                    adjustPoolSize();
                    MkLogger.log("simulation", LogLevel.INFO,
                            "Simulation group [{0}] removed (empty).", targetKey);
                } else {
                    MkLogger.log("simulation", LogLevel.INFO,
                            "Simulation {0} removed from group [{1}]. Remaining: {2}",
                            simulation.getId(), targetKey, groupJob.size());
                }
            }
        }
    }

    /**
     * Simulation.stopOnControl 값에 따라 제어 수용 후속 조치를 적용한다.
     *   0        : 무동작
     *   N > 0    : N초 동안 해당 target 쓰기 보호 (ServerData lock)
     *   N < 0    : 영구 정지 (DB에 DISABLED 기록)
     * <p>
     * N > 0 은 Error 의 simulation_stop 과 같은 수단을 쓴다. 정지 중에는 값이 멈출 뿐 아니라
     * ACCUMULATE 누적과 SEQUENCE 위치도 나아가지 않는다.
     * N < 0 은 시간 정지가 아니라 시뮬레이션을 아예 끄는 별개 동작이라 그대로 둔다.
     */
    public void applyStopOnControl(Simulation simulation) {
        int stopOnControl = simulation.getStopOnControl();
        if (stopOnControl == 0) return;

        Device device = simulation.getDevice();
        if (stopOnControl < 0) {
            simulation.setEnabled(Simulation.DISABLED);
            updateSimulation(simulation);
            MkLogger.log("simulation", LogLevel.INFO,
                    "Simulation {0} permanently disabled by stopOnControl (device {1}({2}), target {3}).",
                    simulation.getId(), device.getName(), device.getId(), simulation.getTarget());
        } else {
            stopSimulationFor(device, simulation, stopOnControl);
            MkLogger.log("simulation", LogLevel.INFO,
                    "Simulation {0} stopped for {1}s by stopOnControl (device {2}({3}), target {4}).",
                    simulation.getId(), stopOnControl, device.getName(), device.getId(), simulation.getTarget());
        }
    }

    /**
     * target 을 seconds 초 동안 쓰기 보호해 그 Simulation 을 멈춘다.
     * ServerData 가 없는 장비(DB 프로토콜)는 걸 대상이 없으므로 아무 일도 하지 않는다.
     */
    public void stopSimulationFor(Device device, Simulation simulation, int seconds) {
        if (seconds <= 0) return;
        ioServerInstances.getServerInstance(device.getId()).ifPresent(server -> {
            ServerData serverData = server.getServerData();
            if (serverData == null) return;
            List<SimulationPolicy> policies = simulation.getPolicies();
            DataType dataType = (policies == null || policies.isEmpty())
                    ? null : policies.get(0).getData_type();
            serverData.stopSimulationFor(simulation.getTarget(), seconds, dataType);
        });
    }

    public void insertSimulation(Simulation simulation) {
        simulationRepository.save(simulation);
        Device device = deviceService.getDevice(simulation.getDevice().getId());
        startSimulation(device, simulation);
        reindexDevice(device.getId());
    }

    /**
     * 대량 적재용. device 를 호출측이 이미 알고 있을 때 건당 재조회를 생략한다.
     * (batchInsert 가 simulation 수천 건을 넣을 때 SELECT 수천 회를 없앤다)
     */
    public void insertSimulation(Simulation simulation, Device device) {
        simulationRepository.save(simulation);
        startSimulation(device, simulation);
    }

    public Simulation selectSimulation(Integer id) {
        return simulationRepository.findById(id).orElseThrow(EntityNotFoundException::new);
    }

    /**
     * 장비의 ServerData 가 들고 있는 target -> Simulation 색인을 다시 만든다.
     * Simulation 이 바뀐 뒤에는 반드시 불러야 핸들러와 API 가 같은 것을 본다.
     * <p>
     * 건건이 부르지 않는다. 대량 적재는 한 건마다 색인을 다시 만들면 O(n^2) 이 되므로
     * 호출측이 루프를 끝낸 뒤 한 번만 부른다.
     */
    public void reindexDevice(Integer deviceId) {
        if (deviceId == null) return;
        ioServerInstances.getServerInstance(deviceId).ifPresent(server -> {
            ServerData serverData = server.getServerData();
            if (serverData != null) {
                serverData.indexSimulations(selectSimulationsByDeviceId(deviceId));
            }
        });
    }

    public void updateSimulation(Simulation simulation) {
        Device device = simulation.getDevice();
        stopSimulation(simulation);

        Simulation updated = simulationRepository.save(simulation);

        startSimulation(device, updated);
        reindexDevice(device == null ? null : device.getId());
    }

    public void deleteSimulation(Integer id) {
        Simulation simulation = selectSimulation(id);
        simulationRepository.deleteById(id);
        stopSimulation(simulation);
        // 진행 상태는 stop 만으로는 지워지지 않는다 (update 시 리셋되면 안 되므로 의도적으로 분리됨)
        GroupedSimulationJob.resetProgress(id);
        reindexDevice(simulation.getDevice() == null ? null : simulation.getDevice().getId());
    }

    /**
     * 정책의 진행 상태(ACCUMULATE 누적값 / SEQUENCE 인덱스)를 버려 처음부터 다시 시작하게 한다.
     * stop/start 로는 지워지지 않으므로 (이름 변경 같은 단순 수정이 리셋하면 안 됨)
     * 기준값이 바뀐 경우와 사용자가 명시적으로 리셋한 경우에만 호출한다.
     */
    public void resetAccumulatedValue(Integer simulationId) {
        GroupedSimulationJob.resetProgress(simulationId);
    }

    public Boolean selectSimulationEnabled(Integer id) {
        return selectSimulation(id).getEnabled() == 1;
    }

    public void deleteSimulationByDeviceId(Integer device_id) {
        stopDeviceSimulations(device_id);
        simulationRepository.deleteSimulationByDeviceId(device_id);
        reindexDevice(device_id);
    }

    public void startDeviceSimulations(Device device) {
        List<Simulation> simulations = selectSimulationsByDeviceId(device.getId());
        for (Simulation simulation : simulations) {
            startSimulation(device, simulation);
        }
        reindexDevice(device.getId());
    }

    public void stopDeviceSimulations(Integer device_id) {
        List<Simulation> simulations = selectSimulationsByDeviceId(device_id);
        for (Simulation simulation : simulations) {
            stopSimulation(simulation);
        }
    }

    public void copySimulations(Integer copyTo, Integer device_id) {
        List<Simulation> orig = simulationRepository.findSimulationsByDeviceId(device_id);
        Device device = deviceService.selectDevice(copyTo);
        for (Simulation s : orig) {
            Simulation simulation = s.clone();
            simulation.setDevice(device);
            simulationRepository.save(simulation);
            if (simulation.getEnabled() == Simulation.ENABLED) {
                startSimulation(device, simulation);
            }
        }
        reindexDevice(copyTo);
    }

    /**
     * 현재 활성 그룹 스케줄 수에 맞춰 풀 사이즈를 동적으로 조정한다.
     * 최소 풀 사이즈는 5, 최대는 활성 그룹 수 + 여유분(2)으로 설정한다.
     */
    private void adjustPoolSize() {
        int activeGroups = groupedSchedules.size();
        int newPoolSize = Math.max(5, activeGroups + 2);
        int currentPoolSize = threadPoolTaskScheduler.getPoolSize();
        if (newPoolSize != currentPoolSize) {
            threadPoolTaskScheduler.setPoolSize(newPoolSize);
            MkLogger.log("simulation", LogLevel.INFO,
                    "ThreadPool size adjusted: {0} -> {1} (active groups: {2})",
                    currentPoolSize, newPoolSize, activeGroups);
        }
    }
}
