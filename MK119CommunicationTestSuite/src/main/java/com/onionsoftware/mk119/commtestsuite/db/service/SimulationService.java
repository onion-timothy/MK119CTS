package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.mapper.SimulationMapper;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.simulation.SimulationExecutorService;
import com.onionsoftware.mk119.commtestsuite.simulation.SimulationJob;
import com.onionsoftware.mk119.commtestsuite.utils.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SimulationService {
    private final SimulationMapper simulationMapper;
    private final SimulationExecutorService simulationExecutorService;

    public SimulationService(SimulationMapper simulationMapper, SimulationExecutorService simulationExecutorService) {
        this.simulationMapper = simulationMapper;
        this.simulationExecutorService = simulationExecutorService;
    }

    public void createScheme() {
        simulationMapper.createScheme();
    }

    public List<Simulation> selectSimulationsByDeviceId(Integer id) {
        return simulationMapper.selectSimulationsByDeviceId(id);
    }

    public void startSimulation(Device device, Simulation simulation) {
        if (simulation.getEnabled() == Simulation.ENABLED) {
            simulationExecutorService.execute(new SimulationJob(simulation));
            if (device.getProtocol() == Protocol.BACNET) {
                BACnetService.getInstance().addSimulation(simulation.getDevice_id(), simulation);
            }
        }
    }

    public void stopSimulation(Device device, Simulation simulation) {
        simulationExecutorService.terminateJob(simulation.getId());
        if (device.getProtocol() == Protocol.BACNET) {
            BACnetService.getInstance().removeSimulation(simulation.getDevice_id(), simulation.getId());
        }
    }

    public void insertSimulation(Simulation simulation) {
        simulationMapper.insertSimulation(simulation);
        DeviceService deviceService = (DeviceService) BeanUtils.getBean("deviceService");
        Device device = deviceService.selectDevice(simulation.getDevice_id());
        startSimulation(device, simulation);
    }

    public Simulation selectSimulation(Integer id) {
        return simulationMapper.selectSimulation(id);
    }

    public void updateSimulation(Simulation simulation) {
        DeviceService deviceService = (DeviceService) BeanUtils.getBean("deviceService");
        Device device = deviceService.selectDevice(simulation.getDevice_id());
        stopSimulation(device, simulation);

        simulationMapper.updateSimulation(simulation);

        startSimulation(device, simulation);
    }

    public void deleteSimulation(Integer id) {
        Simulation simulation = selectSimulation(id);
        simulationMapper.deleteSimulation(id);
        DeviceService deviceService = (DeviceService) BeanUtils.getBean("deviceService");
        Device device = deviceService.selectDevice(simulation.getDevice_id());
        stopSimulation(device, simulation);
    }

    public Boolean selectSimulationEnabled(Integer id) {
        return simulationMapper.selectSimulationEnabled(id) == 1;
    }

    public List<Simulation> selectSimulations() {
        return simulationMapper.selectSimulations();
    }

    public void deleteSimulationByDeviceId(Integer device_id){
        simulationMapper.deleteSimulationByDeviceId(device_id);
    }

    public void startDeviceSimulations(Integer device_id) {
        DeviceService deviceService = (DeviceService) BeanUtils.getBean("deviceService");
        Device device = deviceService.selectDevice(device_id);
        List<Simulation> simulations = selectSimulationsByDeviceId(device_id);
        for (Simulation simulation : simulations) {
            startSimulation(device, simulation);
        }
    }

    public void stopDeviceSimulations(Integer device_id) {
        DeviceService deviceService = (DeviceService) BeanUtils.getBean("deviceService");
        Device device = deviceService.selectDevice(device_id);
        List<Simulation> simulations = selectSimulationsByDeviceId(device_id);
        for (Simulation simulation : simulations) {
            stopSimulation(device, simulation);
        }
    }
}
