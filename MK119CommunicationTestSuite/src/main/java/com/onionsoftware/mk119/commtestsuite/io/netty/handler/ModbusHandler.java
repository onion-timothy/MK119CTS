package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ModbusData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.Checksum;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.Arrays;
import java.util.Optional;

public class ModbusHandler extends MkHandler {
    final SimulationService simulationService;

    public ModbusHandler(IOServer server, SimulationService simulationService) {
        super(server);
        this.simulationService = simulationService;
    }

    @Override
    protected void generateBuffer(ChannelHandlerContext ctx, Object msg) {
        ByteBuf out = ctx.alloc().buffer();
        Modbus modbus = (Modbus) msg;
        int length = modbus.getFc() == Modbus.FC_01 || modbus.getFc() == Modbus.FC_02 ? (int) Math.ceil((double) modbus.getRequestSize() / 8) : modbus.getRequestSize() * 2;

        if (modbus.getRequestSize() == 0x0080) {
            return;
        }

        // TCP Header
        if (modbus instanceof ModbusTCP) {
            ModbusTCP tcp = (ModbusTCP) modbus;
            out.writeShort(tcp.getTid());
            out.writeShort(ModbusTCP.PROTOCOL_ID);
            if(modbus.getFc() == Modbus.FC_16) {
                length = 3;
            }
            out.writeShort(length + 3);
        }

        // Common Header
        out.writeByte(modbus.getUid());
        out.writeByte(modbus.getFc());

        Optional<Simulation> simulationOptional;

        if (target == null) {
            ctx.close();
            return;
        }
        ModbusData modbusData = (ModbusData) target.getServerData();
        // Common Payload Data
        switch (modbus.getFc()) {
            case Modbus.FC_01:
            case Modbus.FC_02:
                out.writeByte(length);
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
                out.writeByte(length);
                short[] ret = Arrays.copyOfRange(modbus.getFc() == Modbus.FC_03 ? modbusData.getHolding_registers() : modbusData.getInput_registers(), modbus.getRegister(), modbus.getRegister() + modbus.getRequestSize());
                for (short value : ret) {
                    out.writeShort(value);
                }
                break;
            case Modbus.FC_05:
                length = 3;
                out.writeShort(modbus.getRegister());
                out.writeShort(modbus.getRequestSize());
                try {
                    modbusData.setServerData("1_" + modbus.getRegister(),
                            modbus.getRequestSize() == (short) 0xff00 ? 1.0 : 0.0,
                            null, ServerDataChangeSource.PROTOCOL_WRITE);
                } catch (Exception e) {
                    MkLogger.log("io", e, "Modbus FC_05 setServerData failed for register {0}", modbus.getRegister());
                }
                applyStopOnControlFor(modbusData, "1_" + modbus.getRegister());
                break;
            case Modbus.FC_06:
                length = 3;
                out.writeShort(modbus.getRegister());
                out.writeShort(modbus.getRequestSize());
                try {
                    modbusData.setServerData("3_" + modbus.getRegister(),
                            (double) modbus.getRequestSize(), DataType.SI16AB,
                            ServerDataChangeSource.PROTOCOL_WRITE);
                } catch (Exception e) {
                    MkLogger.log("io", e, "Modbus FC_06 setServerData failed for register {0}", modbus.getRegister());
                }
                applyStopOnControlFor(modbusData, "3_" + modbus.getRegister());
                break;
            case Modbus.FC_16:
                out.writeShort(modbus.getRegister());
                out.writeShort(modbus.getParamter().length / 2);
                break;
        }

        // RTU CRC
        if (modbus instanceof ModbusRTU) {
            byte[] toCRC = new byte[length + 3];
            out.getBytes(0, toCRC, 0, length + 3);
            short crc = Checksum.getCRC16(toCRC);
            out.writeShort(crc);
        }

        sendBuffer = out;
    }

    /**
     * 제어를 수용한 target 에 걸린 Simulation 을 찾아 stopOnControl 을 적용한다.
     * <p>
     * 예전에는 패킷마다 DB 에서 장비의 Simulation 을 모두 읽어 와 걸렀다. ServerData 의
     * 색인은 다중 레지스터가 차지하는 칸까지 들고 있어 같은 일을 조회 없이 해낸다.
     * function code 가 키에 들어 있으므로 코일(1_N) 이 레지스터 쓰기에 걸리지도 않는다.
     */
    private void applyStopOnControlFor(ServerData serverData, String target) {
        Simulation simulation = serverData.simulationFor(target);
        if (simulation != null) simulationService.applyStopOnControl(simulation);
    }
}
