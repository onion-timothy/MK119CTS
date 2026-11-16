package com.onionsoftware.mk119.commtestsuite.protocol.expr;

import com.onionsoftware.mk119.commtestsuite.protocol.ProtocolError;
import com.onionsoftware.mk119.commtestsuite.protocol.Values;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 컴파일된 식 하나 (형식 설계서 6절). {@link Parser#parse} 가 만들고, 실행은 {@link #eval(Scope)}.
 * 이름·경로를 푸는 일은 {@link Scope} 가 한다 — 같은 식이 받는 메시지·보내는 메시지·체크섬에서 다르게 풀린다.
 */
public abstract class Expr {
    /** 원문. 오류 메시지에 쓴다 */
    String source;

    public abstract Object eval(Scope s);

    public String source() { return source; }

    /** 조건 자리 */
    public boolean test(Scope s) {
        Object v = eval(s);
        if (v instanceof Boolean) return (Boolean) v;
        throw ProtocolError.runtime("condition \"%s\" gave %s, not true/false", source, Values.kind(v));
    }

    /** 식이 이름을 푸는 곳 */
    public interface Scope {
        /** 경로: 첫 마디는 이름(String), 뒤는 필드 이름(String) 또는 위치(Long) */
        Object path(Object[] segments);
        /** $이름 — consts·params */
        Object dollar(String name);
        long sizeof(Object[] segments);
        boolean has(Object[] segments);
        /** 장소에 따라 뜻이 정해지는 함수: data, read, fits, writable, slots, valid */
        Object call(String function, List<Object> args);
    }

    // ── 노드 ─────────────────────────────────────────────────────────

    static final class Lit extends Expr {
        final Object value;
        Lit(Object value) { this.value = value; }
        public Object eval(Scope s) { return value; }
    }

    /** 이름·필드·위치로 이어진 경로. sizeof·has 는 값이 아니라 경로를 받는다 */
    static final class Path extends Expr {
        final String root;
        final List<Object> parts = new ArrayList<>();   // String 또는 Expr(위치)
        Path(String root) { this.root = root; }

        Object[] segments(Scope s) {
            Object[] out = new Object[parts.size() + 1];
            out[0] = root;
            for (int i = 0; i < parts.size(); i++) {
                Object p = parts.get(i);
                out[i + 1] = p instanceof Expr ? Values.toLong(((Expr) p).eval(s), "index") : p;
            }
            return out;
        }

        public Object eval(Scope s) { return s.path(segments(s)); }
    }

    static final class Dollar extends Expr {
        final String name;
        Dollar(String name) { this.name = name; }
        public Object eval(Scope s) { return s.dollar(name); }
    }

    static final class ListLit extends Expr {
        final List<Expr> items;
        ListLit(List<Expr> items) { this.items = items; }
        public Object eval(Scope s) {
            List<Object> out = new ArrayList<>();
            for (Expr e : items) out.add(e.eval(s));
            return out;
        }
    }

    static final class Unary extends Expr {
        final String op;
        final Expr a;
        Unary(String op, Expr a) { this.op = op; this.a = a; }
        public Object eval(Scope s) {
            Object v = a.eval(s);
            switch (op) {
                case "-":
                    if (v instanceof Long) return -(Long) v;
                    return -Values.toDouble(v, "operand of -");
                case "!":
                    return !Values.toBool(v, "operand of !");
                default:  // ~
                    return ~Values.toLong(v, "operand of ~");
            }
        }
    }

    static final class Ternary extends Expr {
        final Expr c, a, b;
        Ternary(Expr c, Expr a, Expr b) { this.c = c; this.a = a; this.b = b; }
        public Object eval(Scope s) { return c.test(s) ? a.eval(s) : b.eval(s); }
    }

    static final class Binary extends Expr {
        final String op;
        final Expr a, b;
        Binary(String op, Expr a, Expr b) { this.op = op; this.a = a; this.b = b; }

        public Object eval(Scope s) {
            if (op.equals("&&")) return Values.toBool(a.eval(s), "left of &&") && Values.toBool(b.eval(s), "right of &&");
            if (op.equals("||")) return Values.toBool(a.eval(s), "left of ||") || Values.toBool(b.eval(s), "right of ||");
            Object x = a.eval(s), y = b.eval(s);
            switch (op) {
                case "==": return Values.same(x, y);
                case "!=": return !Values.same(x, y);
                case "<": return Values.compare(x, y) < 0;
                case "<=": return Values.compare(x, y) <= 0;
                case ">": return Values.compare(x, y) > 0;
                case ">=": return Values.compare(x, y) >= 0;
                case "in": return in(x, y);
                case "+":
                    if (x instanceof String || y instanceof String) return Values.text(x) + Values.text(y);
                    if (x instanceof byte[] && y instanceof byte[]) return concat((byte[]) x, (byte[]) y);
                    return arith('+', x, y);
                case "-": return arith('-', x, y);
                case "*": return arith('*', x, y);
                case "/": {
                    double d = Values.toDouble(y, "divisor");
                    if (d == 0) throw ProtocolError.runtime("division by zero in \"%s\"", source);
                    return Values.toDouble(x, "dividend") / d;
                }
                case "//": {
                    if (x instanceof Long && y instanceof Long) {
                        if ((Long) y == 0) throw ProtocolError.runtime("division by zero in \"%s\"", source);
                        return Math.floorDiv((Long) x, (Long) y);
                    }
                    double d = Values.toDouble(y, "divisor");
                    if (d == 0) throw ProtocolError.runtime("division by zero in \"%s\"", source);
                    return (long) Math.floor(Values.toDouble(x, "dividend") / d);
                }
                case "%": {
                    long d = Values.toLong(y, "divisor");
                    if (d == 0) throw ProtocolError.runtime("division by zero in \"%s\"", source);
                    return Math.floorMod(Values.toLong(x, "dividend"), d);
                }
                case "<<": return Values.toLong(x, "left of <<") << Values.toLong(y, "right of <<");
                case ">>": return Values.toLong(x, "left of >>") >> Values.toLong(y, "right of >>");
                case "&": return Values.toLong(x, "left of &") & Values.toLong(y, "right of &");
                case "^": return Values.toLong(x, "left of ^") ^ Values.toLong(y, "right of ^");
                case "|": return Values.toLong(x, "left of |") | Values.toLong(y, "right of |");
                default: throw ProtocolError.runtime("unknown operator %s", op);
            }
        }

        private static Object arith(char op, Object x, Object y) {
            if (x instanceof Long && y instanceof Long) {
                long p = (Long) x, q = (Long) y;
                return op == '+' ? p + q : op == '-' ? p - q : p * q;
            }
            double p = Values.toDouble(x, "operand of " + op), q = Values.toDouble(y, "operand of " + op);
            return op == '+' ? p + q : op == '-' ? p - q : p * q;
        }

        private static boolean in(Object x, Object y) {
            if (y instanceof List) {
                for (Object o : (List<?>) y) if (Values.same(x, o)) return true;
                return false;
            }
            if (y instanceof byte[]) {
                long v = Values.toLong(x, "left of in");
                for (byte c : (byte[]) y) if ((c & 0xFF) == v) return true;
                return false;
            }
            throw ProtocolError.runtime("right of in must be a list, not %s", Values.kind(y));
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 함수 호출. 순수 함수는 여기서, 장소에 기대는 함수는 Scope 에서 */
    static final class Call extends Expr {
        final String name;
        final List<Expr> args;
        Call(String name, List<Expr> args) { this.name = name; this.args = args; }

        public Object eval(Scope s) {
            if (name.equals("sizeof") || name.equals("has")) {
                Object[] seg = ((Path) args.get(0)).segments(s);
                return name.equals("sizeof") ? (Object) s.sizeof(seg) : (Object) s.has(seg);
            }
            if (name.equals("any") || name.equals("all")) {
                // 둘째 인자는 원소마다 다시 계산한다. 그 안에서 it 이 원소
                Object l = args.get(0).eval(s);
                if (!(l instanceof List)) throw ProtocolError.runtime("%s() needs an array, not %s", name, Values.kind(l));
                boolean any = name.equals("any");
                for (Object el : (List<?>) l) {
                    if (args.get(1).test(new Bound(s, "it", el)) == any) return any;
                }
                return !any;
            }
            List<Object> v = new ArrayList<>(args.size());
            for (Expr e : args) v.add(e.eval(s));
            switch (name) {
                case "min": return Values.compare(v.get(0), v.get(1)) <= 0 ? v.get(0) : v.get(1);
                case "max": return Values.compare(v.get(0), v.get(1)) >= 0 ? v.get(0) : v.get(1);
                case "abs": return v.get(0) instanceof Long ? (Object) Math.abs((Long) v.get(0)) : (Object) Math.abs(Values.toDouble(v.get(0), "abs"));
                case "round": return roundHalfAway(Values.toDouble(v.get(0), "round"));
                case "floor": return (long) Math.floor(Values.toDouble(v.get(0), "floor"));
                case "ceil": return (long) Math.ceil(Values.toDouble(v.get(0), "ceil"));
                case "int":
                    if (v.get(0) instanceof String) {
                        try { return Long.parseLong(((String) v.get(0)).trim()); }
                        catch (NumberFormatException e) { throw ProtocolError.runtime("int('%s') is not a whole number", v.get(0)); }
                    }
                    return v.get(0) instanceof Long ? v.get(0) : (Object) (long) Values.toDouble(v.get(0), "int");
                case "real":
                    if (v.get(0) instanceof String) {
                        try { return Double.parseDouble(((String) v.get(0)).trim()); }
                        catch (NumberFormatException e) { throw ProtocolError.runtime("real('%s') is not a number", v.get(0)); }
                    }
                    return Values.toDouble(v.get(0), "real");
                case "text": return Values.text(v.get(0));
                case "hex": {
                    String h = Long.toHexString(Values.toLong(v.get(0), "hex")).toUpperCase(Locale.ROOT);
                    int width = v.size() > 1 ? (int) Values.toLong(v.get(1), "hex width") : 0;
                    StringBuilder sb = new StringBuilder();
                    for (int i = h.length(); i < width; i++) sb.append('0');
                    return sb.append(h).toString();
                }
                case "substr": {
                    Object x = v.get(0);
                    if (x instanceof byte[]) {
                        byte[] b = (byte[]) x;
                        int a = (int) Values.toLong(v.get(1), "substr start");
                        int n = v.size() > 2 ? (int) Values.toLong(v.get(2), "substr length") : b.length - a;
                        a = Math.max(0, Math.min(a, b.length));
                        return Arrays.copyOfRange(b, a, Math.max(a, Math.min(b.length, a + n)));
                    }
                    if (!(x instanceof String)) throw ProtocolError.runtime("substr() needs text or bytes, not %s", Values.kind(x));
                    String t = (String) x;
                    int a = (int) Values.toLong(v.get(1), "substr start");
                    int n = v.size() > 2 ? (int) Values.toLong(v.get(2), "substr length") : t.length() - a;
                    a = Math.max(0, Math.min(a, t.length()));
                    return t.substring(a, Math.max(a, Math.min(t.length(), a + n)));
                }
                case "find": {
                    if (!(v.get(0) instanceof String) || !(v.get(1) instanceof String)) {
                        throw ProtocolError.runtime("find() needs two texts, not %s and %s", Values.kind(v.get(0)), Values.kind(v.get(1)));
                    }
                    return (long) ((String) v.get(0)).indexOf((String) v.get(1));
                }
                case "pack": {
                    long x = Values.toLong(v.get(0), "pack value");
                    int n = (int) Values.toLong(v.get(1), "pack size");
                    if (n < 1 || n > 8) throw ProtocolError.runtime("pack() size must be 1 to 8, not %d", n);
                    boolean little = byteOrder(v.size() > 2 ? v.get(2) : null, "pack");
                    byte[] out = new byte[n];
                    for (int k = 0; k < n; k++) out[little ? k : n - 1 - k] = (byte) (x >>> (8 * k));
                    return out;
                }
                case "unpack": {
                    if (!(v.get(0) instanceof byte[])) throw ProtocolError.runtime("unpack() needs bytes, not %s", Values.kind(v.get(0)));
                    byte[] b = (byte[]) v.get(0);
                    if (b.length < 1 || b.length > 8) throw ProtocolError.runtime("unpack() takes 1 to 8 bytes, not %d", b.length);
                    boolean little = byteOrder(v.size() > 1 ? v.get(1) : null, "unpack");
                    long x = 0;
                    for (int k = 0; k < b.length; k++) x |= (long) (b[little ? k : b.length - 1 - k] & 0xFF) << (8 * k);
                    return x;
                }
                case "len": {
                    Object x = v.get(0);
                    if (x instanceof String) return (long) ((String) x).length();
                    if (x instanceof byte[]) return (long) ((byte[]) x).length;
                    if (x instanceof List) return (long) ((List<?>) x).size();
                    throw ProtocolError.runtime("len() needs text, bytes or a list, not %s", Values.kind(x));
                }
                case "bytes": {
                    Object x = v.get(0);
                    if (x instanceof byte[]) return x;
                    if (x instanceof String) return ((String) x).getBytes(StandardCharsets.UTF_8);
                    throw ProtocolError.runtime("bytes() needs text, not %s", Values.kind(x));
                }
                case "now": return now(v.isEmpty() ? "epoch" : Values.text(v.get(0)));
                default: return s.call(name, v);
            }
        }
    }

    private static boolean byteOrder(Object o, String fn) {
        if (o == null) return false;
        String t = Values.text(o);
        if (t.equals("little")) return true;
        if (t.equals("big")) return false;
        throw ProtocolError.runtime("%s() byte order must be 'big' or 'little', not '%s'", fn, t);
    }

    /**
     * 이름 하나를 값에 묶은 범위. any()·all() 의 it, Write 반복(count)의 i.
     * 그 이름으로 시작하는 경로만 여기서 풀고 나머지는 바깥 범위에 맡긴다.
     */
    public static final class Bound implements Scope {
        private final Scope base;
        private final String name;
        private final Object value;

        public Bound(Scope base, String name, Object value) {
            this.base = base;
            this.name = name;
            this.value = value;
        }

        public Object path(Object[] seg) {
            if (!name.equals(seg[0])) return base.path(seg);
            Object v = value;
            for (int k = 1; k < seg.length; k++) {
                Object p = seg[k];
                if (p instanceof Long) {
                    if (!(v instanceof List)) throw ProtocolError.runtime("[%d] needs an array", p);
                    List<?> l = (List<?>) v;
                    long i = (Long) p;
                    if (i < 0 || i >= l.size()) throw ProtocolError.runtime("index %d is outside the array of %d", i, l.size());
                    v = l.get((int) i);
                } else {
                    if (!(v instanceof Map)) throw ProtocolError.runtime("%s is not inside a structure", p);
                    Map<?, ?> m = (Map<?, ?>) v;
                    if (!m.containsKey(p)) throw ProtocolError.runtime("%s is not a field (or not present)", p);
                    v = m.get(p);
                }
            }
            return v;
        }

        public Object dollar(String n) { return base.dollar(n); }

        public long sizeof(Object[] seg) {
            if (name.equals(seg[0])) throw ProtocolError.runtime("sizeof(%s…) is not supported; use len()", name);
            return base.sizeof(seg);
        }

        public boolean has(Object[] seg) {
            if (!name.equals(seg[0])) return base.has(seg);
            try {
                path(seg);
                return true;
            } catch (ProtocolError.Runtime e) {
                return false;
            }
        }

        public Object call(String function, List<Object> args) { return base.call(function, args); }
    }

    public static long roundHalfAway(double d) {
        return (long) (d < 0 ? -Math.floor(-d + 0.5) : Math.floor(d + 0.5));
    }

    static long now(String part) {
        Calendar c = Calendar.getInstance();
        switch (part) {
            case "year": return c.get(Calendar.YEAR);
            case "month": return c.get(Calendar.MONTH) + 1;
            case "day": return c.get(Calendar.DAY_OF_MONTH);
            case "hour": return c.get(Calendar.HOUR_OF_DAY);
            case "minute": return c.get(Calendar.MINUTE);
            case "second": return c.get(Calendar.SECOND);
            case "epoch": return System.currentTimeMillis() / 1000;
            default: throw ProtocolError.runtime("now('%s'): use year, month, day, hour, minute, second or epoch", part);
        }
    }
}
