package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.simulation.SimulationJob;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;

@Getter
@Setter
@RequiredArgsConstructor
@Component
@Scope("prototype")
public abstract class MkHandler extends ChannelInboundHandlerAdapter {
    private long txCounter;
    private ChannelHandlerContext context;
    protected ByteBuf sendBuffer;
    protected final ApplicationContext applicationContext;
    protected final IOServer target;
    private String transport;
    private InetSocketAddress sender;
    private SimulationService simulationService;
    Random garbageGenerator = new Random();

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        context = ctx;
        transport = target.getDevice().getCommunicationSettings().get("transport").toString();
        simulationService = applicationContext.getBean(SimulationService.class);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        generateBuffer(ctx, msg);
        if (sendBuffer != null) {
            List<Error> errors = this.target.getErrors();
            Error systemError = Error.getOccuredError(errors.stream().filter(e -> e.getMode() != ErrorMode.SIMULATION).collect(Collectors.toList()));
            if (systemError != null) {
                if (systemError.getMode() == ErrorMode.DISCARD) {
                    return;
                } else if (systemError.getMode() == ErrorMode.TIMEOUT) {
                    try {
                        Thread.sleep(systemError.getTimeout() * 1000L);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                } else if (systemError.getMode() == ErrorMode.GARBAGE) {
                    ByteBuf ret = Unpooled.buffer();
                    for (int i = 0; i < systemError.getGarbageLength(); i++) {
                        ret.writeByte(garbageGenerator.nextInt(Byte.MAX_VALUE));
                    }
                    sendBuffer = ret;
                } else if (systemError.getMode() == ErrorMode.DISCONNECT) {
                    ctx.close();
                }
            }

            Error simulationError = Error.getOccuredError(errors.stream().filter(e -> e.getMode() == ErrorMode.SIMULATION).collect(Collectors.toList()));
            if(simulationError != null) {
                SimulationJob.setServerData(target.getServerData(), simulationError.getSimulation(), simulationError.getErrorValue());
                if(simulationError.getSimulation_stop() != null && simulationError.getSimulation_stop() != 0) {
                    simulationService.pauseSimulation(simulationError.getSimulation(), simulationError.getSimulation_stop());
                }
                regenerateBuffer(ctx, msg);
            }

            if (transport.equals("UDP")) {
                ctx.writeAndFlush(new DatagramPacket(sendBuffer.retain(), sender));
            } else ctx.writeAndFlush(sendBuffer.retain());
            if (target.getDevice().getLog() == Device.DEVICE_ENABLED) {
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
        MkLogger.log("io", LogLevel.ERROR, "[{0}] packet handle error {1}\n{2}", this.getClass().getSimpleName(), cause, buffer.toString());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        target.getSessions().remove((InetSocketAddress) ctx.channel().remoteAddress());
        target.getHandlers().removeIf(this::equals);
        target.getDecoders().removeIf(e -> e instanceof MkDecoderInterface && ((MkDecoderInterface) e).getContext().equals(ctx));
    }

    protected abstract void generateBuffer(ChannelHandlerContext ctx, Object msg);
    protected void regenerateBuffer(ChannelHandlerContext ctx, Object msg) {
        sendBuffer.clear();
        generateBuffer(ctx, msg);
    }
}
