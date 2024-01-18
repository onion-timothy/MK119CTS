package com.onionsoftware.mk119.commtestsuite.io;

import java.util.ArrayList;
import java.util.List;

import com.onionsoftware.mk119.commtestsuite.io.decoder.GenericTCPUDPDecoder;
import com.onionsoftware.mk119.commtestsuite.io.decoder.ModbusDecoder;
import com.onionsoftware.mk119.commtestsuite.io.handler.GenericTCPUDPHandler;

import com.onionsoftware.mk119.commtestsuite.io.handler.ModbusHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class IOServer extends Thread {
    private int port;

    public IOServer(int port) {
        this.port = port;
    }

    public int getPort() {
        return this.port;
    }

    @Override
    public void run() {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        List<String> testrx = new ArrayList<String>();
        testrx.add("{\"rx_id\":\"modbus-tcp-fc3\",\"length\":12,\"fields\":[{\"field_id\":\"transaction_id\",\"data_type\":\"UI16AB\",\"required_value\":null},{\"field_id\":\"protocol_id\",\"data_type\":\"UI16AB\",\"required_value\":0},{\"field_id\":\"tcp_length\",\"data_type\":\"UI16AB\",\"required_value\":6},{\"field_id\":\"unit_id\",\"data_type\":\"UI8\",\"required_value\":null,\"rx_ref\":\"uid\"},{\"field_id\":\"function_code\",\"data_type\":\"UI8\",\"required_value\":3},{\"field_id\":\"start_register\",\"data_type\":\"UI16AB\",\"required_value\":null},{\"field_id\":\"register_count\",\"data_type\":\"UI16AB\",\"required_value\":null}]}");

        List<String> testtx = new ArrayList<String>();
        testtx.add("{\"tx_id\":\"modbus-tcp-fc3\",\"fields\":[{\"field_id\":\"transaction_id\",\"field_type\":\"REF\",\"data_type\":\"UI16AB\",\"value\":\"${transaction_id}\"},{\"field_id\":\"protocol_id\",\"field_type\":\"STATIC\",\"data_type\":\"UI16AB\",\"value\":\"0\"},{\"field_id\":\"tcp_length\",\"field_type\":\"REF\",\"data_type\":\"UI16AB\",\"value\":\"${register_count}*2+3\"},{\"field_id\":\"unit_id\",\"field_type\":\"REF\",\"data_type\":\"UI8\",\"value\":\"${unit_id}\"},{\"field_id\":\"function_code\",\"field_type\":\"REF\",\"data_type\":\"UI8\",\"value\":\"${function_code}\"},{\"field_id\":\"length\",\"field_type\":\"REF\",\"data_type\":\"UI8\",\"value\":\"${register_count}*2\"},{\"field_id\":\"payload\",\"field_type\":\"ITERATION\",\"data_type\":\"SI16AB\",\"value\":\"rand(100)\",\"iteration_count\":\"${register_count}\"}]}");

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            System.out.println("connection from " + ch.remoteAddress().toString() + " to " + ch.localAddress().toString());
                            ch.pipeline().addLast(new ModbusDecoder(1), new ModbusHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_RCVBUF, 1048576)
                    .option(ChannelOption.SO_SNDBUF, 1048576)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(port).sync();
            f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
