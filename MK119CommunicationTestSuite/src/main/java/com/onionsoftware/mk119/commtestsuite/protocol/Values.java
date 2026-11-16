package com.onionsoftware.mk119.commtestsuite.protocol;

import java.util.*;

/**
 * 식과 필드가 다루는 값. 자바 객체를 그대로 쓴다 (형식 설계서 6.3).
 * <pre>
 *   정수     Long          실수   Double        참거짓  Boolean
 *   글자     String        바이트  byte[]        목록    List&lt;Object&gt;
 *   구조체   Map&lt;String, Object&gt; (필드 순서를 지키는 LinkedHashMap)
 * </pre>
 */
public final class Values {
    private Values() {}

    public static boolean isNumber(Object v) { return v instanceof Long || v instanceof Double; }

    public static String kind(Object v) {
        if (v == null) return "nothing";
        if (v instanceof Long) return "integer";
        if (v instanceof Double) return "number";
        if (v instanceof Boolean) return "true/false";
        if (v instanceof String) return "text";
        if (v instanceof byte[]) return "bytes";
        if (v instanceof List) return "list";
        if (v instanceof Map) return "structure";
        return v.getClass().getSimpleName();
    }

    public static double toDouble(Object v, String what) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) return (Double) v;
        throw ProtocolError.runtime("%s must be a number, not %s", what, kind(v));
    }

    /** 정수. 소수가 없는 실수는 받아 준다 (220.0 → 220) */
    public static long toLong(Object v, String what) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && !Double.isInfinite(d)) return (long) d;
            throw ProtocolError.runtime("%s must be a whole number, not %s", what, fmt(d));
        }
        throw ProtocolError.runtime("%s must be a whole number, not %s", what, kind(v));
    }

    public static boolean toBool(Object v, String what) {
        if (v instanceof Boolean) return (Boolean) v;
        throw ProtocolError.runtime("%s must be true or false, not %s", what, kind(v));
    }

    /** 수를 계산 결과로: 소수가 없고 범위 안이면 정수로 둔다 */
    public static Object number(double d) {
        return d;
    }

    /** text(x) — 사람이 읽는 모양. 220.0 은 '220', 0.1 은 '0.1' */
    public static String text(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Double) return fmt((Double) v);
        if (v instanceof byte[]) return hex((byte[]) v);
        return String.valueOf(v);
    }

    public static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        return s;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    /** "01 03 0A" / "01030A" → 바이트. 형식이 틀리면 null */
    public static byte[] parseHex(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", "");
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        if (t.length() % 2 != 0 || !t.matches("[0-9A-Fa-f]*")) return null;
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /** 같은가. 수는 값으로(1 == 1.0), 바이트는 내용으로 */
    public static boolean same(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            if (a instanceof Long && b instanceof Long) return ((Long) a).longValue() == (Long) b;
            return toDouble(a, "") == toDouble(b, "");
        }
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        return Objects.equals(a, b);
    }

    public static int compare(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            if (a instanceof Long && b instanceof Long) return Long.compare((Long) a, (Long) b);
            return Double.compare(toDouble(a, ""), toDouble(b, ""));
        }
        if (a instanceof String && b instanceof String) return ((String) a).compareTo((String) b);
        throw ProtocolError.runtime("cannot compare %s with %s", kind(a), kind(b));
    }
}
