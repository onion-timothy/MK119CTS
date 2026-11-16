package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;


import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class DaeyunDecoder extends MkDecoder {
    public DaeyunDecoder(IOServer ioServer) {
        super(ioServer);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);
        Daeyun daeyun = ByteBufUtils.parseDaeyunPacket(in);
        if (daeyun != null) out.add(daeyun);
        else
            LoggerFactory.getLogger("io").warn("Invalid packet received device {}({})", target.getDevice().getName(), target.getDevice().getId());
        ByteBufUtils.clear(in);
    }
}
