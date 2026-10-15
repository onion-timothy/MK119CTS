package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGTVariable;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.XGTData;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class XGTHandler extends MkHandler {
    private final SimulationService simulationService;

    public XGTHandler(IOServer server, SimulationService simulationService) {
        super(server);
        this.simulationService = simulationService;
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
                    char dataTypeChar = variable.getVarname().charAt(2);
                    DataType dataType = null;
                    if (dataTypeChar == 'B') {
                        dataType = DataType.SI8;
                    } else if (dataTypeChar == 'W') {
                        dataType = DataType.SI16BA;
                    } else if (dataTypeChar == 'D') {
                        dataType = DataType.SI32DA;
                    }
                    try {
                        xgtData.setServerData(variable.getVarname(), (double) variable.getValue(),
                                dataType, ServerDataChangeSource.PROTOCOL_WRITE);
                        Simulation hit = xgtData.simulationFor(variable.getVarname());
                        if (hit != null) simulationService.applyStopOnControl(hit);
                    } catch (Exception e) {
                        MkLogger.log("io", e, "XGTPLC Error on execute control.");
                    }
                }
            }
            sendBuffer = out.copy();
        }
    }
}
