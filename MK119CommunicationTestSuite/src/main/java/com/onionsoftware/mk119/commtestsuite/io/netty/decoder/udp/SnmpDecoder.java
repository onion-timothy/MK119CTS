package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;


import org.slf4j.LoggerFactory;
import com.ireasoning.protocol.snmp.SnmpConst;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.SnmpPdu;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;import java.util.List;
public class SnmpDecoder extends MkUDPDecoder {
    private final int version;
    private final String getCommunity;
    private final String setCommunity;

    public SnmpDecoder(IOServer server) {
        super(server);
        this.version = Integer.parseInt(target.getDevice().getCommunicationSettings().get("snmpVersion").toString());
        this.getCommunity = target.getDevice().getCommunicationSettings().get("getCommunity").toString();
        this.setCommunity = target.getDevice().getCommunicationSettings().get("setCommunity").toString();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf buffer = msg.content();
        byte[] cpy = new byte[buffer.readableBytes()];
        buffer.readBytes(cpy);
        SnmpPdu pdu = new SnmpPdu(SnmpPdu.decodePdu(cpy, cpy.length));
        if (pdu.getVersion() == this.version) {
            if (pdu.getCommunity().equals(this.getCommunity) && pdu.getCommand() == SnmpConst.GET) {
                out.add(pdu);
            } else if (pdu.getCommunity().equals(this.setCommunity) && pdu.getCommand() == SnmpConst.SET) {
                out.add(pdu);
            } else if (pdu.getCommunity().equals(this.getCommunity) && pdu.getCommand() == SnmpConst.GET_BULK && this.version == SnmpConst.SNMPV2) {
                out.add(pdu);
            } else {
                LoggerFactory.getLogger("io").warn("SNMP Error community or command {} {} for device {}({})", pdu.getCommunity(), pdu.getCommand(), target.getDevice().getName(), target.getDevice().getId());
            }
        } else {
            LoggerFactory.getLogger("io").warn("SNMP Error version {} {} for device {}({})", this.version, pdu.getVersion(), target.getDevice().getName(), target.getDevice().getId());
        }
    }
}
