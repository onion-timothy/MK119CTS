package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.InvocationTargetException;
import java.util.BitSet;

@Getter
@Setter
public class ModbusData implements ServerData {
    private BitSet coil_status;
    private BitSet input_status;
    private short[] holding_registers;
    private short[] input_registers;

    public ModbusData(int registerCount){
        coil_status = new BitSet(registerCount);
        input_status = new BitSet(registerCount);
        holding_registers = new short[registerCount];
        input_registers = new short[registerCount];
    }

    public void setInput_registers(Double value, DataType type, int startRegister) {
        if(type == null){
            MkLogger.log("simulation", LogLevel.ERROR, "Invalid data type for simulation.");
            return;
        }
        try {
            ByteBuf buf = Unpooled.buffer();
            ByteBufUtils.writeData(buf, type, value);
            writeByteBufToRegisters(buf, input_registers, startRegister);
        } catch (Exception e) {
            MkLogger.log("simulation", e, "Reflection error write modbus simluation value.");
        }
    }

    /**
     * HOLDING 영역의 시뮬레이션 값을 설정한다.<br>
     * 외부의 register 관리자가 유저 설정에 따라 지정한다.<br>
     * 또한, Modbus 제어로도 지정할 수 있다.<br>
     * 핸들러는 단순히 레지스터를 return만 한다.
     *
     * @param value         설정 값
     * @param type          데이터타입(Enum)
     * @param startRegister 시작 주소
     */
    public void setHolding_registers(Double value, DataType type, int startRegister) {
        if(type == null){
            MkLogger.log("simulation", LogLevel.ERROR, "Invalid data type for simulation.");
            return;
        }
        try {
            ByteBuf buf = Unpooled.buffer();
            ByteBufUtils.writeData(buf, type, value);
            writeByteBufToRegisters(buf, holding_registers, startRegister);
        } catch (Exception e) {
            MkLogger.log("simulation", e, "Reflection error write modbus simluation value.");
        }
    }

    private void writeByteBufToRegisters(ByteBuf buf, short[] registers, int startRegister) {
        int shortCount = buf.readableBytes() / 2;
        for (int i = 0; i < shortCount; i++) {
            short v = buf.readShort();
            registers[startRegister] = v;
            startRegister++;
        }
    }

    public void setCoil_status(boolean value, int coil) {
        coil_status.set(coil, value);
    }

    public void setInput_status(boolean value, int input) {
        input_status.set(input, value);
    }

    @Override
    public void resetServerData() {
        int registerCount = holding_registers.length;
        coil_status = new BitSet(registerCount);
        input_status = new BitSet(registerCount);
        holding_registers = new short[registerCount];
        input_registers = new short[registerCount];
    }
}
