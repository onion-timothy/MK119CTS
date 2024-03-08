package com.onionsoftware.mk119.commtestsuite.io.bacnet;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class BACnetService {
    private static BACnetService instance;
    private static IOServer ioServer;

    private final List<Device> bacnetDevices;
    private final Map<Integer, List<Simulation>> simulationMap;
    private final Map<Integer, Float> simulationValues;

    public static BACnetService getInstance() {
        if (instance == null) {
            instance = new BACnetService();
        }
        return instance;
    }

    private BACnetService() {
        bacnetDevices = new ArrayList<>();
        simulationMap = new HashMap<>();
        simulationValues = new HashMap<>();
    }

    public void addDevice(Device device) {
        if (bacnetDevices.isEmpty()) {
            Device localDeviceVirtual = new Device();
            localDeviceVirtual.setHost("0.0.0.0");
            localDeviceVirtual.setPort(47808);
            localDeviceVirtual.setProtocol(Protocol.BACNET);
            localDeviceVirtual.setTransport(Transport.UDP);
            localDeviceVirtual.setStrCommunicationSettings("{}");

            ioServer = new IOServer(localDeviceVirtual);
            ioServer.start();
        }
        bacnetDevices.add(device);
    }

    public void removeDevice(int deviceId) {
        bacnetDevices.removeIf(e -> e.getId() == deviceId);
        if (bacnetDevices.isEmpty()) {
            ioServer.stopServer();
        }
    }

    public void addSimulation(int deviceId, Simulation simulation) {
        List<Simulation> simulations = simulationMap.computeIfAbsent(deviceId, k -> new ArrayList<>());
        simulations.add(simulation);
    }

    public void removeSimulation(int deviceId, int simulationId) {
        List<Simulation> simulations = simulationMap.get(deviceId);
        if (simulations != null) {
            simulations.removeIf(e -> e.getId() == simulationId);
        }
    }
}
