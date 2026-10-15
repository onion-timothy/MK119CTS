package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.MkHandler;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration("ioServerFactory")
@RequiredArgsConstructor
public class IOServerFactory {
    private final ErrorService errorService;
    private final com.onionsoftware.mk119.commtestsuite.db.service.SimulationService simulationService;
    private final com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService bacnetService;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler;

    @Bean
    @Scope("prototype")
    public IOServer ioServer(Device device) {
        device.getCommunicationSettings().put("transport", device.getTransport().name());
        IOServer ioServer = new IOServer(device, errorService.selectEnabledErrorsByDeviceId(device.getId()));
        ioServer.setTaskScheduler(taskScheduler);
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
                return new EthernetIPData(device.getCommunicationSettings());
            case BACNET:
                return new BACnetData(bacnetService);
            default:
                return null;
        }
    }

    public ChannelInboundHandlerAdapter decoder(Device device, IOServer server) {
        if (device.getTransport() == Transport.UDP) {
            switch (device.getProtocol()) {
                case GENERIC: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.GenericUDPDecoder(server);
                case MODBUS: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.ModbusUDPDecoder(server);
                case XGT_PLC: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.XGTUDPDecoder(server);
                case DAEYUN_RCU: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.DaeyunUDPDecoder(server);
                case OPC_AGENT: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.OPCAgentUDPDecoder(server);
                case SNMP: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.SnmpDecoder(server);
                case BACNET: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.BACnetDecoder(server, bacnetService);
                // EthernetIP UDP Decoder is not present
                default: return null;
            }
        } else {
            switch (device.getProtocol()) {
                case GENERIC: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.GenericTCPDecoder(server);
                case MODBUS: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.ModbusDecoder(server);
                case XGT_PLC: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.XGTDecoder(server);
                case DAEYUN_RCU: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.DaeyunDecoder(server);
                case OPC_AGENT: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.OPCAgentDecoder(server);
                case ETHERNET_IP: return new com.onionsoftware.mk119.commtestsuite.io.netty.decoder.EthernetIPDecoder(server);
                default: return null;
            }
        }
    }

    public MkHandler handler(Device device, IOServer server) {
        switch (device.getProtocol()) {
            case GENERIC: return new com.onionsoftware.mk119.commtestsuite.io.netty.handler.GenericTCPUDPHandler(server);
            case MODBUS: return new com.onionsoftware.mk119.commtestsuite.io.netty.handler.ModbusHandler(server, simulationService);
            case XGT_PLC: return new com.onionsoftware.mk119.commtestsuite.io.netty.handler.XGTHandler(server, simulationService);
            case DAEYUN_RCU: return new com.onionsoftware.mk119.commtestsuite.io.netty.handler.DaeyunHandler(server);
            case OPC_AGENT: return new com.onionsoftware.mk119.commtestsuite.io.netty.handler.OPCAgentHandler(server, simulationService);
            case SNMP: return new com.onionsoftware.mk119.commtestsuite.io.netty.handler.SnmpHandler(server, simulationService);
            case ETHERNET_IP: return new com.onionsoftware.mk119.commtestsuite.io.netty.handler.EthernetIPHandler(server, simulationService);
            case BACNET: return new com.onionsoftware.mk119.commtestsuite.io.netty.handler.BACnetHandler(server, bacnetService, simulationService);
            default: return null;
        }
    }
}
