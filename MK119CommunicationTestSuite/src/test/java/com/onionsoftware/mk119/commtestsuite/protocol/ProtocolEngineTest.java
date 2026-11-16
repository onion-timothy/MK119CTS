package com.onionsoftware.mk119.commtestsuite.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Parser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 엔진 단위 시험. 기준: docs/PROTOCOL_ENGINE_DESIGN.md 15절.
 * 예시 문서 셋(static/protocol-examples)의 tests 를 엔진으로 돌리는 것이 가장 넓은 시험이다.
 */
class ProtocolEngineTest {
    private static final String EXAMPLES = "src/main/resources/static/protocol-examples/";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String name) throws Exception {
        return new ObjectMapper().readValue(new File(EXAMPLES + name + ".json"), LinkedHashMap.class);
    }

    private static Definition compile(Map<String, Object> doc) {
        Compiler.Result r = Compiler.compile(doc);
        StringBuilder sb = new StringBuilder();
        for (Compiler.Problem p : r.problems) if (p.level.equals("error")) sb.append(p.at).append(' ').append(p.text).append('\n');
        assertTrue(r.ok(), "compile errors:\n" + sb);
        return r.definition;
    }

    private static void assertTestsPass(String example) throws Exception {
        Definition def = compile(load(example));
        List<DefinitionTests.Outcome> out = DefinitionTests.run(def);
        assertFalse(out.isEmpty());
        StringBuilder sb = new StringBuilder();
        for (DefinitionTests.Outcome o : out) {
            if (!o.ok) sb.append(o.name).append(": ").append(o.message).append("\n  expected ").append(o.expected).append("\n  actual   ").append(o.actual).append('\n');
        }
        assertEquals("", sb.toString(), example + " tests failed");
    }

    @Test void powerMeterExampleTestsPass() throws Exception { assertTestsPass("power-meter"); }
    @Test void thermoControllerExampleTestsPass() throws Exception { assertTestsPass("thermo-controller"); }
    @Test void asciiMeterExampleTestsPass() throws Exception { assertTestsPass("ascii-meter"); }
    @Test void xgtFenetExampleTestsPass() throws Exception { assertTestsPass("xgt-fenet"); }
    @Test void bacnetIpExampleTestsPass() throws Exception { assertTestsPass("bacnet-ip"); }

    // ── 체크섬 ───────────────────────────────────────────────────────

    @Test
    void crcCatalogCheckValues() {
        byte[] d = "123456789".getBytes(StandardCharsets.US_ASCII);
        long[][] expected = {
                {0xF4}, {0xBB3D}, {0x4B37}, {0x31C3}, {0x29B1}, {0x2189}, {0xCBF43926L}};
        for (int i = 0; i < Checksums.CATALOG.size(); i++) {
            String name = Checksums.CATALOG.get(i);
            assertEquals(expected[i][0], Checksums.catalog(name).compute(d, 0, d.length), name);
        }
        assertEquals((0x31 + 0x32 + 0x33 + 0x34 + 0x35 + 0x36 + 0x37 + 0x38 + 0x39) & 0xFF, Checksums.sum(8, null).compute(d, 0, d.length));
        assertEquals((-(0x31 + 0x32 + 0x33 + 0x34 + 0x35 + 0x36 + 0x37 + 0x38 + 0x39)) & 0xFF, Checksums.LRC8.compute(d, 0, d.length));
        assertEquals(0x31, Checksums.XOR8.compute(d, 0, d.length));
    }

    // ── 식 ───────────────────────────────────────────────────────────

    private static Object eval(String src) throws Parser.SyntaxError {
        Expr e = Parser.parse(src, null);
        return e.eval(new Expr.Scope() {
            public Object path(Object[] s) { if (s[0].equals("x")) return 10L; throw ProtocolError.runtime("no %s", s[0]); }
            public Object dollar(String n) { return 3L; }
            public long sizeof(Object[] s) { return 0; }
            public boolean has(Object[] s) { return false; }
            public Object call(String f, List<Object> a) { throw ProtocolError.runtime("no %s", f); }
        });
    }

    @Test
    void expressionPrecedenceAndKinds() throws Exception {
        assertEquals(7L, eval("1 + 2 * 3"));
        assertEquals(true, eval("1 + 2 == 3 && !false"));
        assertEquals(0x83L, eval("0x03 | 0x80"));
        assertEquals(2.5, eval("5 / 2"));
        assertEquals(2L, eval("5 // 2"));
        assertEquals(-3L, eval("-5 // 2"));
        assertEquals("hr12", eval("'hr' + text(x + 2)"));
        assertEquals(true, eval("x in [1, 10]"));
        assertEquals("1A", eval("hex(26, 2)"));
        assertEquals(13L, eval("x + $k"));
        assertEquals("b", eval("x > 5 ? 'b' : 'a'"));
        assertEquals(16L, eval("1 << 4"));
        assertEquals(true, eval("x <= 10"));
    }

    private static Object evalWithVars(String src) throws Parser.SyntaxError {
        // vars = [{area:'D', n:5}, {area:'M', n:7}]
        List<Object> vars = new ArrayList<>();
        Map<String, Object> a = new LinkedHashMap<>(); a.put("area", "D"); a.put("n", 5L); vars.add(a);
        Map<String, Object> b = new LinkedHashMap<>(); b.put("area", "M"); b.put("n", 7L); vars.add(b);
        Expr e = Parser.parse(src, null);
        return e.eval(new Expr.Scope() {
            public Object path(Object[] s) {
                if (s[0].equals("vars")) return s.length == 1 ? vars : null;
                if (s[0].equals("x")) return 10L;
                throw ProtocolError.runtime("no %s", s[0]);
            }
            public Object dollar(String n) { return "PMKD"; }
            public long sizeof(Object[] s) { return 0; }
            public boolean has(Object[] s) { return false; }
            public Object call(String f, List<Object> args) { throw ProtocolError.runtime("no %s", f); }
        });
    }

    @Test
    void textBytesAndListFunctions() throws Exception {
        // find: 글자 안의 위치, 없으면 -1
        assertEquals(3L, eval("find('PMKD', 'D')"));
        assertEquals(-1L, eval("find('PMKD', 'Q')"));
        // pack·unpack: 정수 ↔ 바이트 (기본 big)
        assertArrayEquals(new byte[]{0x34, 0x12}, (byte[]) eval("pack(0x1234, 2, 'little')"));
        assertArrayEquals(new byte[]{0x00, 0x12, 0x34}, (byte[]) eval("pack(0x1234, 3)"));
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF}, (byte[]) eval("pack(-1, 2)"));
        assertEquals(0x1234L, eval("unpack(x'3412', 'little')"));
        assertEquals(0x3412L, eval("unpack(x'3412')"));
        assertEquals(0xFFFFFFFFL, eval("unpack(x'FFFFFFFF')"));
        // substr 은 바이트도 자른다
        assertArrayEquals(new byte[]{0x02, 0x03}, (byte[]) eval("substr(x'01020304', 1, 2)"));
        assertArrayEquals(new byte[]{0x04}, (byte[]) eval("substr(x'01020304', 3)"));
        assertArrayEquals(new byte[]{0x01, 0x09, 0x04}, (byte[]) eval("substr(x'0102', 0, 1) + x'09' + substr(x'01020304', 3)"));
        // any·all: 원소마다 it
        assertEquals(true, evalWithVars("any(vars, it.area == 'M')"));
        assertEquals(false, evalWithVars("any(vars, it.n > x)"));
        assertEquals(true, evalWithVars("all(vars, find($a, it.area) >= 0 && it.n < x)"));
        assertEquals(false, evalWithVars("all(vars, it.n == 5)"));
        assertEquals(true, evalWithVars("all([], false)"));
        assertThrows(ProtocolError.Runtime.class, () -> eval("pack(1, 9)"));
        assertThrows(ProtocolError.Runtime.class, () -> eval("unpack(x'01', 'middle')"));
        assertThrows(ProtocolError.Runtime.class, () -> eval("any(x, true)"));
        assertThrows(ProtocolError.Runtime.class, () -> evalWithVars("any(vars, it.missing == 1)"));
        assertThrows(Parser.SyntaxError.class, () -> Parser.parse("any(x)", null));
    }

    @Test
    void expressionErrors() {
        assertThrows(Parser.SyntaxError.class, () -> Parser.parse("1 +", null));
        assertThrows(Parser.SyntaxError.class, () -> Parser.parse("nope(1)", null));
        assertThrows(Parser.SyntaxError.class, () -> Parser.parse("min(1)", null));
        assertThrows(Parser.SyntaxError.class, () -> Parser.parse("$missing", new HashSet<>(Collections.singletonList("unitId"))));
        assertThrows(ProtocolError.Runtime.class, () -> eval("1 // 0"));
        assertThrows(ProtocolError.Runtime.class, () -> eval("1 && true"));
    }

    // ── 컴파일 오류 위치 ─────────────────────────────────────────────

    @Test
    void compileErrorsCarryTheEditorPath() throws Exception {
        Map<String, Object> doc = load("power-meter");
        @SuppressWarnings("unchecked")
        Map<String, Object> rx = (Map<String, Object>) ((List<Object>) doc.get("messages")).get(0);
        rx.put("match", "header.function ==");
        Compiler.Result r = Compiler.compile(doc);
        assertFalse(r.ok());
        assertEquals(Arrays.asList("messages", 0, "match"), r.problems.get(0).at);
    }

    // ── 스트림 매칭 ──────────────────────────────────────────────────

    private static byte[] hex(String s) { return Values.parseHex(s); }

    @Test
    void streamWaitsForSplitFramesAndHandlesJoinedOnes() throws Exception {
        Definition def = compile(load("power-meter"));
        DefinitionTests.MemoryStore store = new DefinitionTests.MemoryStore();
        ProtocolEngine e = new ProtocolEngine(() -> def, Collections.emptyMap(), store);
        byte[] req = hex("01 03 00 00 00 01 84 0A");
        // 앞 5바이트만: 기다린다
        assertEquals(ProtocolEngine.Status.NEED_MORE, e.match(req, 0, 5, ProtocolEngine.Mode.STREAM, false).status);
        // 두 요청이 붙어 와도 첫 요청만큼만 먹는다
        byte[] two = new byte[16];
        System.arraycopy(req, 0, two, 0, 8);
        System.arraycopy(req, 0, two, 8, 8);
        ProtocolEngine.Match m = e.match(two, 0, two.length, ProtocolEngine.Mode.STREAM, false);
        assertEquals(ProtocolEngine.Status.MATCH, m.status);
        assertEquals(8, m.consumed);
        // EXACT 에서는 남는 바이트가 있으면 아니다
        assertEquals(ProtocolEngine.Status.NONE, e.match(two, 0, two.length, ProtocolEngine.Mode.EXACT, false).status);
    }

    @Test
    void writesGoThroughTheStoreAndRespectAccess() throws Exception {
        Definition def = compile(load("thermo-controller"));
        DefinitionTests.MemoryStore store = new DefinitionTests.MemoryStore();
        ProtocolEngine e = new ProtocolEngine(() -> def, Collections.emptyMap(), store);
        byte[] req = hex("02 01 20 02 01 09 2B 03");
        ProtocolEngine.Match m = e.match(req, 0, req.length, ProtocolEngine.Mode.EXACT, false);
        ProtocolEngine.Reply r = e.respond(m.message);
        assertEquals("writeOk", r.rule);
        assertEquals(26.5, store.values.get("설정온도"), 1e-9);
        assertArrayEquals(hex("02 01 06 00 07 03"), r.bytes);
    }

    // ── 비트 집합 항목 (순차 타겟의 BITS) ────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String s) throws Exception {
        return new ObjectMapper().readValue(s, LinkedHashMap.class);
    }

    /** CRC-16/MODBUS 를 붙인다 (UI16BA — 낮은 바이트 먼저) */
    private static byte[] withCrc(String body) {
        byte[] b = hex(body);
        long crc = Checksums.catalog("CRC-16/MODBUS").compute(b, 0, b.length);
        byte[] out = Arrays.copyOf(b, b.length + 2);
        out[b.length] = (byte) crc;
        out[b.length + 1] = (byte) (crc >>> 8);
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void bitSetItemsDeclareReadAndWriteEachBit() throws Exception {
        Map<String, Object> doc = load("power-meter");
        Map<String, Object> holding = (Map<String, Object>) ((Map<String, Object>) doc.get("targets")).get("holding");
        // 번지 11: 운전(1비트) · 경보(1비트) · 모드(3비트) · 나머지 11비트는 0
        ((List<Object>) holding.get("items")).add(json("{\"type\":\"BITS\",\"size\":2,\"access\":\"rw\",\"fields\":["
                + "{\"bits\":1,\"target\":\"운전\"},{\"bits\":1,\"target\":\"경보\"},{\"bits\":3,\"target\":\"모드\"},{\"bits\":11,\"const\":0}]}"));
        // FC16 비슷한 쓰기: 시작 번지 + 2바이트를 holding 에 쓴다. otherFunction 보다 먼저 맞춰 본다
        ((List<Object>) doc.get("messages")).add(0, json("{\"name\":\"writeOne\",\"direction\":\"rx\",\"frame\":\"rtu\",\"match\":\"header.function == 6\","
                + "\"body\":[{\"name\":\"start\",\"type\":\"UINT\", \"size\":2},{\"name\":\"values\",\"type\":\"BYTES\",\"size\":2}],"
                + "\"respond\":[{\"reply\":\"none\",\"write\":[{\"map\":\"holding\",\"address\":\"body.start\",\"bytes\":\"body.values\"}]}]}"));
        Definition def = compile(doc);

        // 비트 필드마다 타겟이 하나씩 선언된다. 1비트는 BINARY·INTEGER, 여러 비트는 INTEGER
        assertTrue(def.targetInfo("운전").writable);
        assertEquals(Arrays.asList(com.onionsoftware.mk119.commtestsuite.enums.DataType.BINARY, com.onionsoftware.mk119.commtestsuite.enums.DataType.INTEGER),
                def.targetInfo("운전").dataTypes());
        assertEquals(Collections.singletonList(com.onionsoftware.mk119.commtestsuite.enums.DataType.INTEGER), def.targetInfo("모드").dataTypes());

        DefinitionTests.MemoryStore store = new DefinitionTests.MemoryStore();
        ProtocolEngine e = new ProtocolEngine(() -> def, Collections.emptyMap(), store);
        store.values.put("운전", 1.0);
        store.values.put("경보", 0.0);
        store.values.put("모드", 5.0);
        // lsb 부터: 운전 1 · 경보 0 · 모드 101 → 0b10101 = 0x0015
        assertArrayEquals(hex("00 15"), (byte[]) e.targetFunction("read", Arrays.asList("holding", 11L, 1L)));
        assertEquals(Boolean.TRUE, e.targetFunction("writable", Arrays.asList("holding", 11L, 1L)));

        // 0x000E: 운전 0 · 경보 1 · 모드 011
        byte[] req = withCrc("01 06 00 0B 00 0E");
        ProtocolEngine.Match m = e.match(req, 0, req.length, ProtocolEngine.Mode.EXACT, false);
        assertNotNull(m, "the write message should match");
        e.respond(m.message);
        assertEquals(0.0, store.values.get("운전"), 1e-9);
        assertEquals(1.0, store.values.get("경보"), 1e-9);
        assertEquals(3.0, store.values.get("모드"), 1e-9);
    }

    @Test
    void bitSetItemRejectsItsOwnTarget() throws Exception {
        Map<String, Object> doc = json("{\"format\":\"cts-protocol\",\"version\":1,\"name\":\"x\",\"targets\":{\"m\":{\"kind\":\"sequence\",\"slotSize\":1,\"items\":["
                + "{\"target\":\"t\",\"type\":\"BITS\",\"size\":1,\"fields\":[{\"bits\":8,\"target\":\"u\"}]}]}}}");
        Compiler.Result r = Compiler.compile(doc);
        assertTrue(r.problems.stream().anyMatch(p -> p.level.equals("error") && p.at.equals(Arrays.asList("targets", "m", "items", 0, "target"))),
                "a BITS item with its own target is an error");
    }

    // ── 값은 선언에, 표현은 필드에 (형식 8.3) ───────────────────────────────

    /** 이름 타겟 하나 + 그것을 싣는 tx 메시지 하나짜리 문서 */
    private static Map<String, Object> namedDoc(String item, String field) throws Exception {
        return json("{\"format\":\"cts-protocol\",\"version\":1,\"name\":\"x\","
                + "\"targets\":{\"p\":{\"kind\":\"named\",\"items\":[" + item + "]}},"
                + "\"messages\":[{\"name\":\"q\",\"direction\":\"rx\",\"body\":[{\"name\":\"c\",\"type\":\"UINT\", \"size\":1}],\"respond\":[{\"reply\":\"r\"}]},"
                + "{\"name\":\"r\",\"direction\":\"tx\",\"body\":[" + field + "]}]}");
    }
    private static List<String> errors(Map<String, Object> doc) {
        List<String> out = new ArrayList<>();
        for (Compiler.Problem p : Compiler.compile(doc).problems) if (p.level.equals("error")) out.add(p.at + " " + p.text);
        return out;
    }

    @Test
    void namedTargetsDeclareTheValueAndFieldsCarryTheRepresentation() throws Exception {
        // 표현 없는 선언 + 표현 있는 필드(이름 없이): 통과하고, 값의 종류는 선언한 것
        Map<String, Object> ok = namedDoc("{\"target\":\"t\",\"value\":\"float\",\"access\":\"rw\"}",
                "{\"type\":\"TEXTNUM\",\"size\":3,\"decimals\":1,\"point\":false,\"target\":\"t\"}");
        assertEquals(Collections.emptyList(), errors(ok));
        Definition def = compile(ok);
        assertEquals("float", def.targetInfo("t").valueKind());
        assertEquals(com.onionsoftware.mk119.commtestsuite.enums.DataType.FLOAT, ProtocolEngine.dataTypeOf(def.targetInfo("t")));

        // 값을 비우면 실은 필드의 표현에서 추론한다
        Definition inferred = compile(namedDoc("{\"target\":\"t\"}", "{\"type\":\"UINT\", \"size\":1,\"target\":\"t\"}"));
        assertEquals("integer", inferred.targetInfo("t").valueKind());

        // 선언에 표현을 적으면 오류 — 표현은 필드에
        assertTrue(errors(namedDoc("{\"target\":\"t\",\"type\":\"UINT\", \"size\":1}", "{\"type\":\"UINT\", \"size\":1,\"target\":\"t\"}"))
                .stream().anyMatch(e -> e.contains("[targets, p, items, 0, type]")));
        // 이름 타겟을 실으면서 표현을 비우면 오류
        assertTrue(errors(namedDoc("{\"target\":\"t\"}", "{\"target\":\"t\"}"))
                .stream().anyMatch(e -> e.contains("needs a type")));
        // 정수·0/1 로 선언한 타겟에 소수 표현을 실으면 오류
        assertTrue(errors(namedDoc("{\"target\":\"t\",\"value\":\"integer\"}", "{\"type\":\"UINT\", \"size\":2,\"scale\":0.1,\"target\":\"t\"}"))
                .stream().anyMatch(e -> e.contains("fractional")));
        // 수가 아닌 표현(TEXT)에 타겟을 실으면 오류
        assertTrue(errors(namedDoc("{\"target\":\"t\"}", "{\"type\":\"TEXT\",\"size\":2,\"target\":\"t\"}"))
                .stream().anyMatch(e -> e.contains("holds a number")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldCarryingASequenceTargetNeedsAType() throws Exception {
        // 순차 타겟도 선언의 표현을 가져오지 않는다 — 필드가 Type 을 적는다 (From target 삭제)
        Map<String, Object> doc = load("power-meter");
        Map<String, Object> holding = (Map<String, Object>) ((Map<String, Object>) doc.get("targets")).get("holding");
        ((List<Object>) holding.get("items")).add(json("{\"type\":\"BITS\",\"size\":2,\"fields\":[{\"bits\":1,\"target\":\"운전\"},{\"bits\":15,\"const\":0}]}"));
        int ri = messageIndex(doc, "readHoldingReply");
        Map<String, Object> reply = (Map<String, Object>) ((List<Object>) doc.get("messages")).get(ri);
        ((List<Object>) reply.get("body")).add(json("{\"name\":\"run\",\"target\":\"운전\"}"));
        assertTrue(errors(doc).stream().anyMatch(e -> e.contains("[messages, " + ri + ", body, ") && e.contains("needs a type")), errors(doc).toString());

        // 순차 타겟 항목(UINT)을 타입 없이 실어도 오류
        Map<String, Object> plain = load("power-meter");
        String first = (String) ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) ((Map<String, Object>) plain.get("targets")).get("holding")).get("items")).get(0)).get("target");
        ((List<Object>) ((Map<String, Object>) ((List<Object>) plain.get("messages")).get(messageIndex(plain, "readHoldingReply"))).get("body")).add(json("{\"name\":\"x\",\"target\":\"" + first + "\"}"));
        assertTrue(errors(plain).stream().anyMatch(e -> e.contains("needs a type")), errors(plain).toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservedNamesAreRejected() throws Exception {
        // 필드·비트 필드 이름의 예약어 (식에서 이름만 쓰면 필드 대신 영역·참거짓 등으로 읽힌다)
        Map<String, Object> doc = load("power-meter");
        int ri = messageIndex(doc, "readHolding");
        List<Object> body = (List<Object>) ((Map<String, Object>) ((List<Object>) doc.get("messages")).get(ri)).get("body");
        body.add(json("{\"name\":\"body\",\"type\":\"UINT\",\"size\":1}"));
        body.add(json("{\"name\":\"flags\",\"type\":\"BITS\",\"size\":1,\"fields\":[{\"name\":\"true\",\"bit\":0}]}"));
        body.add(json("{\"name\":\"len\",\"type\":\"UINT\",\"size\":1}"));   // 함수 이름은 괜찮다
        List<String> e = errors(doc);
        assertTrue(e.stream().anyMatch(x -> x.contains("Field name \"body\" is reserved")), e.toString());
        assertTrue(e.stream().anyMatch(x -> x.contains("Bit field name \"true\" is reserved")), e.toString());
        assertFalse(e.stream().anyMatch(x -> x.contains("\"len\"")), e.toString());

        // Reply 가 쓰는 메시지 이름, 식별자가 아닌 Args 이름
        Map<String, Object> d2 = load("power-meter");
        List<Object> ms = (List<Object>) d2.get("messages");
        ((Map<String, Object>) ms.get(messageIndex(d2, "exception"))).put("name", "echo");
        Map<String, Object> rule = (Map<String, Object>) ((List<Object>) ((Map<String, Object>) ms.get(messageIndex(d2, "readHolding"))).get("respond")).get(0);
        rule.put("args", json("{\"my code\": \"1\"}"));
        List<String> e2 = errors(d2);
        assertTrue(e2.stream().anyMatch(x -> x.contains("Message name \"echo\" is reserved for Reply")), e2.toString());
        assertTrue(e2.stream().anyMatch(x -> x.contains("Argument name \"my code\" is not a valid name")), e2.toString());
    }

    /** 예시 문서에서 이름으로 메시지 번호를 찾는다 (메시지를 더해도 번호에 기대지 않게) */
    @SuppressWarnings("unchecked")
    private static int messageIndex(Map<String, Object> doc, String name) {
        List<Object> ms = (List<Object>) doc.get("messages");
        for (int i = 0; i < ms.size(); i++) if (name.equals(((Map<String, Object>) ms.get(i)).get("name"))) return i;
        throw new AssertionError("no message " + name);
    }

    // ── 비트 필드의 자리 (형식 6.7) ────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void bitFieldsSitAtTheirOwnBitNotTheirOrder() throws Exception {
        Map<String, Object> doc = load("power-meter");
        Map<String, Object> holding = (Map<String, Object>) ((Map<String, Object>) doc.get("targets")).get("holding");
        // 순서를 뒤섞고 사이를 비운다: 모드(bit 4–6) · 운전(bit 0) · 경보(bit 9). 나머지 비트는 0
        ((List<Object>) holding.get("items")).add(json("{\"type\":\"BITS\",\"size\":2,\"access\":\"rw\",\"fields\":["
                + "{\"bit\":4,\"bits\":3,\"target\":\"모드\"},{\"bit\":0,\"target\":\"운전\"},{\"bit\":9,\"target\":\"경보\"}]}"));
        Definition def = compile(doc);
        DefinitionTests.MemoryStore store = new DefinitionTests.MemoryStore();
        ProtocolEngine e = new ProtocolEngine(() -> def, Collections.emptyMap(), store);
        store.values.put("운전", 1.0);
        store.values.put("모드", 5.0);
        store.values.put("경보", 1.0);
        // 0x0200 (bit 9) | 0x0050 (5 << 4) | 0x0001 = 0x0251
        assertArrayEquals(hex("02 51"), (byte[]) e.targetFunction("read", Arrays.asList("holding", 11L, 1L)));

        // 겹치거나 범위를 넘으면 오류
        ((Map<String, Object>) ((List<Object>) holding.get("items")).get(10)).put("fields", json("{\"x\":[{\"bit\":0,\"bits\":2,\"target\":\"a\"},{\"bit\":1,\"target\":\"b\"},{\"bit\":16,\"target\":\"c\"}]}").get("x"));
        List<String> errs = errors(doc);
        assertTrue(errs.stream().anyMatch(x -> x.contains("overlaps")), errs.toString());
        assertTrue(errs.stream().anyMatch(x -> x.contains("outside the 16 bits")), errs.toString());
    }

    // ── 숫자형: 표현 형식 + 크기·바이트 순서 속성 (형식 6.1) ──────────────────────

    @Test
    void numbersAreAFormWithSizeAndByteOrder() throws Exception {
        Map<String, Object> doc = json("{\"format\":\"cts-protocol\",\"version\":1,\"name\":\"n\","
                + "\"messages\":[{\"name\":\"q\",\"direction\":\"rx\",\"body\":[{\"name\":\"c\",\"type\":\"UINT\",\"size\":1}],\"respond\":[{\"reply\":\"r\"}]},"
                + "{\"name\":\"r\",\"direction\":\"tx\",\"body\":["
                + "{\"type\":\"UINT\",\"size\":3,\"const\":\"0x123456\"},"                                  // 24비트 — CTS 코드에는 없던 모양
                + "{\"type\":\"INT\",\"size\":4,\"byteOrder\":\"word-swap\",\"const\":-2},"               // FF FF FF FE → C D A B
                + "{\"name\":\"f\",\"type\":\"FLOAT\",\"size\":4,\"byteOrder\":\"little\",\"value\":\"1.5\"},"  // 3F C0 00 00 → 거꾸로
                + "{\"type\":\"UINT\",\"size\":2,\"byteOrder\":\"little\",\"const\":\"0x0102\"}]}],"
                + "\"tests\":[{\"name\":\"bytes\",\"send\":\"01\",\"expect\":\"12 34 56 FF FE FF FF 00 00 C0 3F 02 01\"}]}");
        Definition def = compile(doc);
        DefinitionTests.Outcome o = DefinitionTests.run(def).get(0);
        assertTrue(o.ok, o.message + " expected " + o.expected + " actual " + o.actual);

        // 크기·순서가 맞지 않으면, 그리고 예전 CTS 코드는 오류
        assertTrue(errors(namedDoc("{\"target\":\"t\"}", "{\"type\":\"UINT\",\"size\":2,\"byteOrder\":\"word-swap\",\"target\":\"t\"}"))
                .stream().anyMatch(e -> e.contains("needs 4 or 8 bytes")));
        assertTrue(errors(namedDoc("{\"target\":\"t\"}", "{\"type\":\"FLOAT\",\"size\":2,\"target\":\"t\"}"))
                .stream().anyMatch(e -> e.contains("size must be 4 or 8")));
        assertTrue(errors(namedDoc("{\"target\":\"t\"}", "{\"type\":\"INT\",\"target\":\"t\"}"))
                .stream().anyMatch(e -> e.contains("needs a size")));
        assertTrue(errors(namedDoc("{\"target\":\"t\"}", "{\"type\":\"UI16AB\",\"target\":\"t\"}"))
                .stream().anyMatch(e -> e.contains("Unknown type")));
    }

    // ── 파라미터: 값의 종류와 범위 (형식 11) ──────────────────────────────────

    private static List<String> paramErrors(String params) throws Exception {
        return errors(json("{\"format\":\"cts-protocol\",\"version\":1,\"name\":\"p\",\"params\":" + params + ","
                + "\"messages\":[{\"name\":\"q\",\"direction\":\"rx\",\"body\":[{\"name\":\"c\",\"type\":\"UINT\",\"size\":1}],\"respond\":[{\"reply\":\"none\"}]}]}"));
    }

    @Test
    void parametersDeclareAValueAndARange() throws Exception {
        assertEquals(Collections.emptyList(), paramErrors("{\"unit\":{\"value\":\"integer\",\"default\":1,\"min\":1,\"max\":247},\"gain\":{\"value\":\"float\",\"default\":0.5}}"));
        // value 를 비우면 integer
        assertEquals(Collections.emptyList(), paramErrors("{\"unit\":{\"default\":1}}"));
        assertTrue(paramErrors("{\"unit\":{\"type\":\"UI8\",\"default\":1}}").stream().anyMatch(e -> e.contains("not a type")));
        assertTrue(paramErrors("{\"unit\":{\"value\":\"integer\",\"default\":1.5}}").stream().anyMatch(e -> e.contains("whole number")));
        assertTrue(paramErrors("{\"unit\":{\"default\":300,\"min\":1,\"max\":247}}").stream().anyMatch(e -> e.contains("outside min")));
        assertTrue(paramErrors("{\"unit\":{\"min\":5,\"max\":1}}").stream().anyMatch(e -> e.contains("max is less than min")));
        assertTrue(paramErrors("{\"unit\":{\"value\":\"number\"}}").stream().anyMatch(e -> e.contains("integer or float")));

        // 장비 설정 검사: integer 는 정수만, 범위는 선언의 min·max
        Definition def = compile(load("power-meter"));
        Definition.Param unit = def.params.get("unitId");
        assertNull(com.onionsoftware.mk119.commtestsuite.db.service.DeviceSettingsValidatorAccess.paramProblem(unit, "17"));
        assertEquals("Parameter unitId must be a whole number.", com.onionsoftware.mk119.commtestsuite.db.service.DeviceSettingsValidatorAccess.paramProblem(unit, "1.5"));
        assertEquals("Parameter unitId must be from 0 to 255.", com.onionsoftware.mk119.commtestsuite.db.service.DeviceSettingsValidatorAccess.paramProblem(unit, "300"));
    }

    // ── 구조체: 한 단계, "structure": 이름 (형식 6.10) ─────────────────────────

    private static List<String> structErrors(String structures, String body) throws Exception {
        return errors(json("{\"format\":\"cts-protocol\",\"version\":1,\"name\":\"t\",\"structures\":" + structures + ","
                + "\"messages\":[{\"name\":\"q\",\"direction\":\"rx\",\"body\":" + body + ",\"respond\":[{\"reply\":\"none\"}]}]}"));
    }

    @Test
    void structuresAreOneLevelAndUsedByName() throws Exception {
        String point = "\"point\":{\"fields\":[{\"name\":\"id\",\"type\":\"UINT\",\"size\":1}]}";
        assertEquals(Collections.emptyList(), structErrors("{" + point + "}", "[{\"name\":\"p\",\"structure\":\"point\",\"count\":2}]"));
        // 구조체 안의 구조체 — 아무 메시지도 쓰지 않아도 잡는다
        assertTrue(structErrors("{" + point + ",\"line\":{\"fields\":[{\"name\":\"a\",\"structure\":\"point\"}]}}", "[{\"name\":\"c\",\"type\":\"UINT\",\"size\":1}]")
                .stream().anyMatch(e -> e.startsWith("[structures, line, fields, 0, structure]") && e.contains("cannot contain another structure")));
        // 인라인 fields, 구조체 이름을 type 으로, 예전 types
        assertTrue(structErrors("{}", "[{\"name\":\"g\",\"fields\":[{\"name\":\"x\",\"type\":\"UINT\",\"size\":1}]}]")
                .stream().anyMatch(e -> e.contains("inline fields")));
        assertTrue(structErrors("{" + point + "}", "[{\"name\":\"p\",\"type\":\"point\"}]").stream().anyMatch(e -> e.contains("is a structure")));
        assertTrue(errors(json("{\"format\":\"cts-protocol\",\"version\":1,\"name\":\"t\",\"types\":{},\"messages\":[{\"name\":\"q\",\"direction\":\"rx\",\"body\":[],\"respond\":[]}]}"))
                .stream().anyMatch(e -> e.contains("now \"structures\"")));
        // 구조체 안의 문제는 여러 곳에서 써도 한 번만
        List<String> once = structErrors("{\"bad\":{\"fields\":[{\"name\":\"v\",\"type\":\"UINT\"}]}}",
                "[{\"name\":\"a\",\"structure\":\"bad\"},{\"name\":\"b\",\"structure\":\"bad\"}]");
        assertEquals(1, once.stream().filter(x -> x.startsWith("[structures, bad, fields, 0, size]")).count(), once.toString());
    }

    // ── 본문 변환 (형식 6.8) ───────────────────────────────────────────────

    @Test
    void aBodyTransformWrapsTheWholeBody() throws Exception {
        // YD/T 1363 꼴: ~ [본문을 16진 글자로] [본문 글자 합 16진 2자] CR. 응답 본문은 두 필드짜리 레코드의 배열
        Map<String, Object> doc = json("{\"format\": \"cts-protocol\", \"version\": 1, \"name\": \"ydt\", \"checksums\": {\"sum\": {\"algorithm\": \"SUM\", \"width\": 8, \"covers\": {\"from\": \"body\", \"to\": \"body\"}}}, \"structures\": {\"rec\": {\"fields\": [{\"name\": \"v\", \"type\": \"UINT\", \"size\": 2, \"value\": \"100 + i\"}, {\"name\": \"c\", \"type\": \"UINT\", \"size\": 1, \"value\": \"i\"}]}}, \"frames\": {\"line\": {\"header\": [{\"type\": \"TEXT\", \"size\": 1, \"const\": \"~\"}], \"footer\": [{\"name\": \"sum\", \"type\": \"HEXNUM\", \"size\": 2, \"checksum\": \"sum\"}, {\"type\": \"TEXT\", \"size\": 1, \"const\": \"\\r\"}]}}, \"messages\": [{\"name\": \"get\", \"direction\": \"rx\", \"frame\": \"line\", \"bodyTransform\": [{\"op\": \"ascii-hex\"}], \"body\": [{\"name\": \"addr\", \"type\": \"UINT\", \"size\": 1}, {\"name\": \"cmd\", \"type\": \"UINT\", \"size\": 1}], \"match\": \"body.cmd == 0x42\", \"respond\": [{\"reply\": \"data\"}]}, {\"name\": \"data\", \"direction\": \"tx\", \"frame\": \"line\", \"bodyTransform\": [{\"op\": \"ascii-hex\"}], \"body\": [{\"name\": \"addr\", \"type\": \"UINT\", \"size\": 1, \"value\": \"req.body.addr\"}, {\"name\": \"n\", \"type\": \"UINT\", \"size\": 1, \"value\": \"2\"}, {\"name\": \"recs\", \"structure\": \"rec\", \"count\": \"body.n\"}]}], \"tests\": [{\"name\": \"records\", \"send\": \"7E 30 31 34 32 43 37 0D\", \"expect\": \"7E 30 31 30 32 30 30 36 34 30 30 30 30 36 35 30 31 31 39 0D\"}, {\"name\": \"bad sum\", \"send\": \"7E 30 31 34 32 30 30 0D\", \"expect\": null}]}");
        Definition def = compile(doc);
        for (DefinitionTests.Outcome o : DefinitionTests.run(def)) assertTrue(o.ok, o.name + ": " + o.message + " expected " + o.expected + " actual " + o.actual);
        // 푸터 크기가 정해지지 않으면(bodySize 도 없으면) 본문 끝을 알 수 없다
        @SuppressWarnings("unchecked") Map<String, Object> line = (Map<String, Object>) ((Map<String, Object>) doc.get("frames")).get("line");
        @SuppressWarnings("unchecked") List<Object> footer = (List<Object>) line.get("footer");
        footer.add(0, json("{\"name\":\"note\",\"type\":\"TEXT\",\"terminator\":\",\"}"));
        assertTrue(errors(doc).stream().anyMatch(e -> e.contains("needs the frame's bodySize")), errors(doc).toString());
    }
}
