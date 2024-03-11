package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GenericUDPDecoder extends MessageToMessageDecoder<DatagramPacket> {
    Logger logger = LoggerFactory.getLogger("io");
    private final List<RXFrame> acceptFrames;

    public GenericUDPDecoder(Map<?, ?> communicationSettings) {
        Type listType = new TypeToken<ArrayList<RXFrame>>() {}.getType();
        this.acceptFrames = new Gson().fromJson(communicationSettings.get("rx").toString(), listType);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        ByteBuf in = msg.content();
        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
        GenericTCPUDPPacket packet = null;

        for (RXFrame frame : acceptFrames) {
            packet = ByteBufUtils.parsePacket(frame, in);
            if (packet == null) {
                in.resetReaderIndex();
            } else break;
        }

        if (packet == null) {
            ByteBufUtils.clear(in);
            logger.info("No matched frame.");
        } else {
            packet.setSender(msg.sender());
            out.add(packet);
        }
    }
}
