package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.ireasoning.protocol.snmp.*;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.SnmpPdu;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.OPCAgentData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.SnmpData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component("snmp")
@Scope("prototype")
@RequiredArgsConstructor
public class SnmpHandler extends MkHandler {
    Logger logger = LoggerFactory.getLogger("io");
    private SnmpData snmpData;

    Device device;
    final ApplicationContext applicationContext;
    final IOServerInstances ioServerInstances;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        super.channelActive(ctx);
        IOServer target = null;
        List<IOServer> ioServers = new ArrayList<>(ioServerInstances.getServers().values());
        for (IOServer server : ioServers) {
            Optional<ChannelInboundHandlerAdapter> optionalDecoder = server.getHandlers().stream().filter(this::equals).findFirst();
            if (optionalDecoder.isPresent()) {
                target = server;
                break;
            }
        }
        if (target != null) {
            device = target.getDevice();
            snmpData = (SnmpData) applicationContext.getBean("serverData", device);
            target.setServerData(snmpData);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof SnmpPdu) {
            SnmpPdu packet = (SnmpPdu) msg;

            SnmpPdu ret = new SnmpPdu(SnmpConst.RESPONSE);
            ret.setDestinationAddress(packet.getSourceAddress());
            ret.setDestinationPort(packet.getDestinationPort());
            ret.setVersion(packet.getVersion());
            ret.setCommunity(packet.getCommunity());
            ret.setRequestID(packet.getRequestID());

            ret.addVarBinds(processGetRequest(packet));
            ByteBuf buffer = Unpooled.buffer();
            try {
                buffer.writeBytes(SnmpPdu.encodePdu(ret));
                ctx.writeAndFlush(new DatagramPacket(buffer, packet.getSender()));
                super.channelRead(ctx, msg);
            } catch (SnmpEncodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private SnmpVarBindList processGetRequest(SnmpPdu packet) {
        SnmpVarBindList ret = new SnmpVarBindList();
        for (SnmpVarBind snmpVarBind : packet.getVarBinds()) {
            SnmpOID oid = snmpVarBind.getName();
            String x = oid.toString();
            Double value = snmpData.getOidValueMap().get(x);
            if (value != null) {
                ret.add(new SnmpVarBind(oid, new SnmpOctetString(Double.toString(value))));
            } else {
                ret.add(new SnmpVarBind(oid, SnmpDataType.NO_SUCH_OBJECT_OBJECT));
            }
        }
        return ret;
    }
}
