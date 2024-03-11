package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.*;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.*;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.*;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.BACnetReadProperty;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.BACnetReadPropertyMultiple;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration("ioServerFactory")
@RequiredArgsConstructor
public class IOServerFactory {
    final Logger logger = LoggerFactory.getLogger("io");
    final BACnetService bacnetService;
    final ObjectProvider<BACnetReadProperty> bacnetReadPropertyFactory;
    final ObjectProvider<BACnetReadPropertyMultiple> bacnetReadPropertyMultipleFactory;

    @Bean
    @Scope("prototype")
    public IOServer ioServer(Device device) {
        IOServer ioServer = new IOServer(device);
        ioServer.setServerData(getServerData(device));
        ChannelInboundHandlerAdapter decoder = getDecoder(device);
        if (decoder != null)
            ioServer.setDecoder(getDecoder(device));
        else {
            logger.error("Couldn't fetch decoder for device {}", device.getName());
            return null;
        }
        ChannelInboundHandlerAdapter handler = getHandler(device, ioServer.getServerData());
        if (handler != null)
            ioServer.setHandler(getHandler(device, ioServer.getServerData()));
        else {
            logger.error("Couldn't fetch handler for device {}", device.getName());
            return null;
        }

        return ioServer;
    }

    private ServerData getServerData(Device device) {
        switch (device.getProtocol()) {
            case GENERIC:
                return new GenericData();
            case MODBUS:
                return new ModbusData();
            case XGT_PLC:
                return new XGTData();
            case DAEYUN_RCU:
                return new DaeyunData();
            case OPC_AGENT:
                return new OPCAgentData(Integer.parseInt(device.getCommunicationSettings().get("size").toString()));
            case SNMP:
                return new SnmpData();
            default:
                return null;
        }
    }

    private ChannelInboundHandlerAdapter getDecoder(Device device) {
        ChannelInboundHandlerAdapter ret = null;
        switch (device.getProtocol()) {
            case GENERIC:
                ret = device.getTransport() == Transport.TCP ? new GenericTCPDecoder(device.getCommunicationSettings()) : new GenericUDPDecoder(device.getCommunicationSettings());
            case MODBUS:
                ret = device.getTransport() == Transport.TCP ? new ModbusDecoder(device.getCommunicationSettings()) : new ModbusUDPDecoder(device.getCommunicationSettings());
            case XGT_PLC:
                ret = device.getTransport() == Transport.TCP ? new XGTDecoder() : new XGTUDPDecoder();
            case DAEYUN_RCU:
                ret = device.getTransport() == Transport.TCP ? new DaeyunDecoder(device.getCommunicationSettings()) : new DaeyunUDPDecoder(device.getCommunicationSettings());
            case OPC_AGENT:
                ret = device.getTransport() == Transport.TCP ? new OPCAgentDecoder() : new OPCAgentUDPDecoder();
            case SNMP:
                ret = device.getTransport() == Transport.UDP ? new SnmpDecoder(device.getCommunicationSettings()) : null;
            case BACNET:
                ret = device.getTransport() == Transport.UDP ? new BACnetDecoder(bacnetReadPropertyFactory, bacnetReadPropertyMultipleFactory) : null;
        }
        return ret;
    }

    private ChannelInboundHandlerAdapter getHandler(Device device, ServerData data) {
        switch (device.getProtocol()) {
            case GENERIC:
                return new GenericTCPUDPHandler(device.getCommunicationSettings(), (GenericData) data);
            case MODBUS:
                return new ModbusHandler(device.getCommunicationSettings(), (ModbusData) data);
            case XGT_PLC:
                return new XGTHandler(device.getCommunicationSettings(), (XGTData) data);
            case DAEYUN_RCU:
                return new DaeyunHandler(device.getCommunicationSettings(), (DaeyunData) data);
            case OPC_AGENT:
                return new OPCAgentHandler(device.getCommunicationSettings(), (OPCAgentData) data);
            case SNMP:
                return new SnmpHandler((SnmpData) data);
            case BACNET:
                return new BACnetHandler(bacnetService);
            default:
                return null;
        }
    }
}
