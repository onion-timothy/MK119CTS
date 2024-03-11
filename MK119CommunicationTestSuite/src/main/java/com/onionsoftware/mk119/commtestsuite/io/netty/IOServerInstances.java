package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component("ioServerInstances")
public class IOServerInstances {
    ObjectProvider<IOServer> ioServerFactory;

    public IOServerInstances(ObjectProvider<IOServer> ioServerFactory) {
        this.ioServerFactory = ioServerFactory;
    }

    private final Map<Integer, IOServer> servers = new HashMap<>();

    public void addServerInstance(Device device) {
        IOServer ioServer = servers.get(device.getId());
        if (ioServer != null) {
            ioServer.stopServer();
        }
        ioServer = ioServerFactory.getObject(device);
        ioServer.start();
        servers.put(device.getId(), ioServer);
    }

    public void stopServerInstance(int deviceId) {
        IOServer ioServer = servers.get(deviceId);
        if (ioServer != null) {
            ioServer.stopServer();
            servers.remove(deviceId);
        }
    }

    public IOServer getServerInstance(int deviceId) {
        return servers.get(deviceId);
    }
}
