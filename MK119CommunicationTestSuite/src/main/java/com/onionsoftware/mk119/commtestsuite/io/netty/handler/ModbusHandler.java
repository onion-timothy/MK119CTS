package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ModbusData;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.CRC16;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

@Component("modbus")
@Scope("prototype")
public class ModbusHandler extends MkHandler {
    final SimulationService simulationService;

    public ModbusHandler(ApplicationContext applicationContext, IOServer server) {
        super(applicationContext, server);
        simulationService = applicationContext.getBean(SimulationService.class);
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
                modbusData.setCoil_status(modbus.getRequestSize() == (short) 0xff00, modbus.getRegister());
                simulationOptional = simulationService.selectSimulationsByDeviceId(target.getDevice().getId()).stream().filter(e -> e.getTarget().equals("1_" + modbus.getRegister())).findAny();
                if (simulationOptional.isPresent() && simulationOptional.get().getPolicies().get(0).isStopOnControl()) {
                    simulationOptional.get().setEnabled(Simulation.DISABLED);
                    simulationService.updateSimulation(simulationOptional.get());
                }
                break;
            case Modbus.FC_06:
                length = 3;
                out.writeShort(modbus.getRegister());
                out.writeShort(modbus.getRequestSize());
                modbusData.setHolding_registers((double) modbus.getRequestSize(), DataType.SI16AB, modbus.getRegister());
                simulationOptional = simulationService.selectSimulationsByDeviceId(target.getDevice().getId()).stream().filter(e -> translateTarget(e, modbus.getRegister())).findAny();
                if (simulationOptional.isPresent() && simulationOptional.get().getPolicies().get(0).isStopOnControl()) {
                    simulationOptional.get().setEnabled(Simulation.DISABLED);
                    simulationService.updateSimulation(simulationOptional.get());
                }
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
            short crc = CRC16.getCRC16(toCRC);
            out.writeShort(crc);
        }

        sendBuffer = out;
    }

    private boolean translateTarget(Simulation simulation, int register) {
        if (simulation.getTarget().startsWith("4")) return false;
        int startRegister = Integer.parseInt(simulation.getTarget().split("_")[1]);
        int byteCount = simulation.getPolicies().get(0).getData_type().getByteCount();
        return register >= startRegister && register < startRegister + byteCount;
    }
}
