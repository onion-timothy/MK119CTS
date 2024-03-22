package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

@Getter
@Setter
@RequiredArgsConstructor
@Component()
@Scope("prototype")
public class MkHandler extends ChannelInboundHandlerAdapter {
    private long txCounter;
    private ChannelHandlerContext context;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        context = ctx;
    }
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        txCounter++;
    }
}
