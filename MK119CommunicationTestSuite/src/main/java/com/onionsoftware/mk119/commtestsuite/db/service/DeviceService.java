package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.mapper.DeviceMapper;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.db.DBClientDevice;
import com.onionsoftware.mk119.commtestsuite.io.db.DBDeviceService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.sql.SQLException;
import java.util.List;

@Service
@Getter
@RequiredArgsConstructor
public class DeviceService {
    private final DeviceMapper deviceMapper;
    private final IOServerInstances ioServerInstances;
    private SimulationService simulationService;
    private final ApplicationContext applicationContext;
    private final DBDeviceService dbDeviceService;
    private final BACnetService bacnetService;
    private final Logger logger = LoggerFactory.getLogger("agent");

    @PostConstruct
    public void init() {
        this.simulationService = (SimulationService) applicationContext.getBean("simulationService");
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

    public void restartDevice(Device device) {
        stopDevice(device.getId());
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
            } else if (device.getProtocol() == Protocol.BACNET) {
                bacnetService.addDevice(device);
            } else if (device.getProtocol() == Protocol.DB) {
                try {
                    DBClientDevice dbClientDevice = new DBClientDevice(device);
                    dbDeviceService.addDevice(dbClientDevice);
                } catch (ClassNotFoundException e) {
                    logger.error("DB Driver class '{}' not found.", device.getCommunicationSettings().get("dbdriver"));
                } catch (SQLException e) {
                    logger.error("SQL error to establish connection.", e);
                }
            }
            simulationService.startDeviceSimulations(device.getId());
        }
    }

    public void stopDevice(Integer id) {
        Device dev = this.selectDevice(id);
        simulationService.stopDeviceSimulations(id);
        if (Protocol.NETTY_PROTOCOLS.contains(dev.getProtocol())) {
            ioServerInstances.stopServerInstance(id);
        } else if (dev.getProtocol() == Protocol.BACNET) {
            bacnetService.removeDevice(id);
        } else if (dev.getProtocol() == Protocol.DB) {
            dbDeviceService.removeDevice(dev.getId());
        }
    }
}
