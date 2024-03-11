package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.XGTData;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

public class XGTHandler extends ChannelInboundHandlerAdapter {
    static Logger logger = LoggerFactory.getLogger("io");
    private final String transport;
    private final XGTData xgtData;

    public XGTHandler(Map<Object, Object> communicationSettings, XGTData xgtData) {
        this.xgtData = xgtData;
        transport = communicationSettings.get("transport").toString();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf out;

        if (msg instanceof XGT) {
            XGT xgt = (XGT) msg;
            out = ctx.alloc().buffer(32 + xgt.getBlockSize());
            // XGT Header.
            out.writeBytes("LSIS-XGT".getBytes(StandardCharsets.US_ASCII));
            out.writeShortLE(0);            // Don't care
            out.writeShortLE(0);            // Don't care
            out.writeByte(0);               // Don't care
            out.writeByte(0x11);
            out.writeShortLE(xgt.getInvokeId());
            out.writeShortLE(0);            // Don't care
            out.writeShortLE(0);            // Don't care
            out.writeShortLE(xgt.getCode() + 1);

            out.writeShortLE(xgt.getDataType());
            out.writeShortLE(0);            // Dont' care
            out.writeShortLE(0);            // Error code
            out.writeShortLE(1);            // Block count
            out.writeShortLE(xgt.getBlockSize());

            String variable = xgt.getVariable();
            char blockName = variable.charAt(1);
            int address = Integer.parseInt(variable.split("B")[1]);
            byte[] ret = null;
            switch (blockName) {
                case 'M':
                    ret = Arrays.copyOfRange(xgtData.getMblock(), address, address + xgt.getBlockSize());
                    break;
                case 'D':
                    ret = Arrays.copyOfRange(xgtData.getDblock(), address, address + xgt.getBlockSize());
                    break;
                case 'P':
                    ret = Arrays.copyOfRange(xgtData.getPblock(), address, address + xgt.getBlockSize());
                    break;
                case 'K':
                    ret = Arrays.copyOfRange(xgtData.getKblock(), address, address + xgt.getBlockSize());
                    break;
                case 'S':
                    ret = Arrays.copyOfRange(xgtData.getSblock(), address, address + xgt.getBlockSize());
                    break;
                case 'U':
                    ret = Arrays.copyOfRange(xgtData.getUblock(), address, address + xgt.getBlockSize());
                    break;
            }
            if (ret != null) {
                out.writeBytes(ret);
            }

            ByteBufUtils.writeTXPacketLog(out, this.getClass().getSimpleName());
            if (transport.equals("UDP")) {
                ctx.writeAndFlush(new DatagramPacket(out, xgt.getSender()));
            } else ctx.writeAndFlush(out);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[XGTHandler] exception occur. {}", cause.getMessage());
        ctx.close();
    }
}
