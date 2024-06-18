package com.onionsoftware.mk119.commtestsuite.io.netty;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.MkUDPDecoder;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.MkHandler;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class IOServer extends Thread {
    private final Device device;
    EventLoopGroup bossGroup;
    EventLoopGroup workerGroup;

    private ServerData serverData;
    private List<ChannelInboundHandlerAdapter> decoders;
    private List<ChannelInboundHandlerAdapter> handlers;

    private final Error errorSimulation;

    @Autowired
    IOServerFactory ioServerFactory;

    @Autowired
    ApplicationContext applicationContext;
    Map<InetSocketAddress, Long> sessions = new HashMap<>();

    private Logger packetLogger;

    public IOServer(Device device, Error errorSimulation) {
        this.device = device;
        this.setName(device.getName() + "[" + device.getHost() + ":" + device.getPort() + "(" + device.getTransport() + ")]");

        if (getDevice().getLog() == Device.DEVICE_ENABLED) {
            try {
                String logFileName = new File(".").getCanonicalPath() + "/logs/packet/" + device.getName() + ".log";
                String loggerName = "cts.packet-" + device.getId();
                LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
                RollingFileAppender rfAppender = new RollingFileAppender();
                rfAppender.setContext(loggerContext);
                rfAppender.setFile(logFileName);

                FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
                rollingPolicy.setContext(loggerContext);
                rollingPolicy.setParent(rfAppender);
                rollingPolicy.setFileNamePattern(logFileName + ".%i");
                rollingPolicy.setMinIndex(1);
                rollingPolicy.setMaxIndex(10);
                rollingPolicy.start();

                SizeBasedTriggeringPolicy triggeringPolicy = new SizeBasedTriggeringPolicy();
                triggeringPolicy.setMaxFileSize(FileSize.valueOf("10MB"));
                triggeringPolicy.start();

                PatternLayoutEncoder encoder = new PatternLayoutEncoder();
                encoder.setContext(loggerContext);
                encoder.setPattern("%d{yy-MM-dd HH:mm:ss} %-5p : %m%n");
                encoder.start();

                rfAppender.setEncoder(encoder);
                rfAppender.setRollingPolicy(rollingPolicy);
                rfAppender.setTriggeringPolicy(triggeringPolicy);

                rfAppender.start();

                ch.qos.logback.classic.Logger logbackLogger = loggerContext.getLogger(loggerName);
                logbackLogger.setAdditive(true);
                logbackLogger.detachAndStopAllAppenders();
                logbackLogger.addAppender(rfAppender);

                packetLogger = LoggerFactory.getLogger(loggerName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        this.errorSimulation = errorSimulation;
    }

    @Override
    public void run() {
        decoders = new ArrayList<>();
        handlers = new ArrayList<>();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);

        if (device.getTransport() == Transport.TCP) {
            ServerBootstrap bootstrap = new ServerBootstrap().group(bossGroup, workerGroup).localAddress(device.getHost(), device.getPort());
            bootstrap.channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .option(ChannelOption.AUTO_CLOSE, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    constructPipeline(ch);
                }
            });

            try {
                ChannelFuture f = bootstrap.bind(new InetSocketAddress(device.getHost(), device.getPort())).sync();
                f.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                MkLogger.log("io", LogLevel.WARNING, "Server for device {0} interrupted.", device.getName());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                MkLogger.log("io", e, "General exception to initialize TCP server.");
            } finally {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        } else if (device.getTransport() == Transport.UDP) {
            Bootstrap bootstrap = new Bootstrap().localAddress(device.getHost(), device.getPort());
            bootstrap.group(bossGroup).channel(NioDatagramChannel.class)
                    .option(ChannelOption.AUTO_CLOSE, true)
                    .option(ChannelOption.SO_BROADCAST, true);
            bootstrap.handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    constructPipeline(ch);
                }
            });

            try {
                ChannelFuture f = bootstrap.bind().sync();
                f.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                MkLogger.log("io", LogLevel.WARNING, "Server for device {0} interrupted.", device.getName());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                MkLogger.log("io", e, "General exception to initialize UDP server.");
            } finally {
                bossGroup.shutdownGracefully();
            }
        }
    }

    private void constructPipeline(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        device.getCommunicationSettings().put("transport", device.getTransport().name());
        ChannelInboundHandlerAdapter decoder = (ChannelInboundHandlerAdapter) applicationContext.getBean("decoder", device, this);
        MkHandler handler = (MkHandler) applicationContext.getBean("handler", device, this);
        if (device.getTransport() == Transport.UDP) {
            ((MkUDPDecoder) decoder).setHandler(handler);
        }
        getDecoders().add(decoder);
        getHandlers().add(handler);

        pipeline.addLast(decoder);
        pipeline.addLast(handler);

        sessions.put((InetSocketAddress) channel.remoteAddress(), System.currentTimeMillis());
    }

    public void stopServer() {
        this.interrupt();
    }

}
