package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.protocol.ProtocolEngine;
import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.DaeyunData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.GenericData;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.Checksum;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.util.*;

public class DaeyunHandler extends MkHandler {
    /** RS(0x0A) 본문을 해석하는 프로토콜 정의 엔진. 장비에 정의가 없으면 null — RS 요청에 답하지 않는다 */
    private final ProtocolEngine engine;

    public DaeyunHandler(IOServer server, ProtocolEngine engine) {
        super(server);
        this.engine = engine;
    }

    @Override
    protected void generateBuffer(ChannelHandlerContext ctx, Object msg) {
        ByteBuf out = Unpooled.buffer();
        int error = ERROR_NO_ERROR;
        int length = 0;
        if (msg instanceof Daeyun) {
            DaeyunData daeyunData = (DaeyunData) target.getServerData();
            Daeyun daeyun = (Daeyun) msg;
            out.writeByte(0x01);
            out.writeByte(daeyun.getFunction());
            if (daeyun.getFunction() == Daeyun.DI_FUNCTION) {
                // DI
                int count = daeyun.getInputChannelCount();
                if (count < 32) {
                    int blength = (int) Math.ceil((double) count / 8);
                    length = blength + 3;
                    out.writeByte(blength);
                    byte[] values = new byte[blength];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = 0;
                        for (int j = 0; j < 8; j++) {
                            int index = i * 8 + j;
                            values[i] += (byte) (index < count && daeyunData.getDigitalChannelValue(i) ? 1 << j : 0);
                        }
                    }
                    out.writeBytes(values);
                } else error = ERROR_AI_DI_CHANNEL_RANGE;
            } else if (daeyun.getFunction() == Daeyun.AI_FUNCTION) {
                // AI
                if (daeyun.getInputChannelCount() < 32) {
                    short[] values = daeyunData.getAnalogChannelValues(daeyun.getInputChannelCount());
                    out.writeByte(values.length * 2);
                    length = values.length * 2 + 3;
                    for (short s : values) out.writeShort(s);
                } else error = ERROR_AI_DI_CHANNEL_RANGE;
            } else if (daeyun.getFunction() == Daeyun.DO_FUNCTION) {
                out.writeShort(daeyun.getDOTarget());
                out.writeShort(daeyun.getDOParam());
                length = 6;
            } else if (daeyun.getFunction() == Daeyun.VI_FUNCTION) {
                out.writeByte(0);
                out.writeByte(4);
                out.writeByte(0);
                sendBuffer = out.copy();
                return;
            } else if (daeyun.getFunction() == Daeyun.HB_FUNCTION) {
                out.writeByte(0);
                sendBuffer = out.copy();
                return;
            } else if (daeyun.getFunction() == Daeyun.RS_FUNCTION) {
                // 본문은 프로토콜 정의가 읽고 답한다. 답은 포트·길이·CRC 껍데기에 다시 담는다
                byte[] reply = rsReply(daeyun.getPayload());
                if (reply != null) {
                    out.writeShort(daeyun.getPort());
                    out.writeShort(reply.length);
                    out.writeBytes(reply);
                    length = reply.length + 6;
                } else {
                    error = ERROR_TX_MATCH_NOT_FOUND;
                }
            }

            if (error == ERROR_NO_ERROR) {
                byte[] toCRC = new byte[length];
                out.getBytes(0, toCRC, 0, length);
                short crc = Checksum.getCRC16(toCRC);
                out.writeShort(crc);

                sendBuffer = out.copy();
            }
        }
    }

    /** RS 본문에 대한 정의의 응답. 정의가 없거나, 맞는 메시지·규칙이 없거나, 무응답이면 null */
    private byte[] rsReply(byte[] payload) {
        String device = target.getDevice().getName();
        if (engine == null || engine.definition() == null) {
            LoggerFactory.getLogger("io").warn("Daeyun RCU \"{}\" has no usable protocol definition for RS", device);
            return null;
        }
        if (payload == null) return null;
        ProtocolEngine.Match m = engine.match(payload, 0, payload.length, ProtocolEngine.Mode.EXACT, false);
        if (m.status != ProtocolEngine.Status.MATCH) {
            LoggerFactory.getLogger("io").warn("Daeyun RCU \"{}\" RS body matches no message ({})", device,
                    m.reasons.isEmpty() ? "no messages" : m.reasons.get(0));
            return engine.unmatchedReply(payload);
        }
        ProtocolEngine.Reply r = m.message.respond();
        if (r.error != null) LoggerFactory.getLogger("io").warn("Daeyun RCU \"{}\" did not reply to {}: {}", device, m.message.name(), r.error);
        for (String note : r.notes) LoggerFactory.getLogger("io").debug("Daeyun RCU \"{}\" {}: {}", device, m.message.name(), note);
        return r.bytes;
    }

    public static final int ERROR_NO_ERROR = 0;
    public static final int ERROR_AI_DI_CHANNEL_RANGE = 1;
    public static final int ERROR_TX_MATCH_NOT_FOUND = 2;
}