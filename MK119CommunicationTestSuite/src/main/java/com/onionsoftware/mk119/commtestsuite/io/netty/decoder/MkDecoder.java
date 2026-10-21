package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;

@Getter
@Setter
@RequiredArgsConstructor
public abstract class MkDecoder extends ByteToMessageDecoder implements MkDecoderInterface {
    private long rxCounter;
    private ChannelHandlerContext context;
    protected final IOServer target;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.context = ctx;
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        rxCounter++;
        if(target.getDevice().isLogEnabled())
            ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName(), target.getPacketLogger());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Writer buffer = new StringWriter();
        PrintWriter pw = new PrintWriter(buffer);
        cause.printStackTrace(pw);
        LoggerFactory.getLogger("io").error("[{}] packet decode error {}\n{}", this.getClass().getSimpleName(), cause, buffer.toString());
    }
}
