package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.OPCAgent;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class OPCAgentDecoder extends ByteToMessageDecoder {
    Logger logger = LoggerFactory.getLogger("io");

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() == 3) {
            ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
            OPCAgent agent = ByteBufUtils.parseOPCAgentPacket(in);
            if (agent != null) out.add(agent);
        }
        ByteBufUtils.clear(in);
    }
}
