package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;


import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class GenericUDPDecoder extends MkUDPDecoder {
    private final List<RXFrame> acceptFrames;

    public GenericUDPDecoder(IOServer server) {
        super(server);
        Type listType = new TypeToken<ArrayList<RXFrame>>() {}.getType();
        this.acceptFrames = new Gson().fromJson(target.getDevice().getCommunicationSettings().get("rx").toString(), listType);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        GenericTCPUDPPacket packet = null;

        for (RXFrame frame : acceptFrames) {
            packet = ByteBufUtils.parsePacket(frame, in);
            if (packet == null) {
                in.resetReaderIndex();
            } else break;
        }

        if (packet == null) {
            ByteBufUtils.clear(in);
            LoggerFactory.getLogger("io").warn("Invalid packet received device {}({})", target.getDevice().getName(), target.getDevice().getId());
        } else {
            out.add(packet);
        }
    }
}
