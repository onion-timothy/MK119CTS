package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.ireasoning.protocol.snmp.SnmpConst;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.SnmpPdu;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class SnmpDecoder extends MessageToMessageDecoder<DatagramPacket> {
    private final Logger logger = LoggerFactory.getLogger("io");
    private final int version;
    private final String getCommunity;
    private final String setCommunity;

    public SnmpDecoder(Map<?, ?> communicationSettings) {
        this.version = Integer.parseInt(communicationSettings.get("snmpVersion").toString());
        this.getCommunity = communicationSettings.get("getCommunity").toString();
        this.setCommunity = communicationSettings.get("setCommunity").toString();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
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
}
