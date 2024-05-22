package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.util.List;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("xgt_plc_decoder_tcp")
@Scope("prototype")
public class XGTDecoder extends MkDecoder {

    public XGTDecoder(IOServer server) {
        super(server);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);
        XGT xgt = ByteBufUtils.parseXGTPacket(in);
        if (xgt != null) {
            out.add(xgt);
        } else {
            MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
        }
        ByteBufUtils.clear(in);
    }
}
