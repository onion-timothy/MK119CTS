package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class GenericTCPDecoder extends ByteToMessageDecoder {
    Logger logger = LoggerFactory.getLogger("io");
    private final List<RXFrame> acceptFrames;

    public GenericTCPDecoder(Map<?, ?> communicationSettings) {
        Type listType = new TypeToken<ArrayList<RXFrame>>() {}.getType();
        this.acceptFrames = new Gson().fromJson(communicationSettings.get("rx").toString(), listType);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
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
            out.add(packet);
        }
    }
}
