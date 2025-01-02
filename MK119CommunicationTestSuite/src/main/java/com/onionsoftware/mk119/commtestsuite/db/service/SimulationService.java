package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.repository.SimulationRepository;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.simulation.SimulationJob;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SimulationService {
    private final SimulationRepository simulationRepository;
    private final ThreadPoolTaskScheduler threadPoolTaskScheduler;
    private final DeviceService deviceService;
    private final ObjectProvider<SimulationJob> simulationJobFactory;
    private final BACnetService bacnetService;

    private final Map<Integer, ScheduledFuture<?>> currentSchedules = new HashMap<>();

    public List<Simulation> selectSimulationsByDeviceId(Integer id) {
        return simulationRepository.findSimulationsByDeviceId(id);
    }

    public void startSimulation(Device device, Simulation simulation) {
        if (simulation.getEnabled() == Simulation.ENABLED) {
            SimulationJob simulationJob = this.simulationJobFactory.getObject(simulation);
            PeriodicTrigger periodicTrigger = new PeriodicTrigger(simulation.getInterval(), TimeUnit.SECONDS);
            ScheduledFuture<?> f = threadPoolTaskScheduler.schedule(simulationJob, periodicTrigger);
            currentSchedules.put(simulation.getId(), f);
            if (device.getProtocol() == Protocol.BACNET) {
                bacnetService.addSimulation(simulation.getDevice().getId(), simulation);
            }
        }
    }

    public void stopSimulation(Simulation simulation) {
        ScheduledFuture<?> future = currentSchedules.get(simulation.getId());
        if(future != null){
            future.cancel(true);
            currentSchedules.remove(simulation.getId());
        }
    }

    public void pauseSimulation(Simulation simulation, Integer stopTime) {
        ScheduledFuture<?> future = currentSchedules.get(simulation.getId());
        if(future != null){
            future.cancel(true);
            currentSchedules.remove(simulation.getId());


            SimulationJob simulationJob = this.simulationJobFactory.getObject(simulation);
            PeriodicTrigger periodicTrigger = new PeriodicTrigger(simulation.getInterval(), TimeUnit.SECONDS);
            periodicTrigger.setInitialDelay(stopTime);

            MkLogger.log("simulation", LogLevel.INFO, "simulation pause to");

            ScheduledFuture<?> f = threadPoolTaskScheduler.schedule(simulationJob, periodicTrigger);
            currentSchedules.put(simulation.getId(), f);
        }
    }

    public void insertSimulation(Simulation simulation) {
        simulationRepository.save(simulation);
        Device device = deviceService.getDevice(simulation.getDevice().getId());
        startSimulation(device, simulation);
    }

    public Simulation selectSimulation(Integer id) {
        return simulationRepository.findById(id).orElseThrow(EntityNotFoundException::new);
    }

    public void updateSimulation(Simulation simulation) {
        Device device = simulation.getDevice();
        stopSimulation(simulation);

        Simulation updated = simulationRepository.save(simulation);

        startSimulation(device, updated);
    }

    public void deleteSimulation(Integer id) {
        Simulation simulation = selectSimulation(id);
        simulationRepository.deleteById(id);
        stopSimulation(simulation);
    }

    public Boolean selectSimulationEnabled(Integer id) {
        return selectSimulation(id).getEnabled() == 1;
    }


    public void deleteSimulationByDeviceId(Integer device_id) {
        stopDeviceSimulations(device_id);
        simulationRepository.deleteSimulationByDeviceId(device_id);
    }

    public void startDeviceSimulations(Device device) {
        List<Simulation> simulations = selectSimulationsByDeviceId(device.getId());
        for (Simulation simulation : simulations) {
            startSimulation(device, simulation);
        }
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
    }
}
