package com.onionsoftware.mk119.commtestsuite.io.netty;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Protocol;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.*;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.*;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.*;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IOServer extends Thread {
    private final Logger logger = LoggerFactory.getLogger("io");
    private final Device device;
    EventLoopGroup bossGroup;
    EventLoopGroup workerGroup;

    @Getter
    private ServerData serverData;

    public IOServer(Device device) {
        this.device = device;
        if (device.getProtocol() == Protocol.MODBUS) {
            serverData = new ModbusData();
        } else if (device.getProtocol() == Protocol.XGT_PLC) {
            serverData = new XGTData();
        } else if (device.getProtocol() == Protocol.GENERIC) {
            serverData = new GenericData();
        } else if (device.getProtocol() == Protocol.DAEYUN_RCU) {
            serverData = new DaeyunData();
        } else if (device.getProtocol() == Protocol.OPC_AGENT){
            int size = Integer.parseInt(device.getCommunicationSettings().get("agentTagsCount").toString());
            serverData = new OPCAgentData(size);
        } else if (device.getProtocol() == Protocol.SNMP){
            serverData = new SnmpData();
        }
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
                    addHandlers(ch);
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
                    addHandlers(ch);
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

    private void addHandlers(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        device.getCommunicationSettings().put("transport", device.getTransport().name());
        switch (device.getProtocol()) {
            case GENERIC:
                if (device.getTransport() == Transport.TCP)
                    pipeline.addLast(new GenericTCPDecoder(device.getCommunicationSettings()));
                else if (device.getTransport() == Transport.UDP) {
                    pipeline.addLast(new GenericUDPDecoder(device.getCommunicationSettings()));
                }
                pipeline.addLast(new GenericTCPUDPHandler(device.getCommunicationSettings(), (GenericData) serverData));
                break;
            case MODBUS:
                if (device.getTransport() == Transport.TCP)
                    pipeline.addLast(new ModbusDecoder(device.getCommunicationSettings()));
                else if (device.getTransport() == Transport.UDP) {
                    pipeline.addLast(new ModbusUDPDecoder(device.getCommunicationSettings()));
                }
                pipeline.addLast(new ModbusHandler(device.getCommunicationSettings(), (ModbusData) serverData));
                break;
            case XGT_PLC:
                if (device.getTransport() == Transport.TCP)
                    pipeline.addLast(new XGTDecoder());
                else if (device.getTransport() == Transport.UDP) {
                    pipeline.addLast(new XGTUDPDecoder());
                }
                pipeline.addLast(new XGTHandler(device.getCommunicationSettings(), (XGTData) serverData));
                break;
            case DAEYUN_RCU:
                if (device.getTransport() == Transport.TCP)
                    pipeline.addLast(new DaeyunDecoder(device.getCommunicationSettings()));
                else if (device.getTransport() == Transport.UDP) {
                    pipeline.addLast(new DaeyunUDPDecoder(device.getCommunicationSettings()));
                }
                pipeline.addLast(new DaeyunHandler(device.getCommunicationSettings(), (DaeyunData) serverData));
                break;
            case OPC_AGENT:
                if (device.getTransport() == Transport.TCP)
                    pipeline.addLast(new OPCAgentDecoder());
                else if (device.getTransport() == Transport.UDP) {
                    pipeline.addLast(new OPCAgentUDPDecoder());
                }
                pipeline.addLast(new OPCAgentHandler(device.getCommunicationSettings(), (OPCAgentData) serverData));
                break;
            case SNMP:
                if(device.getTransport() == Transport.TCP){
                    throw new RuntimeException("SNMP don't work on TCP layer");
                } else if (device.getTransport() == Transport.UDP){
                    pipeline.addLast(new SnmpDecoder(device.getCommunicationSettings()));
                }
                pipeline.addLast(new SnmpHandler((SnmpData) serverData));
                break;
            case BACNET:
                if(device.getTransport() == Transport.TCP){
                    throw new RuntimeException("BACnet don't work on TCP layer");
                } else if (device.getTransport() == Transport.UDP){
                    pipeline.addLast(new BACnetDecoder());
                }
                pipeline.addLast(new BACnetHandler());
                break;
        }
    }

    public void stopServer() {
        this.interrupt();
    }

}
