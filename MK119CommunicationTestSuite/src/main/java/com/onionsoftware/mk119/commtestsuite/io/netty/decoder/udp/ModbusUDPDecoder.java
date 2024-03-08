package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class ModbusUDPDecoder extends MessageToMessageDecoder<DatagramPacket> {
    Logger logger = LoggerFactory.getLogger("io");
    int uid;
    int type = -1;

    public ModbusUDPDecoder(Map<?, ?> communicationSettings) {
        this.uid = Integer.parseInt(communicationSettings.get("unitId").toString());
        this.type = Integer.parseInt(communicationSettings.get("packetType").toString());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        ByteBuf in = msg.content();
        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
        Modbus modbus = ByteBufUtils.parseModbusPacket(in, uid, type);
        modbus.setSender(msg.sender());
        if (modbus != null && modbus instanceof ModbusTCP && type == Modbus.MODBUS_TCP) {
            out.add(modbus);
        } else if (modbus != null && modbus instanceof ModbusRTU && type == Modbus.MODBUS_RTU) {
            out.add(modbus);
        }
        ByteBufUtils.clear(in);
    }
}
