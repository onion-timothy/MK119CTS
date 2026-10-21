package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;


import org.slf4j.LoggerFactory;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class DaeyunDecoder extends MkDecoder {
    private final List<RXFrame> acceptFrames;

    public DaeyunDecoder(IOServer ioServer) {
        super(ioServer);
        Type listType = new TypeToken<ArrayList<RXFrame>>() {}.getType();
        this.acceptFrames = new Gson().fromJson(target.getDevice().getCommunicationSettings().get("rx").toString(), listType);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);
        Daeyun daeyun = ByteBufUtils.parseDaeyunPacket(in, acceptFrames);
        if (daeyun != null) out.add(daeyun);
        else
            LoggerFactory.getLogger("io").warn("Invalid packet received device {}({})", target.getDevice().getName(), target.getDevice().getId());
        ByteBufUtils.clear(in);
    }
}
