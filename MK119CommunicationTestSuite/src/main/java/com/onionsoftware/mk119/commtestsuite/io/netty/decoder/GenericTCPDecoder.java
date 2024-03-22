package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.TXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.GenericData;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ExceptionUtil;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("generic_decoder_tcp")
@Scope("prototype")
@RequiredArgsConstructor
public class GenericTCPDecoder extends MkDecoder {
    Logger logger = LoggerFactory.getLogger("io");
    private List<RXFrame> acceptFrames;
    Device device;
    final ApplicationContext applicationContext;
    final IOServerInstances ioServerInstances;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        IOServer target = null;
        List<IOServer> ioServers = new ArrayList<>(ioServerInstances.getServers().values());
        for (IOServer server : ioServers) {
            Optional<ChannelInboundHandlerAdapter> optionalDecoder = server.getDecoders().stream().filter(this::equals).findFirst();
            if (optionalDecoder.isPresent()) {
                target = server;
                break;
            }
        }
        if (target != null) {
            device = target.getDevice();
            Type listType = new TypeToken<ArrayList<RXFrame>>() {}.getType();
            this.acceptFrames = new Gson().fromJson(device.getCommunicationSettings().get("rx").toString(), listType);
            ctx.fireChannelActive();
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);

        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
        GenericTCPUDPPacket packet = null;

        for (RXFrame frame : acceptFrames) {
            packet = ByteBufUtils.parsePacket(frame, in);
            if (packet == null) {
                in.resetReaderIndex();
            } else break;
        }

        if (packet == null) {
            ByteBufUtils.clear(in);
            logger.info("No matched frame.");
        } else {
            out.add(packet);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[GenericTCPUDPDecoder] exception occur. {}", cause.getMessage());
        ExceptionUtil.printStackTrace(cause);
        ctx.close();
    }
}
