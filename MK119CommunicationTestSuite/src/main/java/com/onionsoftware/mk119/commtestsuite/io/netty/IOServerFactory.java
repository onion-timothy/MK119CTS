package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration("ioServerFactory")
@RequiredArgsConstructor
public class IOServerFactory {
    final ApplicationContext applicationContext;

    @Bean
    @Scope("prototype")
    public IOServer ioServer(Device device) {
        device.getCommunicationSettings().put("transport", device.getTransport().name());
        IOServer ioServer = new IOServer(device);
        ioServer.setServerData(serverData(device));
        return ioServer;
    }

    @Bean
    @Scope("prototype")
    public ServerData serverData(Device device) {
        Object size = device.getCommunicationSettings().get("registers");
        int _size = 1000;

        if (size != null) {
            try {
                _size = Integer.parseInt(size.toString());
            } catch (NumberFormatException ignored) {

            }
        }

        switch (device.getProtocol()) {
            case GENERIC:
                return new GenericData();
            case MODBUS:
                return new ModbusData(_size);
            case XGT_PLC:
                return new XGTData(_size);
            case DAEYUN_RCU:
                return new DaeyunData();
            case OPC_AGENT:
                return new OPCAgentData(_size);
            case SNMP:
                return new SnmpData();
            default:
                return null;
        }
    }

    @Bean
    @Scope("prototype")
    public ChannelInboundHandlerAdapter decoder(Device device) {
        String beanName = device.getProtocol().name().toLowerCase() + "_decoder_" + device.getTransport().name().toLowerCase();
        return (ChannelInboundHandlerAdapter) applicationContext.getBean(beanName);
    }

    @Bean
    @Scope("prototype")
    public ChannelInboundHandlerAdapter handler(Device device) {
        return (ChannelInboundHandlerAdapter) applicationContext.getBean(device.getProtocol().name().toLowerCase());
    }
}
