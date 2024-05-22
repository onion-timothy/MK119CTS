package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetSocketAddress;

@Getter
@Setter
@RequiredArgsConstructor
@Component
@Scope("prototype")
public class MkHandler extends ChannelInboundHandlerAdapter {
    private long txCounter;
    private ChannelHandlerContext context;
    protected ByteBuf lastSent;
    protected final ApplicationContext applicationContext;
    protected final IOServer target;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        context = ctx;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        txCounter++;
        if (lastSent != null && target.getDevice().getLog() == Device.DEVICE_ENABLED) {
            ByteBufUtils.writeTXPacketLog(lastSent, this.getClass().getSimpleName(), target.getPacketLogger());
            lastSent = null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Writer buffer = new StringWriter();
        PrintWriter pw = new PrintWriter(buffer);
        cause.printStackTrace(pw);
        MkLogger.log("io", LogLevel.ERROR, "[{0}] packet handle error {1}\n{2}", this.getClass().getSimpleName(), cause, buffer.toString());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        target.getSessions().remove((InetSocketAddress) ctx.channel().remoteAddress());
        target.getHandlers().removeIf(this::equals);
        target.getDecoders().removeIf(e -> e instanceof MkDecoderInterface && ((MkDecoderInterface) e).getContext().equals(ctx));
    }
}
