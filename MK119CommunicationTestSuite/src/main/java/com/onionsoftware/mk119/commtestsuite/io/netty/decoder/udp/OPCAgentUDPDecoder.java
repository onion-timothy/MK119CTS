package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.OPCAgent;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.buffer.ByteBuf;

import java.util.List;

public class OPCAgentUDPDecoder extends MessageToMessageDecoder<DatagramPacket> {
    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        ByteBuf in = msg.content();
        if (in.readableBytes() == 3) {
            ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
            OPCAgent agent = ByteBufUtils.parseOPCAgentPacket(in);
            if (agent != null) {
                agent.setSender(msg.sender());
                out.add(agent);
            }
        }
        ByteBufUtils.clear(in);
    }
}
