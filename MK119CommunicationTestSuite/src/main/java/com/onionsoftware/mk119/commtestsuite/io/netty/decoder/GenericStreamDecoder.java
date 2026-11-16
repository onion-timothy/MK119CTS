package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition;
import com.onionsoftware.mk119.commtestsuite.protocol.ProtocolEngine;
import com.onionsoftware.mk119.commtestsuite.protocol.Values;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GENERIC TCP: 쌓인 바이트에서 프로토콜 정의의 rx 메시지를 찾는다 (docs/PROTOCOL_ENGINE_DESIGN.md 5.2·5.3).
 * <ul>
 *   <li>맞으면 그만큼 소비하고 메시지를 handler 로</li>
 *   <li>모자라면 기다린다. idleFlushMs(없으면 1000 ms) 동안 새 바이트가 없으면 모자람을 아님으로 보고 다시 시도</li>
 *   <li>아무 메시지도 아니면 syncOn 앞까지(없으면 한 바이트) 버리고 다시 시도</li>
 * </ul>
 */
public class GenericStreamDecoder extends MkDecoder {
    /** 문서에 idleFlushMs 가 없을 때 TCP 의 대기 (모자란 프레임이 연결을 계속 막지 않게) */
    static final int DEFAULT_IDLE_MS = 1000;

    private final ProtocolEngine engine;
    private ScheduledFuture<?> idleTimer;

    public GenericStreamDecoder(IOServer server, ProtocolEngine engine) {
        super(server);
        this.engine = engine;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);
        cancelIdle();
        process(ctx, in, out, false);
    }

    private void process(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, boolean flushing) {
        Definition def = engine == null ? null : engine.definition();
        if (def == null) {
            if (in.readableBytes() > 0) {
                LoggerFactory.getLogger("io").warn("Device \"{}\" has no usable protocol definition; {} bytes dropped",
                        target.getDevice().getName(), in.readableBytes());
                in.skipBytes(in.readableBytes());
            }
            return;
        }
        byte[] buf = new byte[in.readableBytes()];
        in.getBytes(in.readerIndex(), buf);
        int pos = 0;
        while (pos < buf.length) {
            ProtocolEngine.Match m = engine.match(buf, pos, buf.length, ProtocolEngine.Mode.STREAM, flushing);
            if (m.status == ProtocolEngine.Status.MATCH) {
                out.add(m.message);
                pos += Math.max(1, m.consumed);
                flushing = false;
            } else if (m.status == ProtocolEngine.Status.NEED_MORE) {
                if (buf.length - pos > def.maxLength) {
                    pos += discard(buf, pos, "longer than maxLength " + def.maxLength);
                    continue;
                }
                scheduleIdle(ctx, def);
                break;
            } else {
                pos += discard(buf, pos, m.reasons.isEmpty() ? "no message" : m.reasons.get(0));
                flushing = false;
            }
        }
        in.skipBytes(pos);
    }

    private int discard(byte[] buf, int pos, String why) {
        int n = engine.resync(buf, pos, buf.length);
        byte[] gone = new byte[n];
        System.arraycopy(buf, pos, gone, 0, n);
        LoggerFactory.getLogger("io").warn("Device \"{}\" discarded {} ({})", target.getDevice().getName(), Values.hex(gone), why);
        return n;
    }

    private void scheduleIdle(ChannelHandlerContext ctx, Definition def) {
        int ms = def.idleFlushMs != null && def.idleFlushMs > 0 ? def.idleFlushMs : DEFAULT_IDLE_MS;
        idleTimer = ctx.executor().schedule(() -> {
            idleTimer = null;
            ByteBuf in = internalBuffer();
            if (in == null || !in.isReadable()) return;
            List<Object> out = new ArrayList<>();
            process(ctx, in, out, true);
            for (Object o : out) ctx.fireChannelRead(o);
            if (!out.isEmpty()) ctx.fireChannelReadComplete();
        }, ms, TimeUnit.MILLISECONDS);
    }

    private void cancelIdle() {
        if (idleTimer != null) {
            idleTimer.cancel(false);
            idleTimer = null;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelIdle();
        super.channelInactive(ctx);
    }
}
