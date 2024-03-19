package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.GenericData;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ExceptionUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.TXFrame;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("generic")
@Scope("prototype")
@RequiredArgsConstructor
public class GenericTCPUDPHandler extends ChannelInboundHandlerAdapter {
    static Logger logger = LoggerFactory.getLogger("io");
    private List<TXFrame> sendFrames;
    private String transport;
    private GenericData genericData;

    Device device;
    final ApplicationContext applicationContext;
    final IOServerInstances ioServerInstances;

    @Override
    public void channelActive(ChannelHandlerContext ctx){
        logger.info("handler channel active");
        Optional<IOServer> optionalIOServer = ioServerInstances.getServers().values().stream().filter(e->this.equals(e.getHandler())).findFirst();
        if(optionalIOServer.isPresent()){
            IOServer ioServer = optionalIOServer.get();
            device = ioServer.getDevice();
            genericData = (GenericData) applicationContext.getBean("serverData", device);
            ioServer.setServerData(genericData);
            transport = device.getCommunicationSettings().get("transport").toString();
            Type listType = new TypeToken<ArrayList<TXFrame>>() {}.getType();
            this.sendFrames = new Gson().fromJson(device.getCommunicationSettings().get("tx").toString(), listType);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf out = null;
        if (msg instanceof GenericTCPUDPPacket) {
            GenericTCPUDPPacket packet = (GenericTCPUDPPacket) msg;
            packet.setGenericData(genericData);
            Optional<TXFrame> optionalTX = sendFrames.stream().filter(e -> e.getTx_id().equals(packet.getRx_id())).findFirst();
            if (optionalTX.isPresent()) {
                out = Unpooled.buffer();
                TXFrame frame = optionalTX.get();
                frame.translateFields(packet);
                ByteBufUtils._writeTXPacketByFrame(frame, out, packet);
            }

            if (out != null) {
                if (transport.equals("UDP")) {
                    ctx.writeAndFlush(new DatagramPacket(out, packet.getSender()));
                } else ctx.writeAndFlush(out);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[GenericTCPUDPHandler] exception occur. {}", cause.getMessage());
        ExceptionUtil.printStackTrace(cause);
        ctx.close();
    }
}
