package com.onionsoftware.mk119.commtestsuite.protocol;

import com.onionsoftware.mk119.commtestsuite.protocol.Definition.ChecksumSpec;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.FieldSpec;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.Kind;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.MessageSpec;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr;

import java.util.*;

/**
 * 받은 바이트를 rx 메시지 하나로 읽는다 (docs/PROTOCOL_ENGINE_DESIGN.md 5.1).
 * 맞으면 {@link #read()} 가 값을 돌려주고, 아니면 {@link ProtocolError.NoMatch}, 모자라면 {@link ProtocolError.NeedMore}.
 * 읽은 뒤에는 응답 규칙의 식을 푸는 {@link Expr.Scope} 로도 쓴다.
 */
final class RxReader implements Expr.Scope {
    /** 바이트 범위 — 체크섬이 덮는 곳을 찾는 데 쓴다. buf 는 변환 안쪽이면 그 안쪽 바이트 */
    static final class Span {
        final byte[] buf;
        final int from, to;
        Span(byte[] buf, int from, int to) { this.buf = buf; this.from = from; this.to = to; }
    }

    private final ProtocolEngine engine;
    final MessageSpec spec;
    private byte[] buf;
    private int pos;
    private int limit;
    private final int start, end;
    private final boolean stream;

    final Map<String, Object> tree = new LinkedHashMap<>();
    final Map<String, Span> spans = new HashMap<>();
    final Map<String, Boolean> checksumOk = new HashMap<>();
    private final List<Object[]> checksumFields = new ArrayList<>();   // {spec, raw value, span}

    /** 이름 찾기: 안쪽 구조체부터 */
    private final Deque<Map<String, Object>> scopes = new ArrayDeque<>();
    private Map<String, Object> region;
    private Object thisValue;
    private final Deque<Long> indexes = new ArrayDeque<>();
    private boolean parsing = true;

    RxReader(ProtocolEngine engine, MessageSpec spec, byte[] buf, int from, int to, boolean stream) {
        this.engine = engine;
        this.spec = spec;
        this.buf = buf;
        this.pos = from;
        this.start = from;
        this.end = to;
        this.limit = to;
        this.stream = stream;
        tree.put("header", new LinkedHashMap<String, Object>());
        tree.put("body", new LinkedHashMap<String, Object>());
        tree.put("footer", new LinkedHashMap<String, Object>());
    }

    int consumed() { return pos - start; }

    ProtocolEngine engine() { return engine; }

    byte[] bytes() { return Arrays.copyOfRange(buf, start, pos); }

    /** @return 이 메시지가 차지한 바이트 수 */
    int read() {
        readRegion("header", spec.header);
        if (spec.bodySize != null) {
            long n = Values.toLong(spec.bodySize.eval(this), "bodySize");
            if (n < 0) throw ProtocolError.noMatch("bodySize %d is negative", n);
            int bodyEnd = pos + (int) n;
            if (bodyEnd > limit) {
                if (stream && limit == end) throw ProtocolError.NeedMore.INSTANCE;
                throw ProtocolError.noMatch("body of %d bytes does not fit", n);
            }
            if (!spec.bodyTransform.isEmpty()) {
                readTransformedBody(bodyEnd);
            } else {
                int outer = limit;
                limit = bodyEnd;
                readRegion("body", spec.body);
                if (pos != bodyEnd) throw ProtocolError.noMatch("body has %d bytes the fields do not use", bodyEnd - pos);
                limit = outer;
            }
        } else if (!spec.bodyTransform.isEmpty()) {
            // bodySize 가 없으면 받은 바이트에서 고정 크기 푸터를 뺀 나머지가 본문이다 (컴파일러가 푸터 크기를 보장한다)
            int bodyEnd = limit - spec.footerWidth;
            if (bodyEnd < pos) {
                if (stream && limit == end) throw ProtocolError.NeedMore.INSTANCE;
                throw ProtocolError.noMatch("message is too short for its footer");
            }
            readTransformedBody(bodyEnd);
        } else {
            readRegion("body", spec.body);
        }
        readRegion("footer", spec.footer);
        if (!stream && pos != end) throw ProtocolError.noMatch("%d bytes are left over", end - pos);
        if (spec.size != null && consumed() != spec.size) throw ProtocolError.noMatch("message is %d bytes, not %d", consumed(), spec.size);
        region = null;
        if (spec.match != null && !spec.match.test(this)) throw ProtocolError.noMatch("match is false");
        verifyChecksums();
        parsing = false;
        return consumed();
    }

    /**
     * 변환한 본문: 전송 바이트 [pos, bodyEnd) 의 변환을 되돌리고 그 안에서 본문 필드를 읽는다 (형식 6.8).
     * 영역 "body" 의 바이트 범위는 전송 바이트다 — 체크섬 covers 는 실제로 오간 바이트를 센다.
     */
    private void readTransformedBody(int bodyEnd) {
        int wireFrom = pos;
        byte[] inner = Codec.undoTransforms(spec.bodyTransform, Arrays.copyOfRange(buf, pos, bodyEnd));
        byte[] outerBuf = buf;
        int outerLimit = limit;
        buf = inner; pos = 0; limit = inner.length;
        try {
            readRegion("body", spec.body);
            if (pos != inner.length) throw ProtocolError.noMatch("body has %d bytes the fields do not use", inner.length - pos);
        } finally {
            buf = outerBuf; pos = bodyEnd; limit = outerLimit;
        }
        spans.put("body", new Span(outerBuf, wireFrom, bodyEnd));
    }

    private void readRegion(String name, List<FieldSpec> fields) {
        @SuppressWarnings("unchecked")
        Map<String, Object> into = (Map<String, Object>) tree.get(name);
        region = into;
        int from = pos;
        byte[] b = buf;
        for (FieldSpec f : fields) readField(f, into, name);
        spans.put(name, new Span(b, from, pos));
    }

    private void readField(FieldSpec f, Map<String, Object> into, String parentPath) {
        if (f.when != null && !f.when.test(this)) return;
        String path = f.name == null ? null : parentPath + "." + f.name;
        int from = pos;
        byte[] b = buf;
        Object value;
        if (f.isArray()) {
            long n = f.count != null ? f.count : Values.toLong(f.countExpr.eval(this), "count of " + Codec.label(f));
            if (n < 0 || n > 65535) throw ProtocolError.noMatch("count %d of field %s is out of range", n, Codec.label(f));
            List<Object> list = new ArrayList<>();
            // 원소를 읽는 동안 앞 원소를 이름으로 찾을 수 있게 목록을 먼저 단다
            if (f.name != null) into.put(f.name, list);
            for (long k = 0; k < n; k++) {
                indexes.push(k);
                try { list.add(readOne(f, path == null ? null : path + "[" + k + "]")); }
                finally { indexes.pop(); }
            }
            value = list;
        } else {
            value = readOne(f, path);
        }
        if (f.name != null) {
            into.put(f.name, value);
            spans.put(path, new Span(b, from, pos));
        }
    }

    private void need(int n) {
        if (n < 0) throw ProtocolError.noMatch("negative size");
        if (pos + n > limit) {
            if (stream && limit == end) throw ProtocolError.NeedMore.INSTANCE;
            throw ProtocolError.noMatch("message is too short");
        }
    }

    private int sizeOf(FieldSpec f) {
        if (f.size != null) return f.size;
        if (f.sizeExpr != null) return (int) Values.toLong(f.sizeExpr.eval(this), "size of " + Codec.label(f));
        return -1;
    }

    private Object readOne(FieldSpec f, String path) {
        int from = pos;
        Object value;
        switch (f.kind) {
            case STRUCT: value = readStruct(f, path); break;
            case BITS: value = readBits(f); break;
            case NUM: {
                int n = f.size;
                need(n);
                if (f.constValue != null && !Values.same(f.constValue, Codec.rawOf(f, buf, pos))) {
                    throw ProtocolError.noMatch("field %s is not %s", Codec.label(f), Values.text(f.constValue));
                }
                value = Codec.decodeNumber(f, buf, pos);
                pos += n;
                break;
            }
            case BCD: {
                int n = sizeOf(f);
                need(n);
                long raw = Codec.decodeBcdRaw(f, buf, pos, pos + n);
                if (f.constValue != null && !Values.same(f.constValue, raw)) throw ProtocolError.noMatch("field %s is not %s", Codec.label(f), f.constValue);
                value = Codec.physical(f, raw);
                pos += n;
                break;
            }
            case TEXT: case TEXTNUM: case HEXNUM: {
                int n = textWidth(f);
                FieldSpec g = f.size == null && f.sizeExpr != null ? f.withSize(n) : f;
                if (f.kind == Kind.TEXT) {
                    String t = Codec.decodeText(g, buf, pos, pos + n);
                    if (f.constValue != null && !f.constValue.equals(t)) throw ProtocolError.noMatch("field %s is not \"%s\"", Codec.label(f), f.constValue);
                    value = t;
                } else if (f.kind == Kind.TEXTNUM) {
                    value = Codec.decodeTextNum(g, buf, pos, pos + n);
                    if (f.constValue != null && !Values.same(f.constValue, value)) throw ProtocolError.noMatch("field %s is not %s", Codec.label(f), f.constValue);
                } else {
                    long raw = Codec.decodeHexNumRaw(g, buf, pos, pos + n);
                    if (f.constValue != null && !Values.same(f.constValue, raw)) throw ProtocolError.noMatch("field %s is not %s", Codec.label(f), f.constValue);
                    value = Codec.physical(f, raw);
                }
                pos += n;
                break;
            }
            case BYTES: {
                int n = sizeOf(f);
                if (n < 0) n = limit - pos;
                need(n);
                byte[] v = Arrays.copyOfRange(buf, pos, pos + n);
                if (f.constValue instanceof byte[] && !Arrays.equals((byte[]) f.constValue, v)) throw ProtocolError.noMatch("field %s does not hold the fixed bytes", Codec.label(f));
                value = v;
                pos += n;
                break;
            }
            case PAD: {
                int n = sizeOf(f);
                need(n);
                pos += n;
                value = null;
                break;
            }
            default:
                throw ProtocolError.runtime("field %s cannot be read", Codec.label(f));
        }
        if (f.expect != null) {
            Object saved = thisValue;
            thisValue = value;
            try {
                if (!f.expect.test(this)) throw ProtocolError.noMatch("expect of field %s is false", Codec.label(f));
            } finally {
                thisValue = saved;
            }
        }
        if (f.checksum != null) checksumFields.add(new Object[]{f, value, new Span(buf, from, pos)});
        return value;
    }

    /** 글자 필드의 바이트 수: 크기 → 끝 글자 → 둘러싼 크기의 남은 부분 */
    private int textWidth(FieldSpec f) {
        int n = sizeOf(f);
        if (n >= 0) {
            need(n);
            return n;
        }
        if (f.terminator != null) {
            byte[] t = f.terminator;
            for (int i = pos; i + t.length <= limit; i++) {
                boolean hit = true;
                for (int k = 0; k < t.length; k++) if (buf[i + k] != t[k]) { hit = false; break; }
                if (hit) return i + t.length - pos;
            }
            if (stream && limit == end) throw ProtocolError.NeedMore.INSTANCE;
            throw ProtocolError.noMatch("field %s has no end character", Codec.label(f));
        }
        return limit - pos;
    }

    private Map<String, Object> readStruct(FieldSpec f, String path) {
        Map<String, Object> map = new LinkedHashMap<>();
        scopes.push(map);
        try {
            int n = sizeOf(f);
            if (!f.transforms.isEmpty()) {
                if (n < 0) n = limit - pos;
                need(n);
                byte[] inner = Codec.undoTransforms(f.transforms, Arrays.copyOfRange(buf, pos, pos + n));
                byte[] outerBuf = buf;
                int outerPos = pos + n, outerLimit = limit;
                buf = inner; pos = 0; limit = inner.length;
                try {
                    for (FieldSpec c : f.fields) readField(c, map, path == null ? "_" : path);
                    if (pos != inner.length) throw ProtocolError.noMatch("structure %s has %d bytes left over", Codec.label(f), inner.length - pos);
                } finally {
                    buf = outerBuf; pos = outerPos; limit = outerLimit;
                }
            } else if (n >= 0) {
                need(n);
                int structEnd = pos + n, outerLimit = limit;
                limit = structEnd;
                try {
                    for (FieldSpec c : f.fields) readField(c, map, path == null ? "_" : path);
                    if (pos != structEnd) throw ProtocolError.noMatch("structure %s has %d bytes left over", Codec.label(f), structEnd - pos);
                } finally {
                    limit = outerLimit;
                }
            } else {
                for (FieldSpec c : f.fields) readField(c, map, path == null ? "_" : path);
            }
        } finally {
            scopes.pop();
        }
        return map;
    }

    private Map<String, Object> readBits(FieldSpec f) {
        need(f.size);
        List<Long> vals = Codec.decodeBits(f, buf, pos);
        Map<String, Object> map = new LinkedHashMap<>();
        for (int k = 0; k < f.fields.size(); k++) {
            FieldSpec sub = f.fields.get(k);
            long v = vals.get(k);
            if (sub.constValue != null && !Values.same(sub.constValue, v)) throw ProtocolError.noMatch("bit field %s is not %s", Codec.label(sub), sub.constValue);
            if (sub.name != null) map.put(sub.name, v);
        }
        pos += f.size;
        return map;
    }

    /** 체크섬 자리마다 범위를 계산해 받은 값과 비교. drop 은 틀리면 이 메시지가 아니다 */
    private void verifyChecksums() {
        for (Object[] c : checksumFields) {
            FieldSpec f = (FieldSpec) c[0];
            ChecksumSpec cs = engine.definition().checksums.get(f.checksum);
            Span from = spanOf(cs.from), to = spanOf(cs.to);
            if (from == null || to == null || from.buf != to.buf) {
                throw ProtocolError.runtime("checksum %s covers %s..%s, which this message does not have together", cs.name, cs.from, cs.to);
            }
            long expected = cs.algorithm.compute(from.buf, from.from, to.to);
            long got = Values.toLong(c[1], "checksum " + cs.name);
            boolean ok = expected == got;
            if (!ok && !cs.flagOnly) throw ProtocolError.noMatch("checksum %s is wrong (got %X, expected %X)", cs.name, got, expected);
            checksumOk.put(cs.name, ok);
        }
    }

    /** "header" / "body" / "header.address" — 영역 또는 필드의 바이트 범위 */
    Span spanOf(String path) {
        Span s = spans.get(path);
        if (s != null) return s;
        for (Map.Entry<String, Span> e : spans.entrySet()) if (e.getKey().endsWith("." + path)) return e.getValue();
        return null;
    }

    // ── 식의 이름 풀기 ───────────────────────────────────────────────

    @Override
    public Object path(Object[] seg) {
        String root = (String) seg[0];
        Object v;
        switch (root) {
            case "header": case "body": case "footer": v = tree.get(root); break;
            case "this":
                if (thisValue == null && parsing) throw ProtocolError.runtime("this is only for expect");
                v = thisValue;
                break;
            case "i":
                if (indexes.isEmpty()) throw ProtocolError.runtime("i is only inside an array field");
                v = indexes.peek();
                break;
            case "req": case "args":
                throw ProtocolError.runtime("%s is only for messages the device sends", root);
            default:
                v = find(root);
        }
        for (int k = 1; k < seg.length; k++) v = step(v, seg[k], seg);
        return v;
    }

    private Object find(String name) {
        for (Map<String, Object> m : scopes) if (m.containsKey(name)) return m.get(name);
        if (region != null && region.containsKey(name)) return region.get(name);
        for (String r : new String[]{"header", "body", "footer"}) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) tree.get(r);
            if (m.containsKey(name)) return m.get(name);
        }
        throw ProtocolError.runtime("%s is not a field of message %s%s", name, spec.name, parsing ? " (or not read yet)" : "");
    }

    static Object step(Object v, Object seg, Object[] all) {
        if (seg instanceof Long) {
            if (!(v instanceof List)) throw ProtocolError.runtime("[%d] needs an array", seg);
            List<?> l = (List<?>) v;
            long i = (Long) seg;
            if (i < 0 || i >= l.size()) throw ProtocolError.runtime("index %d is outside the array of %d", i, l.size());
            return l.get((int) i);
        }
        if (!(v instanceof Map)) throw ProtocolError.runtime("%s is not inside a structure", seg);
        Map<?, ?> m = (Map<?, ?>) v;
        if (!m.containsKey(seg)) throw ProtocolError.runtime("%s is not a field (or not present)", seg);
        return m.get(seg);
    }

    @Override
    public Object dollar(String name) { return engine.dollar(name); }

    @Override
    public long sizeof(Object[] seg) {
        StringBuilder key = new StringBuilder();
        for (Object s : seg) {
            if (s instanceof Long) key.append('[').append(s).append(']');
            else key.append(key.length() == 0 ? "" : ".").append(s);
        }
        Span s = spanOf(key.toString());
        if (s == null) throw ProtocolError.runtime("sizeof(%s): no such field was read", key);
        return s.to - s.from;
    }

    @Override
    public boolean has(Object[] seg) {
        try {
            path(seg);
            return true;
        } catch (ProtocolError.Runtime e) {
            return false;
        }
    }

    @Override
    public Object call(String fn, List<Object> args) {
        if (fn.equals("valid")) {
            String name = Values.text(args.get(0));
            if (!engine.definition().checksums.containsKey(name)) throw ProtocolError.runtime("valid('%s'): no such checksum", name);
            Boolean ok = checksumOk.get(name);
            if (ok == null) throw ProtocolError.runtime("valid('%s'): this message has no %s field", name, name);
            return ok;
        }
        return engine.targetFunction(fn, args);
    }
}
