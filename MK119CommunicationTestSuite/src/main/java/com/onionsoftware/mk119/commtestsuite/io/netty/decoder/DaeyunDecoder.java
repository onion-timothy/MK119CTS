package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class DaeyunDecoder extends ByteToMessageDecoder {
    Logger logger = LoggerFactory.getLogger("io");
    private final List<RXFrame> acceptFrames;
    static Gson parser = new Gson();

    public DaeyunDecoder(Map<?, ?> communicationSettings) {
        Type listType = new TypeToken<ArrayList<RXFrame>>() {
        }.getType();
        this.acceptFrames = new Gson().fromJson(communicationSettings.get("rx").toString(), listType);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
        Daeyun daeyun = ByteBufUtils.parseDaeyunPacket(in, acceptFrames);
        if (daeyun != null) out.add(daeyun);
        ByteBufUtils.clear(in);
    }
}
