package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ireasoning.protocol.snmp.SnmpConst;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.SnmpPdu;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ExceptionUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Configuration("snmp_decoder_udp")
@RequiredArgsConstructor
public class SnmpDecoder extends MkUDPDecoder {
    private final Logger logger = LoggerFactory.getLogger("io");
    private int version;
    private String getCommunity;
    private String setCommunity;

    Device device;
    final ApplicationContext applicationContext;
    final IOServerInstances ioServerInstances;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        IOServer target = null;
        List<IOServer> ioServers = new ArrayList<>(ioServerInstances.getServers().values());
        for (IOServer server : ioServers) {
            Optional<ChannelInboundHandlerAdapter> optionalDecoder = server.getDecoders().stream().filter(this::equals).findFirst();
            if (optionalDecoder.isPresent()) {
                target = server;
                break;
            }
        }
        if (target != null) {
            device = target.getDevice();
            this.version = Integer.parseInt(device.getCommunicationSettings().get("snmpVersion").toString());
            this.getCommunity = device.getCommunicationSettings().get("getCommunity").toString();
            this.setCommunity = device.getCommunicationSettings().get("setCommunity").toString();
            ctx.fireChannelActive();
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf buffer = msg.content();
        byte[] cpy = new byte[buffer.readableBytes()];
        buffer.readBytes(cpy);
        SnmpPdu pdu = new SnmpPdu(SnmpPdu.decodePdu(cpy, cpy.length));
        pdu.setSender(msg.sender());
        if (pdu.getVersion() == this.version) {
            if (pdu.getCommunity().equals(this.getCommunity) && pdu.getCommand() == SnmpConst.GET) {
                out.add(pdu);
            } else if (pdu.getCommunity().equals(this.setCommunity) && pdu.getCommand() == SnmpConst.SET) {
                out.add(pdu);
            } else if (pdu.getCommunity().equals(this.getCommunity) && pdu.getCommand() == SnmpConst.GET_BULK && this.version == SnmpConst.SNMPV2) {
                out.add(pdu);
            } else logger.error("Error community or command: {} {}", pdu.getCommunity(), pdu.getCommand());
        } else logger.error("Error snmp version: expected {} received {}", this.version, pdu.getVersion());

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[SnmpDecoder] exception occur. {}", cause.getMessage());
        ExceptionUtil.printStackTrace(cause);
        ctx.close();
    }
}
