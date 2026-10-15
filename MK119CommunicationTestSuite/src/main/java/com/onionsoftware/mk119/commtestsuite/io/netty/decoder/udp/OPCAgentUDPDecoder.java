package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.OPCAgent;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.buffer.ByteBuf;

import java.util.List;

public class OPCAgentUDPDecoder extends MkUDPDecoder {

    public OPCAgentUDPDecoder(IOServer server) {
        super(server);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        if (in.readableBytes() == 3) {
            OPCAgent agent = ByteBufUtils.parseOPCAgentPacket(in);
            if (agent != null) {
                out.add(agent);
            } else {
                MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
            }
        }
        ByteBufUtils.clear(in);
    }
}
