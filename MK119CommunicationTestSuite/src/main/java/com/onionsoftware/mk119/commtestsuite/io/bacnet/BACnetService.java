package com.onionsoftware.mk119.commtestsuite.io.bacnet;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * Key: Device ID
     * Value: List of simulations
     */
    private Map<Integer, List<Simulation>> simulationMap = new HashMap<>();
    /**
     * Key: Simulation ID
     * Value: Current value
     */
    private Map<Integer, Float> simulationValues = new HashMap<>();

    /**
     * DeviceService에서 장비 활성화 시 실행된다.
     * bacnetDevices가 비어있을 때, 즉 첫 실행시에는 Netty BACnet Endpoint를 기동한다.
     *
     * @param device 추가할 Device
     */
    public synchronized void addDevice(Device device) {
        if (bacnetDevices.isEmpty()) {
            Device localDeviceVirtual = new Device();
            localDeviceVirtual.setHost("0.0.0.0");
            localDeviceVirtual.setPort(47808);
            localDeviceVirtual.setProtocol(Protocol.BACNET);
            localDeviceVirtual.setTransport(Transport.UDP);
            localDeviceVirtual.setStrCommunicationSettings("{}");

            bacnetEndpoint = ioServerFactory.getObject(localDeviceVirtual);
            bacnetEndpoint.start();
        }
        bacnetDevices.add(device);
    }

    /**
     * DeviceService에서 장비 비활성화 시 실행된다.
     * 장비 삭제 후 bacnetDevice가 비어있을 때, 즉 모든 장비를 삭제한 후에는 Netty BACnet Endpoint도 종료한다.
     *
     * @param deviceId 삭제할 장비 ID
     */
    public synchronized void removeDevice(int deviceId) {
        bacnetDevices.removeIf(e -> e.getId() == deviceId);
        if (bacnetDevices.isEmpty()) {
            bacnetEndpoint.stopServer();
        }
    }

    /**
     * SimulationService에서 시뮬레이션 활성화 시 실행된다.
     * 시뮬레이션은 장비 ID를 key로 하는 Map에 List로 저장된다.
     * 해당 장비의 첫 시뮬레이션 추가일 경우 List를 생성한다.
     *
     * @param deviceId   장비 ID
     * @param simulation 추가할 시뮬레이션
     */
    public void addSimulation(int deviceId, Simulation simulation) {
        List<Simulation> simulations = simulationMap.computeIfAbsent(deviceId, k -> new ArrayList<>());
        if(simulations.stream().noneMatch(e->e.getTarget().equals(simulation.getTarget())))
            simulations.add(simulation);
    }

    /**
     * SimulationService에서 시뮬레이션 비활성화 시 실행된다.
     * 장비 ID를 key로 하는 Map에서 List를 가져와서 ID가 일치하는 시뮬레이션을 삭제한다.
     *
     * @param deviceId     장비 ID
     * @param simulationId 삭제할 시뮬레이션 ID
     */
    public void removeSimulation(int deviceId, int simulationId) {
        List<Simulation> simulations = simulationMap.get(deviceId);
        if (simulations != null) {
            simulations.removeIf(e -> e.getId() == simulationId);
        }
    }
}
