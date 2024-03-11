package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.util.List;
import java.util.Map;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class ModbusDecoder extends ByteToMessageDecoder {
    Logger logger = LoggerFactory.getLogger("io");
    int uid;
    int type;

    public ModbusDecoder(Map<?, ?> communicationSettings) {
        this.uid = Integer.parseInt(communicationSettings.get("unitId").toString());
        this.type = Integer.parseInt(communicationSettings.get("packetType").toString());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
        Modbus modbus = ByteBufUtils.parseModbusPacket(in, uid, type);
        if (modbus instanceof ModbusTCP && type == Modbus.MODBUS_TCP) {
            out.add(modbus);
        } else if (modbus instanceof ModbusRTU && type == Modbus.MODBUS_RTU) {
            out.add(modbus);
        }
        ByteBufUtils.clear(in);
    }
}
