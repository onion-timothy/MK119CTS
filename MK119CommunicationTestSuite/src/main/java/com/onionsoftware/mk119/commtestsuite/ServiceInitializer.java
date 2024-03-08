package com.onionsoftware.mk119.commtestsuite;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.GroupService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ServiceInitializer {
    DeviceService deviceService;
    SimulationService simulationService;
    GroupService groupService;

    @Autowired
    public ServiceInitializer(DeviceService deviceService, GroupService groupService, SimulationService simulationService) {
        this.deviceService = deviceService;
        this.groupService = groupService;
        this.simulationService = simulationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterStart() {
        deviceService.createScheme();
        simulationService.createScheme();
        groupService.createScheme();

        List<Device> devices = deviceService.selectDevices();
        for (Device device : devices) {
            if (device.getEnabled() == Device.DEVICE_ENABLED) {
                deviceService.startDevice(device);
            }
        }
    }
}
