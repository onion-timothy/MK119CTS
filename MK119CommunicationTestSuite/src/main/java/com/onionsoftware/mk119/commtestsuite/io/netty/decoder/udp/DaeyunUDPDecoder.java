package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class DaeyunUDPDecoder extends MkUDPDecoder {
    private final List<RXFrame> acceptFrames;

    public DaeyunUDPDecoder(IOServer server) {
        super(server);
        Type listType = new TypeToken<ArrayList<RXFrame>>() {}.getType();
        this.acceptFrames = new Gson().fromJson(target.getDevice().getCommunicationSettings().get("rx").toString(), listType);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        Daeyun daeyun = ByteBufUtils.parseDaeyunPacket(in, acceptFrames);
        if (daeyun != null) {
            out.add(daeyun);
        } else {
            MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
        }
        ByteBufUtils.clear(in);
    }
}
