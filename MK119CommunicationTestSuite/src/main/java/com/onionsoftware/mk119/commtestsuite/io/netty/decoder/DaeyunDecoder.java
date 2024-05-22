package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("daeyun_rcu_decoder_tcp")
@Scope("prototype")
public class DaeyunDecoder extends MkDecoder {
    private List<RXFrame> acceptFrames;

    public DaeyunDecoder(IOServer ioServer) {
        super(ioServer);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        Type listType = new TypeToken<ArrayList<RXFrame>>() {}.getType();
        this.acceptFrames = new Gson().fromJson(target.getDevice().getCommunicationSettings().get("rx").toString(), listType);
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);
        Daeyun daeyun = ByteBufUtils.parseDaeyunPacket(in, acceptFrames);
        if (daeyun != null) out.add(daeyun);
        else
            MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
        ByteBufUtils.clear(in);
    }
}
