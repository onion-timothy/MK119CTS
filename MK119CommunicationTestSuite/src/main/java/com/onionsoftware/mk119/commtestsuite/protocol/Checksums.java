package com.onionsoftware.mk119.commtestsuite.protocol;

import com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr;

import java.util.*;

/**
 * 체크섬 알고리즘 (형식 설계서 8.2). CRC 는 Rocksoft 매개변수 모델이다.
 * 카탈로그의 check 값("123456789" 의 결과)은 단위 시험의 기준이다 — js/cts-protocol-model.js 의 CRC_CATALOG 와 같다.
 */
public final class Checksums {
    private Checksums() {}

    /** 바이트 범위를 정수 하나로 */
    public interface Algorithm {
        long compute(byte[] data, int from, int to);
    }

    public static final class Crc implements Algorithm {
        final int width;
        final long poly, init, xorout, mask;
        final boolean refin, refout;

        public Crc(int width, long poly, long init, boolean refin, boolean refout, long xorout) {
            this.width = width;
            this.mask = width == 64 ? -1L : (1L << width) - 1;
            this.poly = poly & mask;
            this.init = init & mask;
            this.refin = refin;
            this.refout = refout;
            this.xorout = xorout & mask;
        }

        public long compute(byte[] data, int from, int to) {
            long crc = init;
            long top = 1L << (width - 1);
            for (int i = from; i < to; i++) {
                int b = data[i] & 0xFF;
                if (refin) b = (int) reflect(b, 8);
                if (width >= 8) {
                    crc ^= ((long) b) << (width - 8);
                    for (int k = 0; k < 8; k++) crc = ((crc & top) != 0 ? (crc << 1) ^ poly : crc << 1) & mask;
                } else {
                    for (int k = 7; k >= 0; k--) {
                        boolean bit = ((b >> k) & 1) != 0 ^ (crc & top) != 0;
                        crc = ((crc << 1) & mask) ^ (bit ? poly : 0);
                    }
                }
            }
            if (refout) crc = reflect(crc, width);
            return (crc ^ xorout) & mask;
        }
    }

    static long reflect(long v, int bits) {
        long r = 0;
        for (int i = 0; i < bits; i++) if ((v & (1L << i)) != 0) r |= 1L << (bits - 1 - i);
        return r;
    }

    /** 카탈로그 이름 → CRC. 없으면 null */
    public static Crc catalog(String name) {
        switch (name) {
            case "CRC-8/SMBUS": return new Crc(8, 0x07, 0x00, false, false, 0x00);
            case "CRC-16/ARC": return new Crc(16, 0x8005, 0x0000, true, true, 0x0000);
            case "CRC-16/MODBUS": return new Crc(16, 0x8005, 0xFFFF, true, true, 0x0000);
            case "CRC-16/XMODEM": return new Crc(16, 0x1021, 0x0000, false, false, 0x0000);
            case "CRC-16/IBM-3740": return new Crc(16, 0x1021, 0xFFFF, false, false, 0x0000);
            case "CRC-16/KERMIT": return new Crc(16, 0x1021, 0x0000, true, true, 0x0000);
            case "CRC-32/ISO-HDLC": return new Crc(32, 0x04C11DB7L, 0xFFFFFFFFL, true, true, 0xFFFFFFFFL);
            default: return null;
        }
    }

    public static final List<String> CATALOG = Collections.unmodifiableList(Arrays.asList(
            "CRC-8/SMBUS", "CRC-16/ARC", "CRC-16/MODBUS", "CRC-16/XMODEM", "CRC-16/IBM-3740", "CRC-16/KERMIT", "CRC-32/ISO-HDLC"));

    /** 바이트 합. complement: none / ones / twos */
    public static Algorithm sum(int width, String complement) {
        final long mask = width >= 64 ? -1L : (1L << width) - 1;
        return (data, from, to) -> {
            long s = 0;
            for (int i = from; i < to; i++) s += data[i] & 0xFF;
            if ("ones".equals(complement)) s = ~s;
            else if ("twos".equals(complement)) s = -s;
            return s & mask;
        };
    }

    public static final Algorithm XOR8 = (data, from, to) -> {
        int x = 0;
        for (int i = from; i < to; i++) x ^= data[i] & 0xFF;
        return x;
    };

    /** Modbus ASCII: 합의 2의 보수 */
    public static final Algorithm LRC8 = (data, from, to) -> {
        int s = 0;
        for (int i = from; i < to; i++) s += data[i] & 0xFF;
        return (-s) & 0xFF;
    };

    /** 업체 고유 계산: acc = init; 바이트마다 acc = step(acc, b); 결과 final(acc) */
    public static Algorithm expr(Expr init, Expr step, Expr fin) {
        return (data, from, to) -> {
            Map<String, Object> vars = new HashMap<>();
            Expr.Scope scope = new Expr.Scope() {
                public Object path(Object[] seg) {
                    if (seg.length == 1 && vars.containsKey(seg[0])) return vars.get(seg[0]);
                    throw ProtocolError.runtime("checksum expressions can use only acc and b, not %s", seg[0]);
                }
                public Object dollar(String name) { throw ProtocolError.runtime("checksum expressions cannot use $%s", name); }
                public long sizeof(Object[] seg) { throw ProtocolError.runtime("checksum expressions cannot use sizeof()"); }
                public boolean has(Object[] seg) { throw ProtocolError.runtime("checksum expressions cannot use has()"); }
                public Object call(String f, List<Object> a) { throw ProtocolError.runtime("checksum expressions cannot use %s()", f); }
            };
            vars.put("acc", init == null ? 0L : init.eval(scope));
            for (int i = from; i < to; i++) {
                vars.put("b", (long) (data[i] & 0xFF));
                vars.put("acc", step.eval(scope));
            }
            Object r = fin == null ? vars.get("acc") : fin.eval(scope);
            return Values.toLong(r, "checksum result");
        };
    }
}
