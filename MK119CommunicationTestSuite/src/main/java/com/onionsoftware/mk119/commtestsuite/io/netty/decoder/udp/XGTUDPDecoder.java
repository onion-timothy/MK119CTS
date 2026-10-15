package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.util.List;

public class XGTUDPDecoder extends MkUDPDecoder {

    public XGTUDPDecoder(IOServer server) {
        super(server);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();

        XGT xgt = ByteBufUtils.parseXGTPacket(in);
        if (xgt != null) {
            out.add(xgt);
        } else MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
        ByteBufUtils.clear(in);
    }
}
