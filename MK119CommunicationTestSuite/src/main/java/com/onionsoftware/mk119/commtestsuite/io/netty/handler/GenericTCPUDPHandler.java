package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.bean.ErrorResult;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.GenericData;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.TXFrame;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("generic")
@Scope("prototype")
public class GenericTCPUDPHandler extends MkHandler {
    private List<TXFrame> sendFrames;
    private String transport;

    public GenericTCPUDPHandler(ApplicationContext applicationContext, IOServerInstances ioServerInstances) {
        super(applicationContext, ioServerInstances);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        transport = target.getDevice().getCommunicationSettings().get("transport").toString();
        Type listType = new TypeToken<ArrayList<TXFrame>>() {}.getType();
        this.sendFrames = new Gson().fromJson(target.getDevice().getCommunicationSettings().get("tx").toString(), listType);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf out = null;
        if (msg instanceof GenericTCPUDPPacket) {
            GenericTCPUDPPacket packet = (GenericTCPUDPPacket) msg;
            packet.setGenericData((GenericData) target.getServerData());
            Optional<TXFrame> optionalTX = sendFrames.stream().filter(e -> e.getTx_id().equals(packet.getRx_id())).findFirst();
            if (optionalTX.isPresent()) {
                out = Unpooled.buffer();
                TXFrame frame = optionalTX.get();
                frame.translateFields(packet);
                ByteBufUtils._writeTXPacketByFrame(frame, out, packet);
            }

            if (out != null) {
                ErrorService errorService = (ErrorService) applicationContext.getBean("errorService");
                Error errorSimulation = errorService.selectErrorByDeviceId(target.getDevice().getId());
                if (errorSimulation != null) {
                    ErrorResult _ret = errorSimulation.performErrorSimulation(out);
                    switch (_ret.getResultType()) {
                        case ErrorResult.RESULT_TYPE_DISCONNECT:
                            ctx.close();
                            break;
                        case ErrorResult.RESULT_TYPE_TIMEOUT:
                        case ErrorResult.RESULT_TYPE_GARBAGE:
                            out = _ret.getBufToWrite();
                            break;
                        case ErrorResult.RESULT_TYPE_DISCARD:
                            return;
                    }
                }

                lastSent = out.copy();
                if (transport.equals("UDP")) {
                    ctx.writeAndFlush(new DatagramPacket(out, packet.getSender()));
                } else ctx.writeAndFlush(out);
                super.channelRead(ctx, msg);
            }
        }
    }
}
