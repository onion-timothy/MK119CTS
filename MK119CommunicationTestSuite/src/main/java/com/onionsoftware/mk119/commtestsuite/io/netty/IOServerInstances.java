package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.EthernetIPData;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import lombok.Getter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component("ioServerInstances")
@Getter
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

    public Optional<IOServer> getServerInstance(int deviceId) {
        return Optional.ofNullable(servers.get(deviceId));
    }

    @Scheduled(fixedRate = 10000)
    public void inspectEnetSessions() {
        for(IOServer server : servers.values()) {
            if(server.getServerData() instanceof EthernetIPData) {
                EthernetIPData data = (EthernetIPData) server.getServerData();
                MkLogger.log("io", LogLevel.INFO, "Enip session count : {0}", data.getSession_counter());
                for(Integer session : data.getSessions()) {
                    MkLogger.log("io", LogLevel.INFO, "Enip session : {0}", session);
                }
            }
        }
    }
}
