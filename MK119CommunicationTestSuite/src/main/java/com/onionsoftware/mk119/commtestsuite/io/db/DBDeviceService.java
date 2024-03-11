package com.onionsoftware.mk119.commtestsuite.io.db;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service("dbDeviceService")
public class DBDeviceService {
    private final List<DBClientDevice> devices = new ArrayList<>();

    public void addDevice(DBClientDevice device) {
        this.devices.add(device);
    }

    public synchronized void removeDevice(int deviceId) {
        devices.removeIf(e -> e.getDevice().getId() == deviceId);
    }

    public Connection getConnection(Device device) {
        Optional<DBClientDevice> optDev = devices.stream().filter(e -> e.getDevice().getId() == device.getId()).findFirst();
        return optDev.map(DBClientDevice::getConnection).orElse(null);
    }
}
