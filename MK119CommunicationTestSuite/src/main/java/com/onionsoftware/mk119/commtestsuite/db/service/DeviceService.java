package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.repository.DeviceRepository;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
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
import javax.persistence.EntityNotFoundException;
import java.sql.SQLException;
import java.util.List;

@Service
@Getter
@RequiredArgsConstructor
public class DeviceService {
    private final IOServerInstances ioServerInstances;
    private SimulationService simulationService;
    private final ApplicationContext applicationContext;
    private final DBDeviceService dbDeviceService;
    private final BACnetService bacnetService;
    private final Logger logger = LoggerFactory.getLogger("agent");
    private final DeviceRepository deviceRepository;

    @PostConstruct
    public void init() {
        this.simulationService = (SimulationService) applicationContext.getBean("simulationService");
    }

    public List<Device> selectDevices() {
        return deviceRepository.findAll();
    }

    public List<Device> selectDevicesByGroupId(Integer group_id) {
        return deviceRepository.findDevicesByGroupId(group_id);
    }

    public Device selectDevice(Integer id) {
        return deviceRepository.findById(id).orElseThrow(EntityNotFoundException::new);
    }

    public Device getDevice(Integer id) {
        return deviceRepository.getDeviceById(id);
    }

    public void insertDevice(Device device) {
        Device inserted = deviceRepository.save(device);
        startDevice(inserted);
    }

    public void deleteDevice(Integer id) {
        stopDevice(id);
        deviceRepository.deleteById(id);
        simulationService.deleteSimulationByDeviceId(id);
    }

    public void updateDevice(Device device) {
        stopDevice(device.getId());
        Device updated = deviceRepository.save(device);
        startDevice(updated);
    }

    public void restartDevice(Device device) {
        stopDevice(device.getId());
        startDevice(device);
    }

    public boolean selectDeviceEnabled(Integer id) {
        return deviceRepository.findById(id).orElseThrow(EntityNotFoundException::new).getEnabled() == Device.DEVICE_ENABLED;
    }

    public void updateDeviceEnabled(Integer enabled, Integer id) {
        stopDevice(id);
        Device device = deviceRepository.findById(id).orElseThrow(EntityNotFoundException::new);
        device.setEnabled(enabled);
        Device updated = deviceRepository.save(device);
        startDevice(updated);
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
            simulationService.startDeviceSimulations(device);
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
