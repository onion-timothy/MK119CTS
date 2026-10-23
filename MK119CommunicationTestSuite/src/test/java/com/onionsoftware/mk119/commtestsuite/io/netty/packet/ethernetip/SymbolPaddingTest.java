package com.onionsoftware.mk119.commtestsuite.io.netty.packet.ethernetip;

import etherip.protocol.Encapsulation;
import etherip.protocol.MessageRouterProtocol;
import etherip.protocol.SendRRDataProtocol;
import etherip.protocol.UnconnectedSendProtocol;
import etherip.types.CNSymbolPath;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 홀수 길이 태그 이름. 요청 바이트는 규격대로 만든 클라이언트(tools/testkit/eipclient.py)가 실제로 보낸 것이다.
 * 고친 버퍼를 etherip 의 디코더가 읽어 태그 이름을 돌려주는지까지 본다.
 */
class SymbolPaddingTest {
    /** Read Tag "R" — 0x91 01 'R' 00(채움) */
    private static final String READ_R = "6f0026000000000000000000000000000000000000000000"
            + "000000000000020000000000b20016005202200624010af008004c0291015200010001000100";
    /** Read Tag "TAG1" — 짝수 길이, 채움 없음 */
    private static final String READ_TAG1 = "6f0028000000000000000000000000000000000000000000"
            + "000000000000020000000000b20018005202200624010af00a004c03910454414731010001000100";
    /** Write Tag "SI32" INT 3 — 짝수 길이 */
    private static final String WRITE_SI32 = "6f002c000000000000000000000000000000000000000000"
            + "000000000000020000000000b2001c005202200624010af00e004d03910453493332c3000100030001000100";

    @Test
    void oddLengthNameIsReadAfterFix() throws Exception {
        ByteBuffer raw = buffer(READ_R);
        assertThrows(Exception.class, () -> Encapsulation.fromBuffer(raw), "etherip alone cannot read it");

        ByteBuffer fixed = buffer(READ_R);
        SymbolPadding.fix(fixed);
        assertEquals("R", tagOf(Encapsulation.fromBuffer(fixed)));
    }

    @Test
    void evenLengthNamesAreUntouched() throws Exception {
        for (String hex : new String[]{READ_TAG1, WRITE_SI32}) {
            ByteBuffer b = buffer(hex);
            byte[] before = copy(b);
            SymbolPadding.fix(b);
            assertArrayEquals(before, copy(b));
        }
        ByteBuffer b = buffer(READ_TAG1);
        SymbolPadding.fix(b);
        assertEquals("TAG1", tagOf(Encapsulation.fromBuffer(b)));
    }

    @Test
    void incompletePacketIsLeftAlone() {
        byte[] full = hex(READ_R);
        ByteBuffer partial = ByteBuffer.wrap(java.util.Arrays.copyOf(full, full.length - 6)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] before = copy(partial);
        SymbolPadding.fix(partial);
        assertArrayEquals(before, copy(partial));
    }

    private static String tagOf(Encapsulation e) {
        SendRRDataProtocol rr = (SendRRDataProtocol) e.getBody();
        UnconnectedSendProtocol us = (UnconnectedSendProtocol) rr.getBody();
        MessageRouterProtocol mr = (MessageRouterProtocol) us.getBody();
        CNSymbolPath path = (CNSymbolPath) mr.getPath();
        return SymbolPadding.stripPadding(path.getElements().get(0).toString());
    }

    private static ByteBuffer buffer(String hex) {
        return ByteBuffer.wrap(hex(hex)).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static byte[] copy(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }
}
