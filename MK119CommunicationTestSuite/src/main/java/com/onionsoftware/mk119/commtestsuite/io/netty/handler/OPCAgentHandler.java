package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.OPCAgent;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.OPCAgentData;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class OPCAgentHandler extends MkHandler {
    private final SimulationService simulationService;

    public OPCAgentHandler(IOServer server, SimulationService simulationService) {
        super(server);
        this.simulationService = simulationService;
    }

    private static final int DEFAULT_TAGS_LENGTH = 2000;

    @Override
    protected void generateBuffer(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof OPCAgent) {
            OPCAgentData opcAgentData = (OPCAgentData) target.getServerData();
            OPCAgent opc = (OPCAgent) msg;
            int start = opc.getStart();
            int length;
            if (DEFAULT_TAGS_LENGTH + start > opcAgentData.getValues().length) {
                length = opcAgentData.getValues().length - start;
            } else length = DEFAULT_TAGS_LENGTH;

            ByteBuf out = ctx.alloc().buffer(length * 8 + 3);

            double[] values = opcAgentData.getValues(start, length);
            out.writeShort(values.length);
            for (double v : values) {
                out.writeDouble(v);
            }

            byte[] bytes = new byte[values.length * 8 + 2];
            out.getBytes(0, bytes);
            int sum = 0;
            for (byte b : bytes) {
                sum += b;
            }
            out.writeByte(sum & 0xff);

            sendBuffer = out.copy();
        }
    }
}
