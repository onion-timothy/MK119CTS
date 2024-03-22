package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@Scope("prototype")
public abstract class MkDecoder extends ByteToMessageDecoder implements MkDecoderInterface {
    private long rxCounter;
    private ChannelHandlerContext context;
    private long uptime;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.context = ctx;
        this.uptime = System.currentTimeMillis();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        rxCounter++;
    }
}
