package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.OPCAgent;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.OPCAgentData;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

public class OPCAgentHandler extends ChannelInboundHandlerAdapter {
    static Logger logger = LoggerFactory.getLogger("io");
    private final String transport;
    private final OPCAgentData opcAgentData;

    public OPCAgentHandler(Map<Object, Object> communicationSettings, OPCAgentData opcAgentData) {
        this.opcAgentData = opcAgentData;
        transport = communicationSettings.get("transport").toString();
    }

    private static final int DEFAULT_TAGS_LENGTH = 1023;

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[OPCAgentHandler] exception occur. {}", cause.getMessage());
        ctx.close();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof OPCAgent) {
            OPCAgent opc = (OPCAgent) msg;
            int start = opc.getStart();
            int length;
            if (DEFAULT_TAGS_LENGTH + start > opcAgentData.getValues().length) {
                length = opcAgentData.getValues().length - start;
            } else length = DEFAULT_TAGS_LENGTH;

            final ByteBuf out = ctx.alloc().buffer(length * 8 + 3);

            double[] ret = opcAgentData.getValues(start, length);
            out.writeShort(ret.length);
            for (double v : ret) {
                out.writeDouble(v);
            }

            byte[] bytes = new byte[ret.length * 8 + 2];
            out.getBytes(0, bytes);
            int sum = 0;
            for (byte b : bytes) {
                sum += b;
            }
            out.writeByte(sum & 0xff);

            if (transport.equals("UDP"))
                ctx.writeAndFlush(new DatagramPacket(out, opc.getSender()));
            else ctx.writeAndFlush(out);
        }
    }
}
