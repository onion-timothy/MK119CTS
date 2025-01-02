package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGTVariable;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.XGTData;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component("xgt_plc")
@Scope("prototype")
public class XGTHandler extends MkHandler {

    public XGTHandler(ApplicationContext applicationContext, IOServer server) {
        super(applicationContext, server);
    }

    @Override
    protected void generateBuffer(ChannelHandlerContext ctx, Object msg) {
        ByteBuf out;

        if (msg instanceof XGT) {
            XGTData xgtData = (XGTData) target.getServerData();
            XGT xgt = (XGT) msg;
            out = ctx.alloc().buffer(32 + xgt.getBlockSize());
            // XGT Header.
            out.writeBytes("LSIS-XGT".getBytes(StandardCharsets.US_ASCII));
            out.writeShortLE(0);            // Don't care
            out.writeShortLE(0);            // Don't care
            out.writeByte(0);               // Don't care
            out.writeByte(0x11);
            out.writeShortLE(xgt.getInvokeId());
            out.writeShortLE(0);            // Don't care
            out.writeShortLE(0);            // Don't care
            out.writeShortLE(xgt.getCode() + 1);
            byte[] ret = null;

            if (xgt.getCode() == 0x54) {
                out.writeShortLE(xgt.getDataType());
                out.writeShortLE(0);            // Dont' care
                out.writeShortLE(0);            // Error code
                out.writeShortLE(1);            // Block count
                out.writeShortLE(xgt.getBlockSize());

                String variable = xgt.getVariable();
                char blockName = variable.charAt(1);
                int address = Integer.parseInt(variable.split("B")[1]);

                switch (blockName) {
                    case 'M':
                        ret = Arrays.copyOfRange(xgtData.getMblock(), address, address + xgt.getBlockSize());
                        break;
                    case 'D':
                        ret = Arrays.copyOfRange(xgtData.getDblock(), address, address + xgt.getBlockSize());
                        break;
                    case 'P':
                        ret = Arrays.copyOfRange(xgtData.getPblock(), address, address + xgt.getBlockSize());
                        break;
                    case 'K':
                        ret = Arrays.copyOfRange(xgtData.getKblock(), address, address + xgt.getBlockSize());
                        break;
                    case 'S':
                        ret = Arrays.copyOfRange(xgtData.getSblock(), address, address + xgt.getBlockSize());
                        break;
                    case 'U':
                        ret = Arrays.copyOfRange(xgtData.getUblock(), address, address + xgt.getBlockSize());
                        break;
                }

                if (ret != null) {
                    out.writeBytes(ret);
                }
            } else {
                out.writeShort(0);
                out.writeShort(0);
                out.writeShort(0);
                out.writeShortLE(1);
                for (XGTVariable variable : xgt.getVariableList()) {
                    int addr = extractNumbers(variable.getVarname());
                    String block = String.valueOf(variable.getVarname().charAt(1));
                    char dataTypeChar = variable.getVarname().charAt(2);
                    DataType dataType = null;
                    if (dataTypeChar == 'B') {
                        dataType = DataType.SI8;
                    } else if (dataTypeChar == 'W') {
                        addr *= 2;
                        dataType = DataType.SI16BA;
                    } else if (dataTypeChar == 'D') {
                        addr *= 4;
                        dataType = DataType.SI32DA;
                    }
                    try {
                        xgtData.setBlockData(block, dataType, (double) variable.getValue(), addr);
                        SimulationService simulationService = (SimulationService) applicationContext.getBean("simulationService");
                        Optional<Simulation> simulationOptional = simulationService.selectSimulationsByDeviceId(target.getDevice().getId()).stream().filter(e -> e.getTarget().equals(variable.getVarname())).findAny();
                        if (simulationOptional.isPresent()) {
                            Simulation simulation = simulationOptional.get();
                            if (simulation.getPolicies().get(0).isStopOnControl()) {
                                simulation.setEnabled(Simulation.DISABLED);
                                simulationService.updateSimulation(simulation);
                            }
                        }
                    } catch (Exception e) {
                        MkLogger.log("io", e, "XGTPLC Error on execute control.");
                    }
                }
            }
            sendBuffer = out.copy();
        }
    }

    private int extractNumbers(String input) {
        StringBuilder numbers = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isDigit(ch)) {
                numbers.append(ch);
            }
        }

        return Integer.parseInt(numbers.toString());
    }
}
