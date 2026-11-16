package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.protocol.ProtocolEngine;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * GENERIC UDP: 데이터그램 하나가 프레임 하나다 (docs/PROTOCOL_ENGINE_DESIGN.md 5.4).
 * 남는 바이트가 있으면 그 메시지가 아니다. 어느 rx 에도 맞지 않으면 정의의 onUnmatched 로 답한다(기본은 무응답).
 */
public class GenericDatagramDecoder extends MkUDPDecoder {
    private final ProtocolEngine engine;

    public GenericDatagramDecoder(IOServer server, ProtocolEngine engine) {
        super(server);
        this.engine = engine;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        byte[] buf = new byte[in.readableBytes()];
        in.getBytes(in.readerIndex(), buf);
        if (engine == null || engine.definition() == null) {
            LoggerFactory.getLogger("io").warn("Device \"{}\" has no usable protocol definition; datagram dropped", target.getDevice().getName());
            return;
        }
        ProtocolEngine.Match m = engine.match(buf, 0, buf.length, ProtocolEngine.Mode.EXACT, false);
        if (m.status == ProtocolEngine.Status.MATCH) {
            out.add(m.message);
            return;
        }
        LoggerFactory.getLogger("io").warn("Device \"{}\" received a datagram no message matches ({})",
                target.getDevice().getName(), m.reasons.isEmpty() ? "no messages" : m.reasons.get(0));
        byte[] reply = engine.unmatchedReply(buf);
        if (reply != null) out.add(new ProtocolEngine.Unmatched(reply));
    }
}
