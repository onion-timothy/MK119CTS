package com.onionsoftware.mk119.commtestsuite.io.bacnet;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.BACnetReadProperty;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.BACnetReadPropertyMultiple;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.BACnetWriteProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BACnet 장비와 시뮬레이션을 관리하는 Singleton 서비스
 * BACnet 통신 과정에서 장비들의 정보와 시뮬레이션 정보, 값을 여기서 받아간다.
 */
@Getter
@Setter
@Service("bacnetService")
@RequiredArgsConstructor
public class BACnetService {
    private IOServer bacnetEndpoint;
    private final ObjectProvider<IOServer> ioServerFactory;

    private List<Device> bacnetDevices = new ArrayList<>();
    /**
     * Key: Instance ID
     * Value: IOServer
     */
    private Map<Integer, IOServer> bacnetServerMap = new ConcurrentHashMap<>();
    /**
     * Key: Device ID
     * Value: List of simulations
     */
    private Map<Integer, List<Simulation>> simulationMap = new ConcurrentHashMap<>();
    /**
     * Key: Simulation ID
     * Value: Current value
     */
    private Map<Integer, Float> simulationValues = new ConcurrentHashMap<>();
    private final IOServerInstances ioServerInstances;
    private final ObjectProvider<BACnetReadProperty> bacnetReadPropertyFactory;
    private final ObjectProvider<BACnetReadPropertyMultiple> bacnetReadPropertyMultipleFactory;
    private final ObjectProvider<BACnetWriteProperty> bacnetWritePropertyFactory;

    /**
     * DeviceService에서 장비 활성화 시 실행된다.
     * Netty BACnet Endpoint를 각 장비별로 기동한다.
     *
     * @param device 추가할 Device
     */
    public synchronized void addDevice(Device device) {
        ioServerInstances.addServerInstance(device);
        bacnetDevices.add(device);
        int instanceId = Integer.parseInt(device.getCommunicationSettings().get("deviceInstanceId").toString());
        ioServerInstances.getServerInstance(device.getId())
                .ifPresent(server -> bacnetServerMap.put(instanceId, server));
    }

    /**
     * DeviceService에서 장비 비활성화 시 실행된다.
     * 장비 삭제 시 매핑된 Netty BACnet Endpoint도 종료한다.
     *
     * @param deviceId 삭제할 장비 ID
     */
    public synchronized void removeDevice(int deviceId) {
        bacnetDevices.stream().filter(e -> e.getId() == deviceId).findFirst().ifPresent(device -> {
            int instanceId = Integer.parseInt(device.getCommunicationSettings().get("deviceInstanceId").toString());
            bacnetServerMap.remove(instanceId);
        });
        bacnetDevices.removeIf(e -> e.getId() == deviceId);
        ioServerInstances.stopServerInstance(deviceId);
    }

    /**
     * SimulationService에서 시뮬레이션 활성화 시 실행된다.
     * 시뮬레이션은 장비 ID를 key로 하는 Map에 List로 저장된다.
     * 해당 장비의 첫 시뮬레이션 추가일 경우 List를 생성한다.
     *
     * @param deviceId   장비 ID
     * @param simulation 추가할 시뮬레이션
     */
    public synchronized void addSimulation(int deviceId, Simulation simulation) {
        // CopyOnWriteArrayList: netty IO 스레드가 읽기 응답을 만들며 이 리스트를 순회하는 동안
        // 요청 스레드가 시뮬레이션을 추가/삭제한다. ArrayList 로는 ConcurrentModificationException
        // 이나 깨진 순회가 가능하다. 쓰기는 드물고 읽기가 압도적이라 COW 가 맞다.
        List<Simulation> simulations =
                simulationMap.computeIfAbsent(deviceId, k -> new CopyOnWriteArrayList<>());
        // 존재 확인과 추가가 원자적이어야 target 중복이 끼어들지 않는다 (메서드 synchronized).
        if (simulations.stream().noneMatch(e -> e.getTarget().equals(simulation.getTarget())))
            simulations.add(simulation);
    }

    /**
     * SimulationService에서 시뮬레이션 비활성화 시 실행된다.
     * 장비 ID를 key로 하는 Map에서 List를 가져와서 ID가 일치하는 시뮬레이션을 삭제한다.
     *
     * @param deviceId     장비 ID
     * @param simulationId 삭제할 시뮬레이션 ID
     */
    public synchronized void removeSimulation(int deviceId, int simulationId) {
        List<Simulation> simulations = simulationMap.get(deviceId);
        if (simulations != null) {
            simulations.removeIf(e -> e.getId() == simulationId);
        }
    }
}
