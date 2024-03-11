package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
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

@Getter
@Setter
public class IOServer extends Thread {
    private final Logger logger = LoggerFactory.getLogger("io");
    private final Device device;
    EventLoopGroup bossGroup;
    EventLoopGroup workerGroup;

    private ServerData serverData;
    private ChannelInboundHandlerAdapter decoder;
    private ChannelInboundHandlerAdapter handler;

    @Autowired
    IOServerFactory ioServerFactory;

    public IOServer(Device device) {
        this.device = device;
    }

    @Override
    public void run() {
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();

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
                ChannelFuture f = bootstrap.bind(device.getPort()).sync();
                f.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                logger.error("Server [{}] interrupted.", device.getName());
                Thread.currentThread().interrupt();
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
                logger.error("Server [{}] interrupted.", device.getName());
                Thread.currentThread().interrupt();
            } finally {
                bossGroup.shutdownGracefully();
            }
        }
    }

    private void constructPipeline(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        device.getCommunicationSettings().put("transport", device.getTransport().name());
        pipeline.addLast(this.decoder);
        pipeline.addLast(this.handler);
    }

    public void stopServer() {
        this.interrupt();
    }

}
