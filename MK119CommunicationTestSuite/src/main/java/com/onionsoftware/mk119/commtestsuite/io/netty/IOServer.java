package com.onionsoftware.mk119.commtestsuite.io.netty;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp.MkUDPDecoder;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.MkHandler;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;

import java.util.concurrent.CopyOnWriteArrayList;
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
    private final List<ServerDataListener> serverDataListeners = new CopyOnWriteArrayList<>();
    private List<ChannelInboundHandlerAdapter> decoders;
    private List<ChannelInboundHandlerAdapter> handlers;

    private volatile List<Error> errors;

    @Autowired
    IOServerFactory ioServerFactory;

    @Setter
    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler;

    Map<InetSocketAddress, Long> sessions = new HashMap<>();

    private Logger packetLogger;

    // ── 시스템 에러 플래그 ──
    /** 현재 활성화된 시스템 에러 (null이면 정상 상태) */
    private volatile Error activeSystemError;
    /** 시스템 에러 만료 시각 (System.currentTimeMillis 기준) */
    private volatile long errorExpireTime;

    // ── 포트 언바인드/리바인드 지원 ──
    /** 서버 채널 참조 (TCP/UDP 공통) */
    private volatile Channel serverChannel;
    /** TCP 서버 부트스트랩 (리바인드 시 재사용) */
    private ServerBootstrap serverBootstrap;

    public IOServer(Device device, List<Error> errors) {
        this.device = device;
        this.setName(device.getName() + "[" + device.getHost() + ":" + device.getPort() + "(" + device.getTransport()
                + ")]");
        // daemon으로 지정해서 JVM 종료(예: 콘솔 Ctrl+C)를 붙잡지 않도록 함. 정상 종료 경로는 @PreDestroy → stopServer().
        this.setDaemon(true);

        if (getDevice().isLogEnabled()) {
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

        this.errors = errors;
    }

    @Override
    public void run() {
        decoders = new ArrayList<>();
        handlers = new ArrayList<>();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);

        if (device.getTransport() == Transport.TCP) {
            serverBootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                    .localAddress(device.getHost(), device.getPort());
            serverBootstrap.channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .option(ChannelOption.AUTO_CLOSE, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_SNDBUF, 16384);
            serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    constructPipeline(ch);
                }
            });

            try {
                bindServer();
                // closeFuture 대신 interrupt 대기로 변경 — 포트 언바인드/리바인드를 지원하기 위함
                synchronized (this) {
                    while (!Thread.currentThread().isInterrupted()) {
                        this.wait();
                    }
                }
            } catch (InterruptedException e) {
                MkLogger.log("io", LogLevel.WARNING, "Server for device {0} interrupted.", device.getName());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                MkLogger.log("io", e, "General exception to initialize TCP server.");
            } finally {
                if (serverChannel != null && serverChannel.isOpen()) {
                    serverChannel.close();
                }
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        } else if (device.getTransport() == Transport.UDP) {
            Bootstrap bootstrap = new Bootstrap().localAddress(device.getHost(), device.getPort());
            bootstrap.group(bossGroup).channel(NioDatagramChannel.class)
                    .option(ChannelOption.AUTO_CLOSE, true)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_SNDBUF, 16384);
            bootstrap.handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    constructPipeline(ch);
                }
            });

            try {
                ChannelFuture f = bootstrap.bind().sync();
                serverChannel = f.channel();
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
        ChannelInboundHandlerAdapter decoder = ioServerFactory.decoder(device, this);
        MkHandler handler = ioServerFactory.handler(device, this);
        if (device.getTransport() == Transport.UDP) {
            ((MkUDPDecoder) decoder).setHandler(handler);
        }
        getDecoders().add(decoder);
        getHandlers().add(handler);

        pipeline.addLast(decoder);
        pipeline.addLast(handler);

        sessions.put((InetSocketAddress) channel.remoteAddress(), System.currentTimeMillis());
    }

    // ── 시스템 에러 관리 메서드 ──

    /**
     * 시스템 에러를 활성화한다. 지정된 유지 시간 동안 해당 에러 동작이 유지된다.
     * DISCONNECT 모드인 경우 포트를 언바인드하고 기존 연결을 모두 종료한다.
     */
    public synchronized void activateSystemError(Error error, int durationSeconds) {
        this.activeSystemError = error;
        this.errorExpireTime = System.currentTimeMillis() + durationSeconds * 1000L;

        MkLogger.log("io", LogLevel.INFO,
                "System error activated on device {0}({1}): mode={2}, duration={3}s",
                device.getName(), device.getId(), error.getMode(), durationSeconds);

        if (error.getMode() == ErrorMode.DISCONNECT) {
            unbindPort();
        }

        // 지정된 시간 이후 자동으로 에러를 해제하는 독립 스케줄 등록
        // Netty EventLoop(bossGroup)에서 sync() 호출 시 BlockingOperationException이 발생할 수 있으므로
        // Spring의 ThreadPoolTaskScheduler를 사용하여 안전하게 별도 스레드에서 복구를 수행합니다.
        taskScheduler.schedule(() -> {
            if (this.activeSystemError == error) {
                deactivateSystemError();
            }
        }, new java.util.Date(System.currentTimeMillis() + durationSeconds * 1000L));
    }

    /**
     * 현재 활성 시스템 에러를 조회한다. 만료 시 자동으로 해제하고 복구한다.
     */
    public synchronized Error getActiveSystemError() {
        return activeSystemError;
    }

    /**
     * 시스템 에러가 활성 상태인지 확인한다 (스케줄러에서 중복 방지용).
     */
    public boolean isSystemErrorActive() {
        return getActiveSystemError() != null;
    }

    /**
     * 시스템 에러를 비활성화하고 정상 상태로 복구한다.
     * DISCONNECT 모드였던 경우 포트를 재바인드한다.
     */
    public synchronized void deactivateSystemError() {
        Error previous = activeSystemError;
        if (previous == null) {
            return;
        }
        activeSystemError = null;

        MkLogger.log("io", LogLevel.INFO,
                "System error deactivated on device {0}({1}). Resuming normal operation.",
                device.getName(), device.getId());

        if (previous.getMode() == ErrorMode.DISCONNECT) {
            rebindPort();
        }
    }

    // ── 포트 언바인드/리바인드 ──

    /**
     * 서버 포트를 임시로 언바인드한다. 기존 연결도 모두 종료한다.
     * 클라이언트는 Connection Refused를 받게 된다.
     */
    private void unbindPort() {
        // 기존 연결 모두 종료
        for (ChannelInboundHandlerAdapter handler : handlers) {
            if (handler instanceof MkHandler) {
                ChannelHandlerContext ctx = ((MkHandler) handler).getContext();
                if (ctx != null) {
                    ctx.close();
                }
            }
        }

        // 서버 채널 닫기 (포트 언바인드)
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().addListener(f -> {
                if (f.isSuccess()) {
                    MkLogger.log("io", LogLevel.INFO,
                            "Port unbound for device {0}({1}) - DISCONNECT error active.",
                            device.getName(), device.getId());
                }
            });
        }
    }

    /**
     * 서버 포트를 재바인드한다. DISCONNECT 에러가 해제된 후 호출된다.
     */
    private void rebindPort() {
        try {
            bindServer();
            MkLogger.log("io", LogLevel.INFO,
                    "Port rebound for device {0}({1}) - DISCONNECT error resolved.",
                    device.getName(), device.getId());
        } catch (Exception e) {
            MkLogger.log("io", e, "Failed to rebind port for device {0}({1}).",
                    device.getName(), device.getId());
        }
    }

    /**
     * 서버 채널을 바인드한다 (TCP용).
     */
    private void bindServer() throws InterruptedException {
        if (serverBootstrap != null) {
            ChannelFuture f = serverBootstrap
                    .bind(new InetSocketAddress(device.getHost(), device.getPort())).sync();
            serverChannel = f.channel();
        }
    }

    public void stopServer() {
        this.interrupt();
    }

    // ── ServerData 리스너 인프라 ──

    /**
     * ServerData 세터를 커스텀으로 정의해 Lombok 자동 생성 setter를 대체한다.
     * 세팅과 동시에 ServerData → IOServer 역참조를 자동으로 wire한다.
     */
    public void setServerData(ServerData serverData) {
        this.serverData = serverData;
        if (serverData != null) {
            serverData.setIoServer(this);
        }
    }

    public void addServerDataListener(ServerDataListener listener) {
        serverDataListeners.add(listener);
    }

    public void removeServerDataListener(ServerDataListener listener) {
        serverDataListeners.remove(listener);
    }

    /** ServerData가 변경 시점에 호출한다. 리스너 예외는 격리해 다른 리스너에 영향이 없도록 한다. */
    public void fireServerDataChanged(ServerDataEvent event) {
        for (ServerDataListener listener : serverDataListeners) {
            try {
                listener.onServerDataChanged(event);
            } catch (Exception e) {
                MkLogger.log("io", e, "ServerDataListener {0} failed for device {1}({2}).",
                        listener.getClass().getSimpleName(), device.getName(), device.getId());
            }
        }
    }

}
