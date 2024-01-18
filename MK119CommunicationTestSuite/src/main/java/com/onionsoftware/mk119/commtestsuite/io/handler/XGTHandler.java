package com.onionsoftware.mk119.commtestsuite.io.handler;

import com.onionsoftware.mk119.commtestsuite.io.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.tool.CRC16;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;

public class XGTHandler extends ChannelInboundHandlerAdapter {
    static Logger logger = LoggerFactory.getLogger("io");

    public XGTHandler() {

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }
}
