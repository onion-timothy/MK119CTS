package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.protocol.ProtocolEngine;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * GENERIC: 받은 메시지에 프로토콜 정의의 응답 규칙을 실행한다 (docs/PROTOCOL_ENGINE_DESIGN.md 6절).
 * 보낼 바이트는 MkHandler 의 송신 길(시스템 오류·패킷 로그)을 탄다. delayMs 는 이벤트 루프를 막지 않는 예약 송신이다.
 */
public class GenericHandler extends MkHandler {
    public GenericHandler(IOServer server) {
        super(server);
    }

    @Override
    protected void generateBuffer(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ProtocolEngine.Unmatched) {
            sendBuffer = Unpooled.wrappedBuffer(((ProtocolEngine.Unmatched) msg).reply);
            return;
        }
        if (!(msg instanceof ProtocolEngine.RxMessage)) return;
        ProtocolEngine.RxMessage rx = (ProtocolEngine.RxMessage) msg;
        ProtocolEngine.Reply r = rx.respond();
        String device = target.getDevice().getName();
        if (r.error != null) {
            LoggerFactory.getLogger("io").warn("Device \"{}\" did not reply to {}: {}", device, rx.name(), r.error);
        }
        for (String note : r.notes) {
            if (note.startsWith("wrote ")) LoggerFactory.getLogger("io").debug("Device \"{}\" {}: {}", device, rx.name(), note);
            else LoggerFactory.getLogger("io").warn("Device \"{}\" {}: {}", device, rx.name(), note);
        }
        if (r.bytes == null) return;
        if (r.delayMs <= 0) {
            sendBuffer = Unpooled.wrappedBuffer(r.bytes);
            return;
        }
        // 늦게 보낼 때도 같은 송신 길로. UDP 는 그 사이 다른 송신자가 올 수 있어 지금 송신자를 붙들어 둔다
        final byte[] bytes = r.bytes;
        final InetSocketAddress to = getSender();
        ctx.executor().schedule(() -> {
            InetSocketAddress now = getSender();
            setSender(to);
            sendBuffer = Unpooled.wrappedBuffer(bytes);
            transmit(ctx);
            sendBuffer = null;
            setSender(now);
        }, r.delayMs, TimeUnit.MILLISECONDS);
    }
}
