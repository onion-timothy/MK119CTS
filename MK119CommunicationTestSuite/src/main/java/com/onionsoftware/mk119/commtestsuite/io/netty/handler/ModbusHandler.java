package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ModbusData;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.CRC16;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component("modbus")
@Scope("prototype")
public class ModbusHandler extends MkHandler {
    private String transport;
    private ModbusData modbusData;

    public ModbusHandler(ApplicationContext applicationContext, IOServerInstances ioServerInstances) {
        super(applicationContext, ioServerInstances);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        modbusData = (ModbusData) applicationContext.getBean("serverData", target.getDevice());
        target.setServerData(modbusData);
        transport = target.getDevice().getCommunicationSettings().get("transport").toString();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf out = ctx.alloc().buffer();
        Modbus modbus = (Modbus) msg;
        int length = modbus.getFc() == Modbus.FC_01 || modbus.getFc() == Modbus.FC_02 ? (int) Math.ceil((double) modbus.getRequestSize() / 8) : modbus.getRequestSize() * 2;

        // TCP Header
        if (modbus instanceof ModbusTCP) {
            ModbusTCP tcp = (ModbusTCP) modbus;
            out.writeShort(tcp.getTid());
            out.writeShort(ModbusTCP.PROTOCOL_ID);
            out.writeShort(length + 3);
        }

        // Common Header
        out.writeByte(modbus.getUid());
        out.writeByte(modbus.getFc());
        out.writeByte(length);

        // Common Payload Data
        switch (modbus.getFc()) {
            case Modbus.FC_01:
            case Modbus.FC_02:
                int end = modbus.getRegister() + modbus.getRequestSize();

                byte[] values = new byte[length];
                for (int i = 0; i < values.length; i++) {
                    values[i] = 0;
                    for (int j = 0; j < 8; j++) {
                        int index = i * 8 + modbus.getRegister() + j;
                        values[i] += (byte) (index < end && (modbus.getFc() == Modbus.FC_01 ? modbusData.getCoil_status().get(index) : modbusData.getInput_status().get(index)) ? 1 << j : 0);
                    }
                }
                out.writeBytes(values);
                break;
            case Modbus.FC_03:
            case Modbus.FC_04:
                short[] ret = Arrays.copyOfRange(modbus.getFc() == Modbus.FC_03 ? modbusData.getHolding_registers() : modbusData.getInput_registers(), modbus.getRegister(), modbus.getRegister() + modbus.getRequestSize());
                for (short value : ret) {
                    out.writeShort(value);
                }
                break;
            case Modbus.FC_05:
                break;
            case Modbus.FC_06:
                break;
        }

        // RTU CRC
        if (modbus instanceof ModbusRTU) {
            byte[] toCRC = new byte[length + 3];
            out.getBytes(0, toCRC, 0, length + 3);
            short crc = CRC16.getCRC16(toCRC);
            out.writeShort(crc);
        }
        lastSent = out.copy();
        if (transport.equals("UDP")) {
            ctx.writeAndFlush(new DatagramPacket(out, modbus.getSender()));
        } else ctx.writeAndFlush(out);
        super.channelRead(ctx, msg);
    }
}
