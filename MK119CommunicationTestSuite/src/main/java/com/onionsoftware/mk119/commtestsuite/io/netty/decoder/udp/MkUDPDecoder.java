package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.MkHandler;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Getter
@Setter
@RequiredArgsConstructor
public abstract class MkUDPDecoder extends MessageToMessageDecoder<DatagramPacket> implements MkDecoderInterface {
    private long rxCounter;
    private ChannelHandlerContext context;
    protected final IOServer target;
    private MkHandler handler;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.context = ctx;
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        rxCounter++;
        handler.setSender(msg.sender());
        if(target.getDevice().isLogEnabled())
            ByteBufUtils.writeRXPacketLog(msg.content(), this.getClass().getSimpleName(), target.getPacketLogger());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LoggerFactory.getLogger("io").error("[{}}] packet decode error {}", this.getClass().getSimpleName(), cause);
    }
}
