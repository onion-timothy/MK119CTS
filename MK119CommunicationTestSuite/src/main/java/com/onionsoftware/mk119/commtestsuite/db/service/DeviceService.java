package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.mapper.DeviceMapper;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DeviceService {
    private final DeviceMapper deviceMapper;
    private final IOServerInstances ioServerInstances;
    private final SimulationService simulationService;

    @Autowired
    public DeviceService(DeviceMapper deviceMapper, IOServerInstances ioServerInstances, SimulationService simulationService) {
        this.deviceMapper = deviceMapper;
        this.ioServerInstances = ioServerInstances;
        this.simulationService = simulationService;
    }

    public void createScheme() {
        deviceMapper.createScheme();
    }

    public List<Device> selectDevices() {
        return deviceMapper.selectDevices();
    }

    public List<Device> selectDevicesByGroupId(Integer group_id) {
        return deviceMapper.selectDevicesByGroupId(group_id);
    }

    public Device selectDevice(Integer id) {
        return deviceMapper.selectDevice(id);
    }

    public void insertDevice(Device device) {
        deviceMapper.insertDevice(device);
        device.setId(deviceMapper.identDevices());
        startDevice(device);
    }

    public void deleteDevice(Integer id) {
        stopDevice(id);
        deviceMapper.deleteDevice(id);
        simulationService.deleteSimulationByDeviceId(id);
    }

    public void updateDevice(Device device) {
        stopDevice(device.getId());
        deviceMapper.updateDevice(device);
        startDevice(device);
    }

    public boolean selectDeviceEnabled(Integer id) {
        return deviceMapper.selectDeviceEnabled(id) == 1;
    }

    public void updateDeviceEnabled(Integer enabled, Integer id) {
        stopDevice(id);
        deviceMapper.updateDeviceEnabled(enabled, id);

        Device device = selectDevice(id);
        startDevice(device);
    }

    public void startDevice(Device device) {
        if (device.getEnabled() == Device.DEVICE_ENABLED) {
            if (Protocol.NETTY_PROTOCOLS.contains(device.getProtocol())) {
                ioServerInstances.addServerInstance(device);
            } else if (device.getProtocol() == Protocol.BACNET){
                BACnetService bacnetService = BACnetService.getInstance();
                bacnetService.addDevice(device);
            }
            simulationService.startDeviceSimulations(device.getId());
        }
    }

    public void stopDevice(Integer id) {
        Device dev = this.selectDevice(id);
        simulationService.stopDeviceSimulations(id);
        if(Protocol.NETTY_PROTOCOLS.contains(dev.getProtocol())) {
            ioServerInstances.stopServerInstance(id);
        } else if (dev.getProtocol() == Protocol.BACNET){
            BACnetService bacnetService = BACnetService.getInstance();
            bacnetService.removeDevice(id);
        }
    }
}
