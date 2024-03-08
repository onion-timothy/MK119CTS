package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.BitSet;

public class ModbusData implements ServerData {
    private final BitSet coil_status = new BitSet(65536);
    private final BitSet input_status = new BitSet(65536);
    private final short[] holding_registers = new short[65536];
    private final short[] input_registers = new short[65536];

    public void setInput_registers(Double value, DataType type, int startRegister) {
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeData(buf, type, value);
        writeByteBufToRegisters(buf, input_registers, startRegister);
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
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeData(buf, type, value);
        writeByteBufToRegisters(buf, holding_registers, startRegister);
    }

    private void writeByteBufToRegisters(ByteBuf buf, short[] registers, int startRegister) {
        int shortCount = buf.readableBytes() / 2;
        for (int i = 0; i < shortCount; i++) {
            registers[startRegister] = buf.readShort();
            startRegister++;
        }
    }

    public void setCoil_status(boolean value, int coil) {
        coil_status.set(coil, value);
    }

    public void setInput_status(boolean value, int input) {
        input_status.set(input, value);
    }

    public BitSet getCoil_status() {
        return coil_status;
    }

    public BitSet getInput_status() {
        return input_status;
    }

    public short[] getHolding_registers() {
        return holding_registers;
    }

    public short[] getInput_registers() {
        return input_registers;
    }
}
