package com.onionsoftware.mk119.commtestsuite.protocol;

import com.onionsoftware.mk119.commtestsuite.protocol.Definition.ChecksumSpec;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.FieldSpec;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.Kind;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.MessageSpec;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * tx 메시지 하나를 바이트로 만든다 (docs/PROTOCOL_ENGINE_DESIGN.md 7절).
 * <p>
 * 값은 필요할 때 계산한다. 길이 필드(`sizeof(body)`)가 뒤 영역을 먼저 인코딩하게 만들고,
 * 그 영역의 값이 다시 다른 필드를 부르는 식의 의존이 순서와 상관없이 풀린다. 되돌아오는 의존은 오류다.
 * 값의 출처는 const → 메시지 set → 필드 value → 필드 target (형식 5.2).
 */
final class TxWriter implements Expr.Scope {
    private final ProtocolEngine engine;
    private final MessageSpec spec;
    private final RxReader req;
    private final Map<String, Object> args;
    private final Map<String, Node> regions = new LinkedHashMap<>();
    /** 식을 계산하는 자리. 이름은 이 노드의 바깥쪽부터 찾는다 */
    private final Deque<Node> context = new ArrayDeque<>();

    TxWriter(ProtocolEngine engine, MessageSpec spec, RxReader req, Map<String, Object> args) {
        this.engine = engine;
        this.spec = spec;
        this.req = req;
        this.args = args;
        for (String r : new String[]{"header", "body", "footer"}) regions.put(r, Node.region(r, spec.region(r)));
    }

    /** 노드: 필드 하나(배열이면 배열 전체, 그 원소는 자식), 영역도 노드다 */
    private static final class Node {
        FieldSpec f;
        String path;
        Node parent;
        boolean regionNode, element;
        long index;
        List<FieldSpec> regionFields;
        List<Node> children;
        Boolean present;
        Object value;
        boolean raw;
        int valueState;   // 0 아직, 1 계산 중, 2 끝
        byte[] encoded;
        boolean encoding;

        static Node region(String name, List<FieldSpec> fields) {
            Node n = new Node();
            n.regionNode = true;
            n.path = name;
            n.regionFields = fields;
            return n;
        }

        String label() { return path == null ? "(unnamed)" : path; }
    }

    // ── 만들기 ───────────────────────────────────────────────────────

    /** 메시지 바이트. 체크섬까지 채운다 */
    byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<Node, int[]> spans = new LinkedHashMap<>();
        Map<String, int[]> named = new HashMap<>();
        List<Node> sums = new ArrayList<>();
        for (Node r : regions.values()) emit(r, out, spans, named, sums);
        byte[] b = out.toByteArray();
        fillChecksums(b, spans, named, sums);
        return b;
    }

    /** 노드를 이어 붙이며 위치를 적는다. 변환이 있는 구조체는 덩어리 하나다 (안쪽 체크섬은 encode 가 채운다) */
    private void emit(Node n, ByteArrayOutputStream out, Map<Node, int[]> spans, Map<String, int[]> named, List<Node> sums) {
        if (!present(n)) return;
        int from = out.size();
        boolean opaque = !n.regionNode && n.f.kind == Kind.STRUCT && !n.f.transforms.isEmpty() || transformedBody(n);
        if (!opaque && hasChildren(n)) {
            for (Node c : children(n)) emit(c, out, spans, named, sums);
        } else {
            byte[] b = encode(n);
            out.write(b, 0, b.length);
        }
        int[] span = {from, out.size()};
        spans.put(n, span);
        if (n.path != null) named.put(n.path, span);
        if (!n.regionNode && n.f.checksum != null && !n.element) sums.add(n);
    }

    /** 본문 변환이 있는 메시지의 본문 영역 — 변환한 구조체처럼 덩어리 하나로 인코딩한다 (형식 6.8) */
    private boolean transformedBody(Node n) {
        return n.regionNode && "body".equals(n.path) && !spec.bodyTransform.isEmpty();
    }

    private boolean hasChildren(Node n) {
        return n.regionNode || n.f.isArray() && !n.element || n.f.kind == Kind.STRUCT;
    }

    /** 체크섬: 다른 체크섬 자리를 덮는 것은 그 자리가 채워진 뒤에 (안쪽부터) */
    private void fillChecksums(byte[] b, Map<Node, int[]> spans, Map<String, int[]> named, List<Node> sums) {
        List<Node> pending = new ArrayList<>(sums);
        while (!pending.isEmpty()) {
            Node ready = null;
            for (Node c : pending) {
                int[] range = coverRange(c, named);
                boolean blocked = false;
                for (Node o : pending) {
                    if (o == c) continue;
                    int[] s = spans.get(o);
                    if (s[0] >= range[0] && s[1] <= range[1]) { blocked = true; break; }
                }
                if (!blocked) { ready = c; break; }
            }
            if (ready == null) throw ProtocolError.runtime("checksums in message %s cover each other", spec.name);
            ChecksumSpec cs = engine.definition().checksums.get(ready.f.checksum);
            int[] range = coverRange(ready, named);
            long v = cs.algorithm.compute(b, range[0], range[1]);
            byte[] enc = Codec.encodeLeaf(ready.f, v, true);
            int[] at = spans.get(ready);
            if (enc.length != at[1] - at[0]) throw ProtocolError.runtime("checksum %s does not fit field %s", cs.name, ready.label());
            System.arraycopy(enc, 0, b, at[0], enc.length);
            pending.remove(ready);
        }
    }

    private int[] coverRange(Node sum, Map<String, int[]> named) {
        ChecksumSpec cs = engine.definition().checksums.get(sum.f.checksum);
        int[] from = find(named, cs.from), to = find(named, cs.to);
        if (from == null || to == null) throw ProtocolError.runtime("checksum %s covers %s..%s, which message %s does not have", cs.name, cs.from, cs.to, spec.name);
        return new int[]{from[0], to[1]};
    }

    private static int[] find(Map<String, int[]> named, String path) {
        int[] s = named.get(path);
        if (s != null) return s;
        for (Map.Entry<String, int[]> e : named.entrySet()) if (e.getKey().endsWith("." + path)) return e.getValue();
        return null;
    }

    // ── 구조 ─────────────────────────────────────────────────────────

    private List<Node> children(Node n) {
        if (n.children != null) return n.children;
        List<Node> out = new ArrayList<>();
        if (n.regionNode) {
            for (FieldSpec f : n.regionFields) out.add(child(n, f, n.path));
        } else if (n.f.isArray() && !n.element) {
            long count = n.f.count != null ? n.f.count : Values.toLong(evalAt(n.parent, n.f.countExpr), "count of " + n.label());
            if (count < 0 || count > 65535) throw ProtocolError.runtime("count %d of field %s is out of range", count, n.label());
            for (long k = 0; k < count; k++) {
                Node e = new Node();
                e.f = n.f;
                e.parent = n;
                e.element = true;
                e.index = k;
                e.path = n.path == null ? null : n.path + "[" + k + "]";
                out.add(e);
            }
        } else if (n.f.kind == Kind.STRUCT || n.f.kind == Kind.BITS) {
            for (FieldSpec c : n.f.fields) out.add(child(n, c, n.path));
        }
        n.children = out;
        return out;
    }

    private static Node child(Node parent, FieldSpec f, String parentPath) {
        Node c = new Node();
        c.f = f;
        c.parent = parent;
        c.path = f.name == null || parentPath == null ? null : parentPath + "." + f.name;
        return c;
    }

    private boolean present(Node n) {
        if (n.regionNode || n.element) return true;
        if (n.present == null) n.present = n.f.when == null || testAt(n.parent, n.f.when);
        return n.present;
    }

    // ── 값 ───────────────────────────────────────────────────────────

    private Object value(Node n) {
        if (n.regionNode || n.f.kind == Kind.STRUCT && !(n.f.isArray() && !n.element) || n.f.kind == Kind.BITS && !(n.f.isArray() && !n.element)) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Node c : children(n)) if (c.f.name != null && present(c)) m.put(c.f.name, value(c));
            return m;
        }
        if (n.f.isArray() && !n.element) {
            List<Object> l = new ArrayList<>();
            for (Node c : children(n)) l.add(value(c));
            return l;
        }
        if (n.valueState == 2) return n.value;
        if (n.valueState == 1) throw ProtocolError.runtime("field %s depends on itself", n.label());
        n.valueState = 1;
        try {
            FieldSpec f = n.f;
            Expr set = n.path == null ? null : spec.set.get(n.path);
            if (f.constValue != null) { n.value = f.constValue; n.raw = true; }
            else if (set != null) n.value = evalAt(n, set);
            else if (f.value != null) n.value = evalAt(n, f.value);
            else if (f.target != null) n.value = engine.data(f.target);
            else if (f.checksum != null) { n.value = 0L; n.raw = true; }
            else if (f.kind == Kind.PAD) n.value = null;
            else throw ProtocolError.runtime("field %s has no value to send (const, value or target)", n.label());
            n.valueState = 2;
            return n.value;
        } catch (RuntimeException e) {
            n.valueState = 0;
            throw e;
        }
    }

    /** 노드의 전송 바이트 (체크섬 자리는 0) */
    private byte[] encode(Node n) {
        if (n.encoded != null) return n.encoded;
        if (n.encoding) throw ProtocolError.runtime("the size of %s depends on itself", n.label());
        n.encoding = true;
        try {
            byte[] b;
            if (!present(n)) {
                b = new byte[0];
            } else if (n.regionNode || n.f.kind == Kind.STRUCT || n.f.isArray() && !n.element) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean structTransform = !n.regionNode && n.f.kind == Kind.STRUCT && !n.f.transforms.isEmpty() && !(n.f.isArray() && !n.element);
                if (structTransform || transformedBody(n)) {
                    Map<Node, int[]> spans = new LinkedHashMap<>();
                    Map<String, int[]> named = new HashMap<>();
                    List<Node> sums = new ArrayList<>();
                    for (Node c : children(n)) emit(c, out, spans, named, sums);
                    byte[] inner = out.toByteArray();
                    fillChecksums(inner, spans, named, sums);
                    b = Codec.applyTransforms(structTransform ? n.f.transforms : spec.bodyTransform, inner);
                } else {
                    for (Node c : children(n)) { byte[] x = encode(c); out.write(x, 0, x.length); }
                    b = out.toByteArray();
                }
                if (!n.regionNode && n.f.size != null && n.f.kind == Kind.STRUCT && b.length != n.f.size) {
                    throw ProtocolError.runtime("structure %s is %d bytes, not %d", n.label(), b.length, n.f.size);
                }
            } else if (n.f.kind == Kind.BITS) {
                List<Long> vals = new ArrayList<>();
                for (Node c : children(n)) vals.add(Values.toLong(value(c), "bit field " + c.label()));
                b = Codec.encodeBits(n.f, vals);
            } else {
                FieldSpec f = n.f;
                if (f.size == null && f.sizeExpr != null && f.kind != Kind.BYTES) {
                    // 크기가 식인 글자·BCD·PAD — 보낼 때 그 크기로 (BYTES 는 값의 길이가 크기다)
                    f = f.withSize((int) Values.toLong(evalAt(n.parent, f.sizeExpr), "size of " + n.label()));
                }
                Object v = value(n);
                b = Codec.encodeLeaf(f, v, n.raw);
            }
            n.encoded = b;
            return b;
        } finally {
            n.encoding = false;
        }
    }

    // ── 식 ───────────────────────────────────────────────────────────

    private Object evalAt(Node at, Expr e) {
        context.push(at);
        try {
            return e.eval(this);
        } finally {
            context.pop();
        }
    }

    private boolean testAt(Node at, Expr e) {
        context.push(at);
        try {
            return e.test(this);
        } finally {
            context.pop();
        }
    }

    /** 경로를 노드로 (값이 아니라). 영역·구조체·배열을 따라간다 */
    private Node nodeAt(Object[] seg) {
        String root = (String) seg[0];
        Node n = regions.get(root);
        if (n == null) n = findNear(root);
        if (n == null) throw ProtocolError.runtime("%s is not a field of message %s", root, spec.name);
        for (int k = 1; k < seg.length; k++) {
            Object s = seg[k];
            if (s instanceof Long) {
                if (!(n.f != null && n.f.isArray() && !n.element)) throw ProtocolError.runtime("[%d] needs an array", s);
                List<Node> l = children(n);
                long i = (Long) s;
                if (i < 0 || i >= l.size()) throw ProtocolError.runtime("index %d is outside the array of %d", i, l.size());
                n = l.get((int) i);
            } else {
                Node next = null;
                for (Node c : children(n)) if (s.equals(c.f.name)) { next = c; break; }
                if (next == null) throw ProtocolError.runtime("%s is not a field of %s", s, n.label());
                n = next;
            }
        }
        return n;
    }

    /** 이름만 쓰면 가까운 범위부터: 같은 구조체 → 바깥 구조체 → 같은 영역 → 다른 영역 */
    private Node findNear(String name) {
        for (Node c = context.peek(); c != null; c = c.parent) {
            if (c.regionNode || c.f.kind == Kind.STRUCT || c.f.kind == Kind.BITS) {
                if (c.f != null && c.f.isArray() && !c.element) continue;
                for (Node x : children(c)) if (name.equals(x.f.name)) return x;
            }
        }
        for (Node r : regions.values()) for (Node x : children(r)) if (name.equals(x.f.name)) return x;
        return null;
    }

    private long index() {
        for (Node c = context.peek(); c != null; c = c.parent) if (c.element) return c.index;
        throw ProtocolError.runtime("i is only inside an array field");
    }

    @Override
    public Object path(Object[] seg) {
        String root = (String) seg[0];
        switch (root) {
            case "req": {
                if (req == null) throw ProtocolError.runtime("req is only in a reply");
                Object[] rest = Arrays.copyOfRange(seg, 1, seg.length);
                if (rest.length == 0) return req.tree;
                return req.path(rest);
            }
            case "args": {
                Object v = args;
                for (int k = 1; k < seg.length; k++) {
                    if (k == 1 && !args.containsKey(seg[1])) throw ProtocolError.runtime("args.%s was not passed by the respond rule", seg[1]);
                    v = RxReader.step(v, seg[k], seg);
                }
                return v;
            }
            case "i":
                if (seg.length == 1 && findNearQuiet("i") == null) return index();
                break;
            default:
                break;
        }
        return value(nodeAt(seg));
    }

    private Node findNearQuiet(String name) {
        try { return findNear(name); } catch (ProtocolError e) { return null; }
    }

    @Override
    public Object dollar(String name) { return engine.dollar(name); }

    @Override
    public long sizeof(Object[] seg) { return encode(nodeAt(seg)).length; }

    @Override
    public boolean has(Object[] seg) {
        try {
            return present(nodeAt(seg));
        } catch (ProtocolError.Runtime e) {
            return false;
        }
    }

    @Override
    public Object call(String fn, List<Object> a) {
        if (fn.equals("valid")) {
            if (req == null) throw ProtocolError.runtime("valid() is only about a received message");
            return req.call(fn, a);
        }
        return engine.targetFunction(fn, a);
    }
}
