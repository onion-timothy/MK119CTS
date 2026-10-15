package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * BACnet 프로토콜용 ServerData. 실제 값 저장은 {@link BACnetService#getSimulationValues()}
 * (simulation ID 키)에 위임한다.
 * <p>
 * 다른 프로토콜은 target 문자열이 저장 위치를 직접 가리키지만 BACnet 은 simulation ID 로 저장한다.
 * 그래서 target 기반 쓰기/읽기는 target → simulation 을 먼저 찾아야 한다. 장비 안에서
 * target 은 유일하므로({@link BACnetService#addSimulation}) 1:1 로 찾을 수 있다.
 */
public class BACnetData extends ServerData {
    private final BACnetService bacnetService;

    public BACnetData(BACnetService bacnetService) {
        this.bacnetService = bacnetService;
    }

    /** 이 장비에 등록된 시뮬레이션 목록. 없으면 빈 리스트. */
    private List<Simulation> deviceSimulations() {
        if (ioServer == null || ioServer.getDevice() == null) return Collections.emptyList();
        List<Simulation> sims = bacnetService.getSimulationMap().get(ioServer.getDevice().getId());
        return sims == null ? Collections.<Simulation>emptyList() : sims;
    }

    /** target 과 정확히 일치하는 시뮬레이션. target 은 장비 안에서 유일하다. */
    private Optional<Simulation> simulationByTarget(String target) {
        if (target == null) return Optional.empty();
        for (Simulation s : deviceSimulations()) {
            if (target.equals(s.getTarget())) return Optional.of(s);
        }
        return Optional.empty();
    }

    @Override
    public boolean setServerData(Simulation simulation, double value, ServerDataChangeSource source) {
        String target = simulation.getTarget();
        if (isBlockedByLock(target, source)) return false;
        bacnetService.getSimulationValues().put(simulation.getId(), (float) value);
        fireChangeEvent(source, target, value, simulation);
        return true;
    }

    /** lock 만료를 지정하지 않는 호출. 세션이 lock 을 풀어주는 WS/내부 경로용이다. */
    @Override
    public boolean setServerData(String target, double value, DataType dataType,
                                 ServerDataChangeSource source) {
        return setServerData(target, value, dataType, source, PERMANENT_LOCK_SECONDS);
    }

    /**
     * target 기반 쓰기. target 에 대응하는 시뮬레이션을 찾아 그 값을 덮어쓴다.
     * <p>
     * lock 은 {@code lockSeconds} 를 그대로 따른다. 예전에는 만료 없는 {@code lock(target)} 을
     * 호출해 호출자가 보낸 초를 무시했다.
     *
     * @throws UnsupportedOperationException target 에 해당하는 시뮬레이션이 없을 때.
     *         BACnet 은 시뮬레이션이 곧 object 이므로, 없는 target 에는 쓸 곳이 없다.
     */
    @Override
    public boolean setServerData(String target, double value, DataType dataType,
                                 ServerDataChangeSource source, Integer lockSeconds) {
        if (isBlockedByLock(target, source)) return false;

        Optional<Simulation> opt = simulationByTarget(target);
        if (!opt.isPresent()) {
            throw new UnsupportedOperationException(
                    "No BACnet object for target=" + target
                            + ". A simulation with that exact target must exist on this device.");
        }
        Simulation simulation = opt.get();

        bacnetService.getSimulationValues().put(simulation.getId(), (float) value);
        if (source == ServerDataChangeSource.API_WRITE) {
            lock(target, lockSeconds);
        }
        fireChangeEvent(source, target, value, Collections.singletonList(target), simulation);
        return true;
    }

    /** target 의 현재 값. 해당 시뮬레이션이 없거나 아직 한 번도 안 돌았으면 null. */
    public Float readValue(String target) {
        return simulationByTarget(target)
                .map(s -> bacnetService.getSimulationValues().get(s.getId()))
                .orElse(null);
    }

    /** 스냅샷용: 이 장비의 object 목록과 현재 값. */
    public List<Simulation> getSimulations() {
        return deviceSimulations();
    }

    public Float valueOfSimulation(Integer simulationId) {
        return bacnetService.getSimulationValues().get(simulationId);
    }

    @Override
    public void resetServerData() {
        for (Simulation s : deviceSimulations()) {
            bacnetService.getSimulationValues().remove(s.getId());
        }
    }
}
