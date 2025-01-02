package com.onionsoftware.mk119.commtestsuite;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.GroupService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ServiceInitializer {
    private final DeviceService deviceService;
    private final SimulationService simulationService;
    private final GroupService groupService;
    private final ErrorService errorService;

    @EventListener(ApplicationReadyEvent.class)
    public void afterStart() {
        groupService.makeRootGroup();

        List<Device> devices = deviceService.selectDevices();
        for (Device device : devices) {
            if (device.getEnabled() == Device.DEVICE_ENABLED) {
                deviceService.startDevice(device);
            }
        }
    }
}
