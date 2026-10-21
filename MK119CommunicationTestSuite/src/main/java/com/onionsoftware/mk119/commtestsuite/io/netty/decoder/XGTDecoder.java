package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;


import org.slf4j.LoggerFactory;
import java.util.List;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class XGTDecoder extends MkDecoder {

    public XGTDecoder(IOServer server) {
        super(server);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);
        XGT xgt = ByteBufUtils.parseXGTPacket(in);
        if (xgt != null) {
            out.add(xgt);
        } else {
            LoggerFactory.getLogger("io").warn("Invalid packet received device {}({})", target.getDevice().getName(), target.getDevice().getId());
        }
        ByteBufUtils.clear(in);
    }
}
