package com.onionsoftware.mk119.commtestsuite.protocol;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition.TestCase;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * 문서의 tests 실행기 (docs/PROTOCOL_ENGINE_DESIGN.md 11절).
 * 사례는 장비 하나에서 순서대로 돈다 — 앞 사례의 값·쓰기가 뒤 사례에 남는다.
 * 저장소는 메모리뿐이라 실제 장비·잠금·이벤트에 닿지 않는다.
 */
public final class DefinitionTests {
    private DefinitionTests() {}

    public static final class Outcome {
        public String name;
        public int index;
        public boolean ok;
        public String expected, actual;
        public String message;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("index", index);
            m.put("ok", ok);
            m.put("expected", expected);
            m.put("actual", actual);
            m.put("message", message);
            return m;
        }
    }

    /** 메모리 저장소. 모든 쓰기를 받아들인다 */
    public static final class MemoryStore implements ProtocolEngine.Store {
        public final Map<String, Double> values = new LinkedHashMap<>();
        public Double get(String target) { return values.get(target); }
        public boolean write(String target, double value, DataType dataType) { values.put(target, value); return true; }
    }

    public static List<Outcome> run(Definition def) {
        List<Outcome> out = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        for (TestCase t : def.tests) {
            Outcome o = new Outcome();
            o.name = t.name;
            o.index = t.index;
            try {
                Map<String, Object> params = new HashMap<>(t.params);
                ProtocolEngine engine = new ProtocolEngine(() -> def, params, store);
                store.values.putAll(t.data);
                List<String> notes = new ArrayList<>();
                byte[] reply = feed(engine, t.send, notes);
                o.expected = t.expect == null ? "no reply" : Values.hex(t.expect);
                o.actual = reply.length == 0 ? "no reply" : Values.hex(reply);
                boolean bytesOk = t.expect == null ? reply.length == 0 : Arrays.equals(t.expect, reply);
                StringBuilder why = new StringBuilder();
                if (!bytesOk) why.append("the reply differs");
                for (Map.Entry<String, Double> w : t.expectWrites.entrySet()) {
                    Double v = store.values.get(w.getKey());
                    if (v == null || Math.abs(v - w.getValue()) > 1e-9 * Math.max(1, Math.abs(w.getValue()))) {
                        if (why.length() > 0) why.append("; ");
                        why.append(w.getKey()).append(" is ").append(v == null ? "not written" : Values.fmt(v))
                                .append(", expected ").append(Values.fmt(w.getValue()));
                    }
                }
                if (!notes.isEmpty() && why.length() > 0) why.append(" (").append(String.join("; ", notes)).append(")");
                o.ok = why.length() == 0;
                o.message = o.ok ? null : why.toString();
            } catch (RuntimeException e) {
                o.ok = false;
                o.message = e.getMessage();
            }
            out.add(o);
        }
        return out;
    }

    /**
     * 바이트를 스트림처럼 넣는다: 맞는 메시지마다 응답을 모으고, 안 맞으면 다시 맞춘다.
     * 끝에 모자란 바이트가 남으면 대기 만료처럼 한 번 더 보고 버린다.
     */
    static byte[] feed(ProtocolEngine engine, byte[] send, List<String> notes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        boolean flushing = false;
        while (pos < send.length) {
            ProtocolEngine.Match m = engine.match(send, pos, send.length, ProtocolEngine.Mode.STREAM, flushing);
            if (m.status == ProtocolEngine.Status.MATCH) {
                ProtocolEngine.Reply r = engine.respond(m.message);
                if (r.error != null) notes.add(m.message.name() + ": " + r.error);
                notes.addAll(r.notes);
                if (r.bytes != null) out.write(r.bytes, 0, r.bytes.length);
                pos += Math.max(1, m.consumed);
                flushing = false;
            } else if (m.status == ProtocolEngine.Status.NEED_MORE) {
                flushing = true;
            } else {
                if (!m.reasons.isEmpty()) notes.add("skipped bytes: " + m.reasons.get(0));
                pos += engine.resync(send, pos, send.length);
                flushing = false;
            }
        }
        return out.toByteArray();
    }
}
