package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import java.util.List;

public class ModbusUDPDecoder extends MkUDPDecoder {
    final int uid;
    final int type;

    public ModbusUDPDecoder(IOServer server) {
        super(server);
        this.uid = Integer.parseInt(target.getDevice().getCommunicationSettings().get("unitId").toString());
        this.type = Integer.parseInt(target.getDevice().getCommunicationSettings().get("packetType").toString());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        Modbus modbus = ByteBufUtils.parseModbusPacket(in, uid, type);

        if (modbus != null) {
            out.add(modbus);
        } else {
            LoggerFactory.getLogger("io").warn("Invalid packet received device {}({})", target.getDevice().getName(), target.getDevice().getId());
        }

        ByteBufUtils.clear(in);
    }
}
