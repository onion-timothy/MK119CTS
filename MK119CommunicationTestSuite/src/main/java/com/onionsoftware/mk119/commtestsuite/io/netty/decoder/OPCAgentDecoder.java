package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.OPCAgent;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

public class OPCAgentDecoder extends MkDecoder {

    public OPCAgentDecoder(IOServer server) {
        super(server);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);
        if (in.readableBytes() == 3) {
            OPCAgent agent = ByteBufUtils.parseOPCAgentPacket(in);
            if (agent != null) out.add(agent);
            else {
                MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
            }
        }
        ByteBufUtils.clear(in);
    }
}
