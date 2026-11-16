package com.onionsoftware.mk119.commtestsuite.protocol;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.*;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr;

import java.util.*;
import java.util.function.Supplier;

/**
 * 프로토콜 정의 + 장비 파라미터 + 저장소를 묶은 실행기. 장비마다 하나 (docs/PROTOCOL_ENGINE_DESIGN.md 4절).
 * <pre>
 *   Match m = engine.match(buf, from, to, Mode.STREAM, false);   // MATCH / NEED_MORE / NONE
 *   Reply r = engine.respond(m.message);                          // 보낼 바이트(없으면 null), 지연, 기록
 * </pre>
 * 정의는 {@link Supplier} 로 받는다 — 정의를 고쳐 저장하면 다음 요청부터 새 판을 쓴다 (10.3).
 * 호출 자체는 상태가 없다. 연결마다 남는 것(TCP 버퍼)은 호출하는 쪽이 들고 있다.
 */
public final class ProtocolEngine {
    /** 저장소 창구. 장비에서는 ServerData(PROTOCOL_WRITE), 시험에서는 메모리 */
    public interface Store {
        /** 없으면 null */
        Double get(String target);
        /** @return 썼으면 true, 잠금에 막혔으면 false */
        boolean write(String target, double value, DataType dataType);
    }

    public enum Mode { STREAM, EXACT }
    public enum Status { MATCH, NEED_MORE, NONE }

    public static final class Match {
        public final Status status;
        public final int consumed;
        public final RxMessage message;
        /** NONE 일 때 메시지별로 안 맞은 까닭 (로그용) */
        public final List<String> reasons;

        Match(Status status, int consumed, RxMessage message, List<String> reasons) {
            this.status = status;
            this.consumed = consumed;
            this.message = message;
            this.reasons = reasons;
        }
    }

    /** 고른 rx 메시지와 그 값 */
    public static final class RxMessage {
        final RxReader reader;
        RxMessage(RxReader reader) { this.reader = reader; }
        public String name() { return reader.spec.name; }
        public byte[] bytes() { return reader.bytes(); }
        public Map<String, Object> values() { return reader.tree; }
        /** 이 메시지를 읽은 엔진으로 응답 규칙을 실행한다 */
        public Reply respond() { return reader.engine().respond(this); }
    }

    /** 어느 rx 에도 맞지 않은 데이터그램에 onUnmatched 로 보낼 바이트 — decoder 가 handler 로 넘긴다 */
    public static final class Unmatched {
        public final byte[] reply;
        public Unmatched(byte[] reply) { this.reply = reply; }
    }

    public static final class Reply {
        /** 보낼 바이트. 보내지 않으면 null */
        public byte[] bytes;
        public long delayMs;
        /** 실행한 규칙 이름 (없으면 "rule N"), 맞는 규칙이 없으면 null */
        public String rule;
        /** 쓰기 결과·건너뛴 까닭·오류 */
        public final List<String> notes = new ArrayList<>();
        /** 실행 중 오류로 응답하지 않았으면 그 까닭 */
        public String error;
    }

    private final Supplier<Definition> definition;
    private final Map<String, Object> params;
    private final Store store;

    /**
     * @param params 장비가 준 파라미터 값 (없는 이름은 정의의 기본값)
     */
    public ProtocolEngine(Supplier<Definition> definition, Map<String, Object> params, Store store) {
        this.definition = definition;
        this.params = params == null ? Collections.<String, Object>emptyMap() : params;
        this.store = store;
    }

    public Definition definition() { return definition.get(); }

    // ── 받기 ─────────────────────────────────────────────────────────

    /**
     * rx 메시지를 문서 순서대로 시도한다 (5.2).
     * STREAM 에서 앞 메시지가 모자라면(NEED_MORE) 뒤 메시지가 맞아도 기다린다. flushing 이면 모자람을 아님으로 본다.
     */
    public Match match(byte[] buf, int from, int to, Mode mode, boolean flushing) {
        Definition def = definition();
        boolean stream = mode == Mode.STREAM;
        List<String> reasons = new ArrayList<>();
        for (MessageSpec m : def.rx) {
            RxReader r = new RxReader(this, m, buf, from, to, stream && !flushing);
            try {
                int n = r.read();
                return new Match(Status.MATCH, n, new RxMessage(r), reasons);
            } catch (ProtocolError.NeedMore e) {
                if (stream && !flushing) return new Match(Status.NEED_MORE, 0, null, reasons);
                reasons.add(m.name + ": too short");
            } catch (ProtocolError e) {
                reasons.add(m.name + ": " + e.getMessage());
            }
        }
        return new Match(Status.NONE, 0, null, reasons);
    }

    /**
     * 스트림에서 맞는 메시지가 없을 때 버릴 바이트 수 (5.3).
     * syncOn 이 있으면 그 바이트(들)가 다음에 나오는 곳 앞까지, 없으면 한 바이트.
     */
    public int resync(byte[] buf, int from, int to) {
        byte[] sync = definition().syncOn;
        if (sync == null || sync.length == 0) return 1;
        for (int i = from + 1; i + sync.length <= to; i++) {
            boolean hit = true;
            for (int k = 0; k < sync.length; k++) if (buf[i + k] != sync[k]) { hit = false; break; }
            if (hit) return i - from;
        }
        // 끝에 sync 의 앞부분이 걸쳐 있을 수 있으니 그만큼은 남긴다
        return Math.max(1, to - from - (sync.length - 1));
    }

    // ── 응답 ─────────────────────────────────────────────────────────

    /** 응답 규칙 중 처음 맞는 하나를 실행한다 (6절). 실행 중 오류면 응답하지 않는다 */
    public Reply respond(RxMessage msg) {
        Reply out = new Reply();
        RxReader rx = msg.reader;
        try {
            Rule rule = null;
            for (Rule r : rx.spec.respond) {
                if (r.when == null || r.when.test(rx)) { rule = r; break; }
            }
            if (rule == null) {
                out.notes.add("no respond rule matched");
                return out;
            }
            out.rule = rule.label();
            for (WriteSpec w : rule.writes) write(w, rx, out);
            Map<String, Object> args = new LinkedHashMap<>();
            for (Map.Entry<String, com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr> a : rule.args.entrySet()) {
                args.put(a.getKey(), a.getValue().eval(rx));
            }
            out.delayMs = rule.delayMs;
            out.bytes = replyBytes(rule.reply, rx, args);
        } catch (ProtocolError e) {
            out.bytes = null;
            out.error = e.getMessage();
        }
        return out;
    }

    /** 어느 rx 에도 맞지 않은 프레임(EXACT)에 대한 onUnmatched 응답. 보내지 않으면 null */
    public byte[] unmatchedReply(byte[] frame) {
        String r = definition().onUnmatched;
        if (r == null || r.equals("none")) return null;
        if (r.equals("echo")) return frame;
        try {
            return replyBytes(r, null, Collections.<String, Object>emptyMap());
        } catch (ProtocolError e) {
            return null;
        }
    }

    private byte[] replyBytes(String reply, RxReader rx, Map<String, Object> args) {
        if (reply == null || reply.equals("none")) return null;
        if (reply.equals("echo")) return rx == null ? null : rx.bytes();
        MessageSpec tx = definition().tx.get(reply);
        if (tx == null) throw ProtocolError.runtime("reply %s is not a message the device sends", reply);
        return new TxWriter(this, tx, rx, args).build();
    }

    // ── 쓰기 ─────────────────────────────────────────────────────────

    private void write(WriteSpec w, RxReader rx, Reply out) {
        if (w.count == null) {
            writeOnce(w, rx, out);
            return;
        }
        long n = Values.toLong(w.count.eval(rx), "write count");
        if (n < 0 || n > 65535) throw ProtocolError.runtime("write count %d is out of range", n);
        // 하나씩 계산하고 바로 쓴다 — 뒤 번 식의 read() 가 앞 번에 쓴 값을 본다
        for (long k = 0; k < n; k++) writeOnce(w, new Expr.Bound(rx, "i", k), out);
    }

    private void writeOnce(WriteSpec w, Expr.Scope rx, Reply out) {
        Definition def = definition();
        if (w.target != null) {
            double v = Values.toDouble(w.value.eval(rx), "value written to " + w.target);
            writeTarget(def.targetInfo(w.target), w.target, v, out);
            return;
        }
        TargetGroup g = def.targets.get(w.map);
        long address = Values.toLong(w.address.eval(rx), "address");
        Object raw = w.bytes.eval(rx);
        if (!(raw instanceof byte[])) throw ProtocolError.runtime("write to %s needs bytes, not %s", w.map, Values.kind(raw));
        byte[] bytes = (byte[]) raw;
        if (bytes.length % g.slotSize != 0) {
            out.notes.add("write to " + w.map + " skipped: " + bytes.length + " bytes is not whole slots");
            return;
        }
        long count = bytes.length / g.slotSize;
        if (!fits(g, address, count)) {
            out.notes.add("write to " + w.map + " " + address + "+" + count + " skipped: the range does not fit the map");
            return;
        }
        int at = 0;
        for (long a = address; a < address + count; ) {
            SeqItem it = itemAt(g, a);
            if (it == null) { a++; at += g.slotSize; continue; }   // unmapped: zero
            int width = it.slots * g.slotSize;
            if (it.isBits()) {
                List<Long> vals = Codec.decodeBits(it.spec, bytes, at);
                for (int k = 0; k < it.spec.fields.size(); k++) {
                    String t = it.spec.fields.get(k).target;
                    if (t != null) writeTarget(def.targetInfo(t), t, vals.get(k), out);
                }
            } else if (it.target != null) {
                Object v = decodeItem(it, bytes, at);
                writeTarget(def.targetInfo(it.target), it.target, Values.toDouble(v, it.target), out);
            }
            a += it.slots;
            at += width;
        }
    }

    private void writeTarget(TargetInfo info, String target, double v, Reply out) {
        if (info == null || !info.writable) {
            out.notes.add("write to \"" + target + "\" skipped: not a writable (rw) target");
            return;
        }
        boolean ok = store.write(target, v, dataTypeOf(info));
        out.notes.add(ok ? "wrote " + target + " = " + Values.fmt(v) : "write to \"" + target + "\" blocked by a lock");
    }

    /** 잠금 범위를 셀 때 쓰는 data type: 1비트 → BINARY, 정수형(여러 비트 포함) → INTEGER, 그 밖 → FLOAT */
    static DataType dataTypeOf(TargetInfo info) {
        if (info != null && info.value != null) {
            return "binary".equals(info.value) ? DataType.BINARY : "integer".equals(info.value) ? DataType.INTEGER : DataType.FLOAT;
        }
        FieldSpec s = info == null ? null : info.spec;
        if (s == null) return DataType.FLOAT;
        if (s.kind == Kind.BIT) return s.bits > 1 ? DataType.INTEGER : DataType.BINARY;
        boolean whole = !s.scaled && (s.kind == Kind.NUM && !s.isReal() || s.kind == Kind.BCD || s.kind == Kind.HEXNUM
                || s.kind == Kind.TEXTNUM && s.decimals == 0);
        return whole ? DataType.INTEGER : DataType.FLOAT;
    }

    // ── 이름과 타겟 함수 ─────────────────────────────────────────────

    /** $이름 — consts 먼저, 그다음 장비 params, 없으면 정의의 기본값 */
    Object dollar(String name) {
        Definition def = definition();
        if (def.consts.containsKey(name)) return def.consts.get(name);
        Param p = def.params.get(name);
        if (p == null) throw ProtocolError.runtime("$%s is not a constant or a parameter", name);
        Object v = params.get(name);
        if (v == null) v = p.defaultValue;
        if (v == null) throw ProtocolError.runtime("parameter $%s has no value and no default", name);
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Double && (Double) v == Math.rint((Double) v) && !p.isReal()) return (long) (double) (Double) v;
        return v;
    }

    /** 저장소의 물리값. 없으면 0 */
    Object data(String target) {
        Double v = store.get(target);
        return v == null ? 0.0 : v;
    }

    Object targetFunction(String fn, List<Object> a) {
        switch (fn) {
            case "data": return data(Values.text(a.get(0)));
            case "read": {
                TargetGroup g = sequence(a.get(0));
                long addr = Values.toLong(a.get(1), "address"), count = Values.toLong(a.get(2), "count");
                return read(g, addr, count);
            }
            case "fits": return fits(sequence(a.get(0)), Values.toLong(a.get(1), "address"), Values.toLong(a.get(2), "count"));
            case "writable": {
                TargetGroup g = sequence(a.get(0));
                return writable(g, Values.toLong(a.get(1), "address"), Values.toLong(a.get(2), "count"));
            }
            case "slots": return (long) sequence(a.get(0)).totalSlots;
            default: throw ProtocolError.runtime("unknown function %s()", fn);
        }
    }

    private TargetGroup sequence(Object name) {
        TargetGroup g = definition().targets.get(Values.text(name));
        if (g == null || !"sequence".equals(g.kind)) throw ProtocolError.runtime("%s is not a sequence target group", name);
        return g;
    }

    /** 번지 a 에서 시작하는 항목. 항목 중간이면 예외, 빈 번지면 null */
    private static SeqItem itemAt(TargetGroup g, long a) {
        for (SeqItem it : g.items) {
            if (a == it.address) return it;
            if (a > it.address && a < it.address + it.slots) throw ProtocolError.runtime("address %d is inside %s", a, describe(it));
        }
        return null;
    }

    private static String describe(SeqItem it) {
        if (it.target != null) return "\"" + it.target + "\"";
        if (it.isBits()) return "a bit set at " + it.address;
        return it.skip ? "a skipped range" : "a fixed item";
    }

    static boolean fits(TargetGroup g, long address, long count) {
        if (count <= 0) return false;
        long end = address + count;
        for (long a = address; a < end; ) {
            SeqItem it;
            try { it = itemAt(g, a); } catch (ProtocolError e) { return false; }
            if (it == null) {
                if (!g.unmappedZero) return false;
                a++;
                continue;
            }
            if (a + it.slots > end) return false;
            a += it.slots;
        }
        return true;
    }

    private static boolean writable(TargetGroup g, long address, long count) {
        if (!fits(g, address, count)) return false;
        for (long a = address; a < address + count; ) {
            SeqItem it = itemAt(g, a);
            if (it == null) return false;
            if (!it.hasTargets() || !it.writable) return false;
            a += it.slots;
        }
        return true;
    }

    private byte[] read(TargetGroup g, long address, long count) {
        if (!fits(g, address, count)) throw ProtocolError.runtime("read('%s', %d, %d): the range does not fit the map", g.name, address, count);
        byte[] out = new byte[(int) (count * g.slotSize)];
        int at = 0;
        for (long a = address; a < address + count; ) {
            SeqItem it = itemAt(g, a);
            if (it == null) { a++; at += g.slotSize; continue; }
            int width = it.slots * g.slotSize;
            if (!it.skip) {
                byte[] b = it.isBits() ? Codec.encodeBits(it.spec, bitValues(it.spec))
                        : it.target != null
                        ? Codec.encodeLeaf(it.spec, data(it.target), false)
                        : Codec.encodeLeaf(it.spec, it.spec.constValue, true);
                if (b.length > width) throw ProtocolError.runtime("item %s is %d bytes, more than its %d slots", describe(it), b.length, it.slots);
                System.arraycopy(b, 0, out, at, b.length);
            }
            a += it.slots;
            at += width;
        }
        return out;
    }

    /** 비트 집합 항목의 비트 필드 값: 타겟은 저장소 값(반올림), 고정값은 그대로, 없으면 0 */
    private List<Long> bitValues(FieldSpec bits) {
        List<Long> out = new ArrayList<>();
        for (FieldSpec b : bits.fields) {
            if (b.target != null) out.add(Math.round(Values.toDouble(data(b.target), b.target)));
            else if (b.constValue != null) out.add(Values.toLong(b.constValue, "bit field const"));
            else out.add(0L);
        }
        return out;
    }

    private static Object decodeItem(SeqItem it, byte[] b, int at) {
        FieldSpec s = it.spec;
        int width = it.slots;
        switch (s.kind) {
            case NUM: return Codec.decodeNumber(s, b, at);
            case BCD: return Codec.physical(s, Codec.decodeBcdRaw(s, b, at, at + s.size));
            case TEXTNUM: return Codec.decodeTextNum(s, b, at, at + s.size);
            case HEXNUM: return Codec.physical(s, Codec.decodeHexNumRaw(s, b, at, at + s.size));
            default: throw ProtocolError.runtime("item \"%s\" (%s, %d slots) cannot be written from bytes", it.target, s.kind, width);
        }
    }
}
