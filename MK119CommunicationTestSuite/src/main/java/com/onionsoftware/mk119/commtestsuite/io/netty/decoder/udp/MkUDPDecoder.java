package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Getter
@Setter
@Component
@Scope("prototype")
public abstract class MkUDPDecoder extends MessageToMessageDecoder<DatagramPacket> implements MkDecoderInterface {
    private long rxCounter;
    private ChannelHandlerContext context;
    private long uptime;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.context = ctx;
        this.uptime = System.currentTimeMillis();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        rxCounter++;
    }
}
