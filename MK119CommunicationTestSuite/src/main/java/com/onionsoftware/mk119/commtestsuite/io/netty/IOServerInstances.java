package com.onionsoftware.mk119.commtestsuite.io.netty;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.EthernetIPData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataListener;
import lombok.Getter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
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

    /** ServerData 변경 이벤트를 STOMP 로 브로드캐스트하기 위한 리스너. 모든 IOServer 에 자동 부착된다. */
    @Autowired(required = false)
    private ServerDataListener serverDataBridge;

    public void addServerInstance(Device device) {
        IOServer ioServer = servers.get(device.getId());
        if (ioServer != null) {
            ioServer.stopServer();
        }
        ioServer = ioServerFactory.getObject(device);
        if (serverDataBridge != null) {
            ioServer.addServerDataListener(serverDataBridge);
        }
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

    /**
     * Spring context 종료(콘솔 Ctrl+C 포함) 시 모든 IOServer 인스턴스를 정지시킨다.
     * IOServer 스레드는 daemon이지만, Netty EventLoopGroup 정리를 위해 명시적으로 stop을 호출한다.
     */
    @PreDestroy
    public void shutdownAll() {
        LoggerFactory.getLogger("io").info("Shutting down {} IOServer instance(s) on context close.", servers.size());
        for (IOServer server : servers.values()) {
            try {
                server.stopServer();
            } catch (Exception e) {
                LoggerFactory.getLogger("io").error("Error stopping IOServer for device {}", server.getDevice().getName(), e);
            }
        }
        servers.clear();
    }

    @Scheduled(fixedRate = 10000)
    public void inspectEnetSessions() {
        for(IOServer server : servers.values()) {
            if(server.getServerData() instanceof EthernetIPData) {
                EthernetIPData data = (EthernetIPData) server.getServerData();
                LoggerFactory.getLogger("io").info("Enip session count : {}", data.getSession_counter());
                for(Integer session : data.getSessions()) {
                    LoggerFactory.getLogger("io").info("Enip session : {}", session);
                }
            }
        }
    }
}
