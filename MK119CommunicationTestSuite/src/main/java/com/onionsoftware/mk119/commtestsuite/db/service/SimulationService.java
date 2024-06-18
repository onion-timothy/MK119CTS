package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.mapper.SimulationMapper;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.simulation.SimulationExecutorService;
import com.onionsoftware.mk119.commtestsuite.simulation.SimulationJob;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SimulationService {
    private final SimulationMapper simulationMapper;
    private final SimulationExecutorService simulationExecutorService;
    private final DeviceService deviceService;
    private final ObjectProvider<SimulationJob> simulationJobFactory;
    private final BACnetService bacnetService;

    public void createScheme() {
        simulationMapper.createScheme();
    }

    public List<Simulation> selectSimulationsByDeviceId(Integer id) {
        return simulationMapper.selectSimulationsByDeviceId(id);
    }

    public void startSimulation(Device device, Simulation simulation) {
        if (simulation.getEnabled() == Simulation.ENABLED) {
            SimulationJob simulationJob = this.simulationJobFactory.getObject(simulation);
            simulationExecutorService.execute(simulationJob);
            if (device.getProtocol() == Protocol.BACNET) {
                bacnetService.addSimulation(simulation.getDevice_id(), simulation);
            }
        }
    }

    public void stopSimulation(Device device, Simulation simulation) {
        simulationExecutorService.terminateJob(simulation.getId());
        if (device.getProtocol() == Protocol.BACNET) {
            //bacnetService.removeSimulation(simulation.getDevice_id(), simulation.getId());
        }
    }

    public void insertSimulation(Simulation simulation) {
        simulationMapper.insertSimulation(simulation);
        Device device = deviceService.selectDevice(simulation.getDevice_id());
        startSimulation(device, simulation);
    }

    public Simulation selectSimulation(Integer id) {
        return simulationMapper.selectSimulation(id);
    }

    public void updateSimulation(Simulation simulation) {
        Device device = deviceService.selectDevice(simulation.getDevice_id());
        stopSimulation(device, simulation);

        simulationMapper.updateSimulation(simulation);

        startSimulation(device, simulation);
    }

    public void deleteSimulation(Integer id) {
        Simulation simulation = selectSimulation(id);
        simulationMapper.deleteSimulation(id);
        Device device = deviceService.selectDevice(simulation.getDevice_id());
        stopSimulation(device, simulation);
    }

    public Boolean selectSimulationEnabled(Integer id) {
        return simulationMapper.selectSimulationEnabled(id) == 1;
    }

    public List<Simulation> selectSimulations() {
        return simulationMapper.selectSimulations();
    }

    public void deleteSimulationByDeviceId(Integer device_id) {
        stopDeviceSimulations(device_id);
        simulationMapper.deleteSimulationByDeviceId(device_id);
    }

    public void startDeviceSimulations(Integer device_id) {
        Device device = deviceService.selectDevice(device_id);
        List<Simulation> simulations = selectSimulationsByDeviceId(device_id);
        for (Simulation simulation : simulations) {
            startSimulation(device, simulation);
        }
    }

    public void stopDeviceSimulations(Integer device_id) {
        Device device = deviceService.selectDevice(device_id);
        List<Simulation> simulations = selectSimulationsByDeviceId(device_id);
        for (Simulation simulation : simulations) {
            stopSimulation(device, simulation);
        }
    }

    public void copySimulations(Integer copyTo, Integer device_id) {
        simulationMapper.copySimulations(copyTo, device_id);
    }
}
