package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ExceptionUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("daeyun_rcu_decoder_tcp")
@Scope("prototype")
@RequiredArgsConstructor
public class DaeyunDecoder extends ByteToMessageDecoder {
    Logger logger = LoggerFactory.getLogger("io");
    private List<RXFrame> acceptFrames;
    Device device;
    final ApplicationContext applicationContext;
    final IOServerInstances ioServerInstances;
    @Override
    public void channelActive(ChannelHandlerContext ctx){
        Optional<IOServer> optionalIOServer = ioServerInstances.getServers().values().stream().filter(e->this.equals(e.getDecoder())).findFirst();
        if(optionalIOServer.isPresent()){
            IOServer ioServer = optionalIOServer.get();
            device = ioServer.getDevice();
            Type listType = new TypeToken<ArrayList<RXFrame>>() {}.getType();
            this.acceptFrames = new Gson().fromJson(device.getCommunicationSettings().get("rx").toString(), listType);
        }
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
        Daeyun daeyun = ByteBufUtils.parseDaeyunPacket(in, acceptFrames);
        if (daeyun != null) out.add(daeyun);
        ByteBufUtils.clear(in);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[DaeyunDecoder] exception occur. {}", cause.getMessage());
        ExceptionUtil.printStackTrace(cause);
        ctx.close();
    }
}
