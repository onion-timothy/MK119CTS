package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.util.ArrayList;
import java.util.List;

public class DaeyunUDPDecoder extends MkUDPDecoder {
    public DaeyunUDPDecoder(IOServer server) {
        super(server);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        Daeyun daeyun = ByteBufUtils.parseDaeyunPacket(in);
        if (daeyun != null) {
            out.add(daeyun);
        } else {
            LoggerFactory.getLogger("io").warn("Invalid packet received device {}({})", target.getDevice().getName(), target.getDevice().getId());
        }
        ByteBufUtils.clear(in);
    }
}
