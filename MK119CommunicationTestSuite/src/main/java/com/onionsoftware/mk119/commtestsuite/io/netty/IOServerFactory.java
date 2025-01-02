package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.MkHandler;
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
        ErrorService errorService = (ErrorService) applicationContext.getBean("errorService");
        IOServer ioServer = new IOServer(device, errorService.selectEnabledErrorsByDeviceId(device.getId()));
        ioServer.setServerData(serverData(device));
        return ioServer;
    }

    @Bean
    @Scope("prototype")
    public ServerData serverData(Device device) {
        int size;
        try {
            size = Integer.parseInt(device.getCommunicationSettings().get("registers").toString());
        } catch (NumberFormatException | NullPointerException e) {
            size = 65536;
        }

        switch (device.getProtocol()) {
            case GENERIC:
                return new GenericData();
            case MODBUS:
                return new ModbusData(size);
            case XGT_PLC:
                return new XGTData(size);
            case DAEYUN_RCU:
                return new DaeyunData();
            case OPC_AGENT:
                return new OPCAgentData(size);
            case SNMP:
                return new SnmpData();
            case ETHERNET_IP:
                return new EthernetIPData();
            default:
                return null;
        }
    }

    @Bean
    @Scope("prototype")
    public ChannelInboundHandlerAdapter decoder(Device device, IOServer server) {
        String beanName = device.getProtocol().name().toLowerCase() + "_decoder_" + device.getTransport().name().toLowerCase();
        if(device.getTransport() == Transport.UDP)
            return (ChannelInboundHandlerAdapter) applicationContext.getBean(beanName, applicationContext, server);
        else
            return (ChannelInboundHandlerAdapter) applicationContext.getBean(beanName, server);
    }

    @Bean
    @Scope("prototype")
    public MkHandler handler(Device device, IOServer server) {
        return (MkHandler) applicationContext.getBean(device.getProtocol().name().toLowerCase(), applicationContext, server);
    }
}
