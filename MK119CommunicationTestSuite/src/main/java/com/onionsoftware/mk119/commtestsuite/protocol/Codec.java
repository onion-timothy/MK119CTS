package com.onionsoftware.mk119.commtestsuite.protocol;

import com.onionsoftware.mk119.commtestsuite.protocol.Definition.FieldSpec;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.Kind;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.Transform;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 낱개 필드의 바이트 ↔ 값 (형식 설계서 4.1·5절).
 * <pre>
 *   받을 때   전송 바이트 → raw → 물리값 = raw × scale + offset
 *   보낼 때   물리값 → raw = round((물리값 − offset) / scale) → 범위 검사(onOverflow) → 바이트
 * </pre>
 * 구조체·배열·조건은 {@link RxReader}·{@link TxWriter} 가 맡고, 여기는 한 칸만 다룬다.
 */
public final class Codec {
    private Codec() {}

    // ── 숫자형 ───────────────────────────────────────────────────────

    /** 이름 끝 글자가 정하는 바이트 순서 */
    private enum Order { BIG, LITTLE, WORD_SWAP, BYTE_SWAP }

    private static Order orderOf(FieldSpec f) {
        switch (f.byteOrder == null ? "big" : f.byteOrder) {
            case "little": return Order.LITTLE;
            case "word-swap": return Order.WORD_SWAP;
            case "byte-swap": return Order.BYTE_SWAP;
            default: return Order.BIG;
        }
    }

    /** 빅 엔디언 바이트를 그 타입의 전송 순서로 (되돌리기도 같은 함수 — 모두 자기 역이다) */
    private static byte[] permute(byte[] be, Order o) {
        int n = be.length;
        byte[] out = new byte[n];
        switch (o) {
            case LITTLE:
                for (int i = 0; i < n; i++) out[i] = be[n - 1 - i];
                return out;
            case WORD_SWAP: {
                int words = n / 2;
                for (int w = 0; w < words; w++) {
                    out[2 * w] = be[2 * (words - 1 - w)];
                    out[2 * w + 1] = be[2 * (words - 1 - w) + 1];
                }
                return out;
            }
            case BYTE_SWAP:
                for (int i = 0; i + 1 < n; i += 2) { out[i] = be[i + 1]; out[i + 1] = be[i]; }
                return out;
            default:
                return be.clone();
        }
    }

    private static long minOf(FieldSpec f) {
        int bits = f.size * 8;
        if (!f.isSigned()) return 0;
        return bits == 64 ? Long.MIN_VALUE : -(1L << (bits - 1));
    }

    /** UINT 8 은 long 의 양수 범위까지만 (2^63 − 1) */
    private static long maxOf(FieldSpec f) {
        int bits = f.size * 8;
        if (f.isSigned()) return bits == 64 ? Long.MAX_VALUE : (1L << (bits - 1)) - 1;
        return bits >= 64 ? Long.MAX_VALUE : (1L << bits) - 1;
    }

    /** 범위 밖 정수: error / clamp / wrap */
    static long overflow(FieldSpec f, long raw, long min, long max, String what) {
        if (raw >= min && raw <= max) return raw;
        switch (f.onOverflow) {
            case "clamp": return raw < min ? min : max;
            case "wrap": {
                long span = max - min + 1;
                if (span <= 0) return raw;
                return min + Math.floorMod(raw - min, span);
            }
            default:
                throw ProtocolError.runtime("%s value %d is out of range (%d..%d) for field %s",
                        what, raw, min, max, label(f));
        }
    }

    static String label(FieldSpec f) {
        return f.name != null ? "\"" + f.name + "\"" : "(unnamed)";
    }

    public static byte[] encodeNumber(FieldSpec f, Object physical, boolean isRaw) {
        int n = f.size;
        byte[] be = new byte[n];
        if (f.isReal()) {
            double raw = isRaw ? Values.toDouble(physical, label(f)) : (Values.toDouble(physical, label(f)) - f.offset) / f.scale;
            long bits = n == 4 ? Float.floatToIntBits((float) raw) & 0xFFFFFFFFL : Double.doubleToLongBits(raw);
            for (int i = 0; i < n; i++) be[i] = (byte) (bits >>> (8 * (n - 1 - i)));
        } else {
            long raw = isRaw ? Values.toLong(physical, label(f)) : rawLong(f, physical);
            raw = overflow(f, raw, minOf(f), maxOf(f), f.numText());
            for (int i = 0; i < n; i++) be[i] = (byte) (raw >>> (8 * (n - 1 - i)));
        }
        return permute(be, orderOf(f));
    }

    /** 물리값 → 정수 raw. 반올림은 가장 가까운 정수, .5 는 0 에서 먼 쪽 */
    static long rawLong(FieldSpec f, Object physical) {
        if (physical instanceof Long && !f.scaled) return (Long) physical;
        double p = Values.toDouble(physical, label(f));
        return Expr.roundHalfAway((p - f.offset) / f.scale);
    }

    /** raw 를 물리값으로. scale·offset 이 없는 정수는 정수로 둔다 (비트 연산을 쓸 수 있게) */
    static Object physical(FieldSpec f, long raw) {
        if (!f.scaled) return raw;
        return raw * f.scale + f.offset;
    }

    public static Object decodeNumber(FieldSpec f, byte[] buf, int at) {
        int n = f.size;
        byte[] be = permute(Arrays.copyOfRange(buf, at, at + n), orderOf(f));
        long bits = 0;
        for (byte b : be) bits = (bits << 8) | (b & 0xFF);
        if (f.isReal()) {
            double raw = n == 4 ? Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
            return raw * f.scale + f.offset;
        }
        if (f.isSigned() && n < 8) bits = (bits << (64 - 8 * n)) >> (64 - 8 * n);
        return physical(f, bits);
    }

    /** const 비교용 raw (숫자형). 실수형은 Double */
    public static Object rawOf(FieldSpec f, byte[] buf, int at) {
        int n = f.size;
        byte[] be = permute(Arrays.copyOfRange(buf, at, at + n), orderOf(f));
        long bits = 0;
        for (byte b : be) bits = (bits << 8) | (b & 0xFF);
        if (f.isReal()) return n == 4 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
        if (f.isSigned() && n < 8) bits = (bits << (64 - 8 * n)) >> (64 - 8 * n);
        return bits;
    }

    // ── BCD ──────────────────────────────────────────────────────────

    public static byte[] encodeBcd(FieldSpec f, Object value, boolean isRaw) {
        int size = f.size;
        long raw = isRaw ? Values.toLong(value, label(f)) : rawLong(f, value);
        long max = 1;
        for (int i = 0; i < size * 2 && max < Long.MAX_VALUE / 10; i++) max *= 10;
        raw = overflow(f, raw, 0, max - 1, "BCD");
        byte[] out = new byte[size];
        for (int i = size - 1; i >= 0; i--) {
            int lo = (int) (raw % 10); raw /= 10;
            int hi = (int) (raw % 10); raw /= 10;
            out[i] = (byte) ((hi << 4) | lo);
        }
        if (f.littleBytes) reverse(out);
        return out;
    }

    public static long decodeBcdRaw(FieldSpec f, byte[] buf, int from, int to) {
        byte[] b = Arrays.copyOfRange(buf, from, to);
        if (f.littleBytes) reverse(b);
        long v = 0;
        for (byte x : b) {
            int hi = (x >> 4) & 0xF, lo = x & 0xF;
            if (hi > 9 || lo > 9) throw ProtocolError.noMatch("field %s is not BCD (%02X)", label(f), x & 0xFF);
            v = v * 100 + hi * 10 + lo;
        }
        return v;
    }

    // ── 글자 ─────────────────────────────────────────────────────────

    public static Charset charset(String encoding) {
        switch (encoding == null ? "ascii" : encoding) {
            case "utf-8": return StandardCharsets.UTF_8;
            case "utf-16be": return StandardCharsets.UTF_16BE;
            case "utf-16le": return StandardCharsets.UTF_16LE;
            case "euc-kr": return Charset.forName("EUC-KR");
            default: return StandardCharsets.US_ASCII;
        }
    }

    /** 고정 크기에 맞추기: 남는 자리는 pad, align 에 따라 앞/뒤에 */
    private static byte[] fit(FieldSpec f, byte[] body, String defaultPad, String defaultAlign, String what) {
        if (f.size == null) return body;
        int size = f.size;
        if (body.length > size) {
            throw ProtocolError.runtime("%s \"%s\" is longer than the %d bytes of field %s", what,
                    new String(body, charset(f.encoding)), size, label(f));
        }
        byte[] pad = (f.pad != null ? f.pad : defaultPad).getBytes(charset(f.encoding));
        if (pad.length == 0) pad = new byte[]{' '};
        byte[] out = new byte[size];
        int fillCount = size - body.length;
        boolean right = "right".equals(f.align != null ? f.align : defaultAlign);
        int start = right ? fillCount : 0;
        System.arraycopy(body, 0, out, start, body.length);
        for (int i = 0; i < fillCount; i++) out[right ? i : body.length + i] = pad[i % pad.length];
        return out;
    }

    private static byte[] withTerminator(FieldSpec f, byte[] b) {
        if (f.terminator == null || f.size != null) return b;
        byte[] out = Arrays.copyOf(b, b.length + f.terminator.length);
        System.arraycopy(f.terminator, 0, out, b.length, f.terminator.length);
        return out;
    }

    public static byte[] encodeText(FieldSpec f, Object value) {
        String s = value instanceof String ? (String) value : Values.text(value);
        Charset cs = charset(f.encoding);
        if (cs == StandardCharsets.US_ASCII && !cs.newEncoder().canEncode(s)) {
            throw ProtocolError.runtime("field %s is ASCII but \"%s\" has other characters", label(f), s);
        }
        return withTerminator(f, fit(f, s.getBytes(cs), " ", "left", "Text"));
    }

    /** 글자 바이트에서 값: 끝 글자를 떼고 채움 글자를 걷어 낸다 */
    public static String decodeText(FieldSpec f, byte[] buf, int from, int to) {
        int end = to;
        if (f.terminator != null && f.size == null) end -= f.terminator.length;
        String s = new String(buf, from, end - from, charset(f.encoding));
        if (f.size != null) {
            String pad = f.pad != null ? f.pad : " ";
            if (!pad.isEmpty()) {
                boolean right = "right".equals(f.align);
                if (right) while (s.startsWith(pad)) s = s.substring(pad.length());
                else while (s.endsWith(pad)) s = s.substring(0, s.length() - pad.length());
            }
        }
        return s;
    }

    public static byte[] encodeTextNum(FieldSpec f, Object value, boolean isRaw) {
        double raw = isRaw ? Values.toDouble(value, label(f)) : (Values.toDouble(value, label(f)) - f.offset) / f.scale;
        boolean negative = raw < 0;
        if (negative && "none".equals(f.sign)) {
            raw = overflowText(f, raw, "a negative value but sign is none");
            negative = raw < 0;
        }
        String digits = digits(f, Math.abs(raw));
        String sign = negative ? "-" : "always".equals(f.sign) ? "+" : "";
        Charset cs = charset(f.encoding);
        if (f.size != null) {
            int room = f.size - sign.length();
            if (digits.length() > room) {
                if ("clamp".equals(f.onOverflow)) digits = clampDigits(f, room);
                else if ("wrap".equals(f.onOverflow)) digits = digits.substring(digits.length() - room);
                else throw ProtocolError.runtime("value %s does not fit in the %d characters of field %s",
                            Values.fmt(raw), f.size, label(f));
            }
            String pad = f.pad != null ? f.pad : "0";
            boolean right = !"left".equals(f.align);
            StringBuilder sb = new StringBuilder();
            int fillCount = room - digits.length();
            if (right && "0".equals(pad)) {
                sb.append(sign);
                for (int i = 0; i < fillCount; i++) sb.append(pad);
                sb.append(digits);
            } else if (right) {
                for (int i = 0; i < fillCount; i++) sb.append(pad);
                sb.append(sign).append(digits);
            } else {
                sb.append(sign).append(digits);
                for (int i = 0; i < fillCount; i++) sb.append(pad);
            }
            return sb.toString().getBytes(cs);
        }
        return withTerminator(f, (sign + digits).getBytes(cs));
    }

    private static double overflowText(FieldSpec f, double raw, String why) {
        if ("clamp".equals(f.onOverflow)) return 0;
        throw ProtocolError.runtime("field %s got %s", label(f), why);
    }

    private static String digits(FieldSpec f, double abs) {
        if (f.decimals <= 0) return String.valueOf(Expr.roundHalfAway(abs));
        String s = BigDecimal.valueOf(abs).setScale(f.decimals, RoundingMode.HALF_UP).toPlainString();
        return f.point ? s : s.replace(".", "");
    }

    private static String clampDigits(FieldSpec f, int room) {
        StringBuilder sb = new StringBuilder();
        int intDigits = f.decimals > 0 && f.point ? room - f.decimals - 1 : room - (f.point ? 0 : 0);
        if (f.decimals > 0 && f.point) {
            for (int i = 0; i < Math.max(0, intDigits); i++) sb.append('9');
            sb.append('.');
            for (int i = 0; i < f.decimals; i++) sb.append('9');
        } else {
            for (int i = 0; i < room; i++) sb.append('9');
        }
        return sb.toString();
    }

    public static Object decodeTextNum(FieldSpec f, byte[] buf, int from, int to) {
        String s = decodeText(f, buf, from, to).trim();
        String pad = f.pad != null ? f.pad : "0";
        if (!pad.equals("0") && !pad.isEmpty()) {
            while (s.startsWith(pad)) s = s.substring(pad.length());
            while (s.endsWith(pad)) s = s.substring(0, s.length() - pad.length());
            s = s.trim();
        }
        if (!s.matches("[+-]?[0-9]+(\\.[0-9]*)?")) throw ProtocolError.noMatch("field %s is not a decimal number: \"%s\"", label(f), s);
        double raw;
        boolean whole = !s.contains(".");
        try {
            raw = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw ProtocolError.noMatch("field %s is not a decimal number: \"%s\"", label(f), s);
        }
        if (!f.point && f.decimals > 0) { raw = raw / Math.pow(10, f.decimals); whole = false; }
        if (whole && !f.scaled && f.decimals == 0) return (long) raw;
        return raw * f.scale + f.offset;
    }

    public static byte[] encodeHexNum(FieldSpec f, Object value, boolean isRaw) {
        long raw = isRaw ? Values.toLong(value, label(f)) : rawLong(f, value);
        int width = f.size != null ? f.size : 0;
        long max = width > 0 && width < 16 ? (1L << (4 * width)) - 1 : Long.MAX_VALUE;
        raw = overflow(f, raw, 0, max, "HEXNUM");
        String h = Long.toHexString(raw);
        h = f.lowerCase ? h.toLowerCase(Locale.ROOT) : h.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = h.length(); i < width; i++) sb.append('0');
        return withTerminator(f, sb.append(h).toString().getBytes(StandardCharsets.US_ASCII));
    }

    public static long decodeHexNumRaw(FieldSpec f, byte[] buf, int from, int to) {
        String s = decodeText(f, buf, from, to).trim();
        if (s.isEmpty() || !s.matches("[0-9A-Fa-f]+")) throw ProtocolError.noMatch("field %s is not hex text: \"%s\"", label(f), s);
        try {
            return Long.parseUnsignedLong(s, 16);
        } catch (NumberFormatException e) {
            throw ProtocolError.noMatch("field %s is too long for a number: \"%s\"", label(f), s);
        }
    }

    // ── BITS ─────────────────────────────────────────────────────────

    /** 하위 필드 값들을 size 바이트로. lsb: 첫 하위 필드가 맨 아래 비트 */
    public static byte[] encodeBits(FieldSpec f, List<Long> values) {
        long acc = 0;
        // 비트 필드마다 제 자리(bitAt, 맨 아래 비트 = 0)에 놓는다. 어느 필드도 차지하지 않은 비트는 0
        for (int k = 0; k < f.fields.size(); k++) {
            FieldSpec sub = f.fields.get(k);
            long v = values.get(k);
            long max = sub.bits >= 63 ? Long.MAX_VALUE : (1L << sub.bits) - 1;
            v = overflow(sub, v, 0, max, sub.bits + "-bit field");
            acc |= v << sub.bitAt;
        }
        byte[] out = new byte[f.size];
        for (int i = 0; i < f.size; i++) out[i] = (byte) (acc >>> (8 * (f.size - 1 - i)));
        return out;
    }

    public static List<Long> decodeBits(FieldSpec f, byte[] buf, int from) {
        long acc = 0;
        for (int i = 0; i < f.size; i++) acc = (acc << 8) | (buf[from + i] & 0xFF);
        List<Long> out = new ArrayList<>();
        for (FieldSpec sub : f.fields) {
            long mask = sub.bits >= 64 ? -1L : (1L << sub.bits) - 1;
            out.add((acc >>> sub.bitAt) & mask);
        }
        return out;
    }

    // ── 변환 ─────────────────────────────────────────────────────────

    public static byte[] applyTransforms(List<Transform> ts, byte[] b) {
        for (Transform t : ts) {
            switch (t.op) {
                case "ascii-hex": {
                    StringBuilder sb = new StringBuilder();
                    for (byte x : b) sb.append(String.format(t.lowerCase ? "%02x" : "%02X", x & 0xFF));
                    b = sb.toString().getBytes(StandardCharsets.US_ASCII);
                    break;
                }
                case "add": {
                    byte[] o = new byte[b.length];
                    for (int i = 0; i < b.length; i++) o[i] = (byte) ((b[i] + t.value) & 0xFF);
                    b = o;
                    break;
                }
                case "xor": {
                    byte[] o = new byte[b.length];
                    for (int i = 0; i < b.length; i++) o[i] = (byte) ((b[i] ^ t.value) & 0xFF);
                    b = o;
                    break;
                }
                default:
                    throw ProtocolError.runtime("unknown transform %s", t.op);
            }
        }
        return b;
    }

    /** 받을 때: 변환을 거꾸로 되돌린다 */
    public static byte[] undoTransforms(List<Transform> ts, byte[] b) {
        for (int k = ts.size() - 1; k >= 0; k--) {
            Transform t = ts.get(k);
            switch (t.op) {
                case "ascii-hex": {
                    if (b.length % 2 != 0) throw ProtocolError.noMatch("ascii-hex bytes come in pairs");
                    byte[] o = new byte[b.length / 2];
                    for (int i = 0; i < o.length; i++) {
                        int hi = Character.digit((char) b[2 * i], 16), lo = Character.digit((char) b[2 * i + 1], 16);
                        if (hi < 0 || lo < 0) throw ProtocolError.noMatch("not hex text");
                        o[i] = (byte) ((hi << 4) | lo);
                    }
                    b = o;
                    break;
                }
                case "add": {
                    byte[] o = new byte[b.length];
                    for (int i = 0; i < b.length; i++) o[i] = (byte) ((b[i] - t.value) & 0xFF);
                    b = o;
                    break;
                }
                case "xor": {
                    byte[] o = new byte[b.length];
                    for (int i = 0; i < b.length; i++) o[i] = (byte) ((b[i] ^ t.value) & 0xFF);
                    b = o;
                    break;
                }
                default:
                    throw ProtocolError.runtime("unknown transform %s", t.op);
            }
        }
        return b;
    }

    // ── 한 칸 인코딩 (보낼 때) ───────────────────────────────────────

    /**
     * 낱개 필드(구조체·BITS 밖)를 바이트로.
     * @param isRaw const·체크섬처럼 이미 raw 인 값이면 true (scale 을 거치지 않는다)
     */
    public static byte[] encodeLeaf(FieldSpec f, Object value, boolean isRaw) {
        switch (f.kind) {
            case NUM: return encodeNumber(f, value, isRaw);
            case BCD: return encodeBcd(f, value, isRaw);
            case TEXT: return encodeText(f, value);
            case TEXTNUM: return encodeTextNum(f, value, isRaw);
            case HEXNUM: return encodeHexNum(f, value, isRaw);
            case BYTES: {
                byte[] b;
                if (value instanceof byte[]) b = (byte[]) value;
                else if (value instanceof String) b = ((String) value).getBytes(charset(f.encoding));
                else throw ProtocolError.runtime("field %s needs bytes, not %s", label(f), Values.kind(value));
                if (f.size != null && b.length != f.size) {
                    throw ProtocolError.runtime("field %s is %d bytes but the value has %d", label(f), f.size, b.length);
                }
                return b;
            }
            case PAD: {
                byte[] b = new byte[f.size == null ? 0 : f.size];
                Arrays.fill(b, (byte) f.fill);
                return b;
            }
            default:
                throw ProtocolError.runtime("field %s cannot be encoded on its own", label(f));
        }
    }

    /** 체크섬 자리를 채울 때의 0 (같은 폭) */
    public static byte[] placeholder(FieldSpec f) {
        return encodeLeaf(f, 0L, true);
    }

    static void reverse(byte[] b) {
        for (int i = 0, j = b.length - 1; i < j; i++, j--) { byte t = b[i]; b[i] = b[j]; b[j] = t; }
    }

    static boolean isText(Kind k) { return k == Kind.TEXT || k == Kind.TEXTNUM || k == Kind.HEXNUM; }
}
