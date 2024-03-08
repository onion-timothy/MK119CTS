package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component("ioServerInstances")
public class IOServerInstances {
    private final Map<Integer, IOServer> servers = new HashMap<>();

    public void addServerInstance(Device device) {
        IOServer ioServer = servers.get(device.getId());
        if (ioServer != null) {
            ioServer.stopServer();
        }
        ioServer = new IOServer(device);
        ioServer.start();
        servers.put(device.getId(), ioServer);
    }

    public void stopServerInstance(int deviceId){
        IOServer ioServer = servers.get(deviceId);
        if (ioServer != null) {
            ioServer.stopServer();
            servers.remove(deviceId);
        }
    }

    public IOServer getServerInstance(int deviceId){
        return servers.get(deviceId);
    }
}
