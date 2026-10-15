package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.util.List;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class ModbusDecoder extends MkDecoder {
    int uid;
    int type;

    public ModbusDecoder(IOServer server) {
        super(server);
        this.uid = Integer.parseInt(target.getDevice().getCommunicationSettings().get("unitId").toString());
        this.type = Integer.parseInt(target.getDevice().getCommunicationSettings().get("packetType").toString());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);

        Modbus modbus = ByteBufUtils.parseModbusPacket(in, uid, type);
        if (modbus instanceof ModbusTCP && type == Modbus.MODBUS_TCP) {
            out.add(modbus);
        } else if (modbus instanceof ModbusRTU && type == Modbus.MODBUS_RTU) {
            out.add(modbus);
        } else MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
        ByteBufUtils.clear(in);
    }
}
