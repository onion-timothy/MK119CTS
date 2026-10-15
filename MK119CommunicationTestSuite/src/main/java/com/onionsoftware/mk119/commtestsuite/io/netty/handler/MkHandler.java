package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.Random;

@Getter
@Setter
@RequiredArgsConstructor
public abstract class MkHandler extends ChannelInboundHandlerAdapter {
    private long txCounter;
    private ChannelHandlerContext context;
    protected ByteBuf sendBuffer;
    protected final IOServer target;
    private String transport;
    private InetSocketAddress sender;
    Random garbageGenerator = new Random();

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        context = ctx;
        transport = target.getDevice().getCommunicationSettings().get("transport").toString();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        generateBuffer(ctx, msg);
        if (sendBuffer != null) {
            // 시스템 에러 플래그 확인 (스케줄러가 세팅한 플래그 기반)
            Error systemError = target.getActiveSystemError();
            if (systemError != null) {
                if (systemError.getMode() == ErrorMode.DISCARD) {
                    sendBuffer.release();
                    sendBuffer = null;
                    return;
                } else if (systemError.getMode() == ErrorMode.TIMEOUT) {
                    try {
                        Thread.sleep(systemError.getTimeout() * 1000L);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                } else if (systemError.getMode() == ErrorMode.GARBAGE) {
                    sendBuffer.release();
                    ByteBuf ret = Unpooled.buffer();
                    for (int i = 0; i < systemError.getGarbageLength(); i++) {
                        ret.writeByte(garbageGenerator.nextInt(Byte.MAX_VALUE));
                    }
                    sendBuffer = ret;
                } else if (systemError.getMode() == ErrorMode.DISCONNECT) {
                    sendBuffer.release();
                    sendBuffer = null;
                    ctx.close();
                    return;
                }
            }

            if (transport.equals("UDP")) {
                ctx.writeAndFlush(new DatagramPacket(sendBuffer.retain(), sender));
            } else
                ctx.writeAndFlush(sendBuffer.retain());
            if (target.getDevice().isLogEnabled()) {
                ByteBufUtils.writeTXPacketLog(sendBuffer, this.getClass().getSimpleName(), target.getPacketLogger());
            }
            sendBuffer.release();
            txCounter++;
        }
        sendBuffer = null;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Writer buffer = new StringWriter();
        PrintWriter pw = new PrintWriter(buffer);
        cause.printStackTrace(pw);
        MkLogger.log("io", LogLevel.ERROR, "[{0}] packet handle error {1}\n{2}", this.getClass().getSimpleName(), cause,
                buffer.toString());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        target.getSessions().remove((InetSocketAddress) ctx.channel().remoteAddress());
        target.getHandlers().removeIf(this::equals);
        target.getDecoders()
                .removeIf(e -> e instanceof MkDecoderInterface && ((MkDecoderInterface) e).getContext().equals(ctx));
    }

    protected abstract void generateBuffer(ChannelHandlerContext ctx, Object msg);

    protected void regenerateBuffer(ChannelHandlerContext ctx, Object msg) {
        sendBuffer.clear();
        generateBuffer(ctx, msg);
    }
}
