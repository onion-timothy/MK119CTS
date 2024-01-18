package com.onionsoftware.mk119.commtestsuite.io.handler;

import com.onionsoftware.mk119.commtestsuite.io.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.tool.CRC16;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;

public class ModbusHandler extends ChannelInboundHandlerAdapter {
    static Logger logger = LoggerFactory.getLogger("io");
    private BitSet coil_status = new BitSet(65536);
    private BitSet input_status = new BitSet(65536);
    private short[] holding_registers = new short[65536];
    private short[] input_registers = new short[65536];

    public ModbusHandler() {
        for (int i = 0; i < Byte.MAX_VALUE; i++) {
            int index = i * 8;
            for (int j = 7; j >= 0; j--) {
                coil_status.set(index + (7 - j), ((i >> j) & 1) == 1);
            }
        }

        for (int i = 0; i < Short.MAX_VALUE; i++) {
            holding_registers[i] = (short) i;
        }
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
                        values[i] += index < end && (modbus.getFc() == Modbus.FC_01 ? coil_status.get(index) : input_status.get(index)) ? 1 << j : 0;
                    }
                }
                out.writeBytes(values);
                break;
            case Modbus.FC_03:
            case Modbus.FC_04:
                short[] ret = Arrays.copyOfRange(modbus.getFc() == Modbus.FC_03 ? holding_registers : input_registers, modbus.getRegister(), modbus.getRegister() + modbus.getRequestSize());
                for (int i = 0; i < ret.length; i++) {
                    out.writeShort(ret[i]);
                }
                break;
        }

        // RTU CRC
        if (modbus instanceof ModbusRTU) {
            byte[] toCRC = new byte[length + 3];
            out.getBytes(0, toCRC, 0, length + 3);
            short crc = CRC16.getCRC16(toCRC);
            out.writeShort(crc);
        }

        ctx.writeAndFlush(out);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    /**
     * INPUT 영역의 시뮬레이션 값을 설정한다.<br>
     * 외부의 register 관리자가 유저 설정에 따라 지정하도록 한다.<br>
     * 핸들러는 단순히 레지스터를 return만 한다.
     *
     * @param value         설정 값
     * @param type          데이터 타입
     * @param startRegister 시작 레지스터 주소
     */
    public void setInput_registers(Double value, DataType type, int startRegister) {

    }

    /**
     * HOLDING 영역의 시뮬레이션 값을 설정한다.<br>
     * 외부의 register 관리자가 유저 설정에 따라 지정한다.<br>
     * 또한, Modbus 제어로도 지정할 수 있다.<br>
     * 핸들러는 단순히 레지스터를 return만 한다.
     *
     * @param value         설정 값
     * @param type
     * @param startRegister
     */
    public void setHolding_registers(Double value, DataType type, int startRegister) {

    }
}
