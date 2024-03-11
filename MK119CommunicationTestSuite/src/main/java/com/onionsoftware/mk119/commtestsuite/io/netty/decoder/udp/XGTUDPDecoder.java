package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class XGTUDPDecoder extends MessageToMessageDecoder<DatagramPacket> {
    Logger logger = LoggerFactory.getLogger("io");

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        ByteBuf in = msg.content();
        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());

        XGT xgt = ByteBufUtils.parseXGTPacket(in);
        if (xgt != null) {
            xgt.setSender(msg.sender());
            out.add(xgt);
        }
        ByteBufUtils.clear(in);
    }
}
