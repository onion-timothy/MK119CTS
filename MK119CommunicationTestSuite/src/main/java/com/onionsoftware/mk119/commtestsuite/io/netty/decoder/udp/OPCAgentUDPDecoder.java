package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.OPCAgent;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.buffer.ByteBuf;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("opc_agent_decoder_udp")
@Scope("prototype")
public class OPCAgentUDPDecoder extends MkUDPDecoder {

    public OPCAgentUDPDecoder(ApplicationContext applicationContext, IOServer server) {
        super(applicationContext, server);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        if (in.readableBytes() == 3) {
            OPCAgent agent = ByteBufUtils.parseOPCAgentPacket(in);
            if (agent != null) {
                agent.setSender(msg.sender());
                out.add(agent);
            } else {
                MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
            }
        }
        ByteBufUtils.clear(in);
    }
}
