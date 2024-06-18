package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.MkHandler;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Getter
@Setter
@Component
@RequiredArgsConstructor
@Scope("prototype")
public abstract class MkUDPDecoder extends MessageToMessageDecoder<DatagramPacket> implements MkDecoderInterface {
    private long rxCounter;
    private ChannelHandlerContext context;
    final ApplicationContext applicationContext;
    protected final IOServer target;
    private MkHandler handler;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.context = ctx;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        rxCounter++;
        handler.setSender(msg.sender());
        if(target.getDevice().getLog() == Device.DEVICE_ENABLED)
            ByteBufUtils.writeRXPacketLog(msg.content(), this.getClass().getSimpleName(), target.getPacketLogger());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        MkLogger.log("io", LogLevel.ERROR, "[{0}}] packet decode error {1}", this.getClass().getSimpleName(), cause);
    }
}
