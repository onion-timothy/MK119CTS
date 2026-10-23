package com.onionsoftware.mk119.commtestsuite.io.netty.packet.ethernetip;

import java.nio.ByteBuffer;

/**
 * 길이가 홀수인 태그 이름을 etherip 가 읽을 수 있게 고친다.
 * <p>
 * CIP 의 ANSI 심볼 세그먼트({@code 0x91 len name})는 이름 길이가 홀수면 뒤에 채움 바이트 0x00 을 붙인다.
 * etherip 의 인코더는 이 바이트를 붙이지만 {@code CNSymbolPath.fromBuffer} 는 건너뛰지 않아서,
 * 채움 바이트를 다음 세그먼트로 읽다가 예외를 낸다. 디코더는 그 예외를 "덜 온 패킷" 으로 보고 기다리므로
 * 클라이언트는 응답을 받지 못한다.
 * <p>
 * 길이 바이트를 1 늘려 채움 바이트까지 이름으로 읽게 한다. 바꾸는 것은 그 1바이트뿐이라 경로 크기·
 * 메시지 길이는 그대로 맞는다. 읽힌 이름 끝의 NUL 은 {@link #stripPadding} 으로 뗀다.
 * 태그 이름에는 NUL 이 들어갈 수 없으므로 다른 이름과 섞이지 않는다.
 */
public final class SymbolPadding {
    private SymbolPadding() {
    }

    private static final int SEND_RR_DATA = 0x6F;
    private static final int ITEM_UNCONNECTED_DATA = 0xB2;
    private static final int UNCONNECTED_SEND = 0x52;
    private static final int SYMBOL_SEGMENT = 0x91;

    /**
     * 캡슐화 패킷 하나를 제자리에서 고친다. buffer 의 position 부터 limit 까지가 패킷이다.
     * 모양이 예상과 다르거나 패킷이 덜 왔으면 아무것도 하지 않는다.
     */
    public static void fix(ByteBuffer b) {
        int base = b.position();
        int limit = b.limit();
        if (limit - base < 24 || u16(b, base) != SEND_RR_DATA) return;
        int end = base + 24 + u16(b, base + 2);
        if (end > limit) return;                                    // 덜 왔다. 다 오면 다시 불린다
        int cpf = base + 24 + 6;                                    // interface handle(4) + timeout(2)
        if (cpf + 2 > end) return;
        int count = u16(b, cpf);
        int off = cpf + 2;
        for (int i = 0; i < count && off + 4 <= end; i++) {
            int type = u16(b, off);
            int len = u16(b, off + 2);
            int data = off + 4;
            if (data + len > end) return;
            if (type == ITEM_UNCONNECTED_DATA) fixRequest(b, data, data + len);
            off = data + len;
        }
    }

    /** Message Router 요청 하나: service, path size(words), path, 본문. Unconnected Send 면 안의 요청도 고친다. */
    private static void fixRequest(ByteBuffer b, int start, int end) {
        if (start + 2 > end) return;
        int service = b.get(start) & 0xFF;
        int pathStart = start + 2;
        int pathEnd = pathStart + 2 * (b.get(start + 1) & 0xFF);
        if (pathEnd > end) return;
        fixPath(b, pathStart, pathEnd);
        if (service == UNCONNECTED_SEND) {
            // priority/tick(1), timeout ticks(1), message size(2), 내장 요청
            int msg = pathEnd + 4;
            if (msg > end) return;
            int size = u16(b, pathEnd + 2);
            if (msg + size <= end) fixRequest(b, msg, msg + size);
        }
    }

    /** 경로 세그먼트를 따라가며 홀수 길이 심볼의 길이 바이트를 1 늘린다. 모르는 세그먼트를 만나면 멈춘다. */
    private static void fixPath(ByteBuffer b, int start, int end) {
        int p = start;
        while (p < end) {
            int seg = b.get(p) & 0xFF;
            if (seg == SYMBOL_SEGMENT) {
                if (p + 1 >= end) return;
                int len = b.get(p + 1) & 0xFF;
                if (len % 2 == 1) {
                    if (p + 2 + len >= end || b.get(p + 2 + len) != 0) return;   // 채움 바이트가 없으면 건드리지 않는다
                    b.put(p + 1, (byte) (len + 1));
                    len++;
                }
                p += 2 + len;
            } else if (seg == 0x28 || seg == 0x20 || seg == 0x24 || seg == 0x30) {
                p += 2;                                             // 8비트 element / class / instance / attribute
            } else if (seg == 0x29 || seg == 0x21 || seg == 0x25 || seg == 0x31) {
                p += 4;                                             // 16비트: 세그먼트, pad, 값(2)
            } else if (seg == 0x2A) {
                p += 6;                                             // 32비트 element: 세그먼트, pad, 값(4)
            } else {
                return;
            }
        }
    }

    /** {@link #fix} 가 이름에 넣은 채움 바이트(NUL)를 뗀다. */
    public static String stripPadding(String tag) {
        return tag == null ? null : tag.replace("\u0000", "");
    }

    private static int u16(ByteBuffer b, int at) {
        return (b.get(at) & 0xFF) | (b.get(at + 1) & 0xFF) << 8;
    }
}
