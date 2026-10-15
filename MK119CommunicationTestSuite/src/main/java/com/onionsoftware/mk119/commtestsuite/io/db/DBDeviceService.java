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
        // Integer == Integer 는 값이 아니라 참조를 비교한다. Integer 캐시(-128~127) 안의 id 는
        // 우연히 맞지만 128 이상에서는 같은 장비인데도 false 가 되어 connection 을 못 찾는다.
        Optional<DBClientDevice> optDev = devices.stream()
                .filter(e -> java.util.Objects.equals(e.getDevice().getId(), device.getId()))
                .findFirst();
        return optDev.map(DBClientDevice::getConnection).orElse(null);
    }
}
