package com.onionsoftware.mk119.commtestsuite.protocol;

import com.onionsoftware.mk119.commtestsuite.protocol.Definition.*;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Parser;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 문서(JSON) → {@link Definition} (docs/PROTOCOL_ENGINE_DESIGN.md 3절).
 * 문제는 {level, at, text} 로 모은다. at 은 편집기(js/cts-protocol-model.js)의 문제 경로와 같다.
 * 오류가 하나라도 있으면 Definition 을 내지 않는다. 경고는 막지 않는다.
 */
public final class Compiler {
    public static final String FORMAT = "cts-protocol";
    public static final int VERSION = 1;

    public static final class Problem {
        public final String level;
        public final List<Object> at;
        public final String text;
        Problem(String level, List<Object> at, String text) { this.level = level; this.at = at; this.text = text; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("at", at);
            m.put("text", text);
            return m;
        }
    }

    public static final class Result {
        public Definition definition;
        public final List<Problem> problems = new ArrayList<>();
        public boolean ok() {
            for (Problem p : problems) if ("error".equals(p.level)) return false;
            return true;
        }
    }

    private static final Set<String> SPECIAL = new HashSet<>(Arrays.asList("BCD", "TEXT", "TEXTNUM", "HEXNUM", "BYTES", "PAD", "BITS"));
    private static final Pattern IDENT = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_]*$");
    /** 필드 이름으로 쓸 수 없는 낱말 — 식에서 이름만 쓰면 필드 대신 영역·요청·번호·참거짓 등으로 읽힌다 (형식 3장) */
    static final Set<String> RESERVED_FIELD_NAMES = new HashSet<>(Arrays.asList(
            "header", "body", "footer", "req", "args", "i", "this", "it", "true", "false", "in"));
    /** 메시지 이름으로 쓸 수 없는 낱말 — 응답 규칙의 Reply 가 따로 쓴다 */
    static final Set<String> RESERVED_MESSAGE_NAMES = new HashSet<>(Arrays.asList("none", "echo"));

    private final Result result = new Result();
    private final Definition def = new Definition();
    private Map<String, Object> doc;
    private final Set<String> dollars = new HashSet<>();
    private String defaultEncoding = "ascii", defaultOverflow = "error";
    /** 이름 붙은 구조체 (형식 6.10). 필드는 "structure": 이름 으로 쓴다 */
    private Map<String, Object> structures = Collections.emptyMap();
    private final Deque<String> structStack = new ArrayDeque<>();

    private Compiler() {}

    public static Result compile(Object document) {
        Compiler c = new Compiler();
        c.run(document);
        c.dedupe();
        if (c.result.ok()) c.result.definition = c.def;
        return c.result;
    }

    // ── 문제 ─────────────────────────────────────────────────────────

    private static List<Object> at(Object... parts) { return new ArrayList<>(Arrays.asList(parts)); }

    private static List<Object> plus(List<Object> base, Object... parts) {
        List<Object> out = new ArrayList<>(base);
        out.addAll(Arrays.asList(parts));
        return out;
    }

    private void error(List<Object> at, String text) { result.problems.add(new Problem("error", at, text)); }

    /** 같은 자리의 같은 문제는 한 번만 — 타입은 쓰는 곳마다 다시 컴파일되므로 같은 문제가 여러 번 나온다 */
    private void dedupe() {
        Set<String> seen = new HashSet<>();
        result.problems.removeIf(p -> !seen.add(p.level + "|" + p.at + "|" + p.text));
    }

    private void warn(List<Object> at, String text) { result.problems.add(new Problem("warn", at, text)); }

    // ── 전체 ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void run(Object document) {
        if (!(document instanceof Map)) {
            error(at(), "The document must be a JSON object.");
            return;
        }
        doc = (Map<String, Object>) document;
        Object format = doc.get("format"), version = doc.get("version");
        if (!FORMAT.equals(format) || !(version instanceof Number) || ((Number) version).intValue() != VERSION) {
            error(at("format"), "Format must be \"" + FORMAT + "\" version " + VERSION + ".");
        }
        String name = str(doc.get("name"));
        if (name == null || name.trim().isEmpty()) error(at("name"), "Document name is required.");
        def.name = name == null ? "" : name.trim();

        Map<String, Object> defaults = map(doc.get("defaults"));
        if (str(defaults.get("encoding")) != null) defaultEncoding = str(defaults.get("encoding"));
        if (str(defaults.get("onOverflow")) != null) defaultOverflow = str(defaults.get("onOverflow"));

        params();
        consts();
        structures = map(doc.get("structures"));
        for (String t : structures.keySet()) if (!isIdent(t)) error(at("structures", t), "Structure name \"" + t + "\" is not a valid name.");
        if (doc.containsKey("types")) error(at("types"), "\"types\" is now \"structures\"; a field uses one with \"structure\": name.");
        targets();
        checksums();
        framing();
        messages();
        typeDefinitions();
        onUnmatched();
        tests();
        literalTargets();
        targetValues();
    }

    /** 파라미터가 가질 수 있는 키 — 값의 종류와 범위만 (형식 11). 예전의 type(CTS 코드)은 받지 않는다 */
    private static final Set<String> PARAM_KEYS = new HashSet<>(Arrays.asList("value", "default", "min", "max", "doc"));

    private void params() {
        Map<String, Object> ps = map(doc.get("params"));
        for (Map.Entry<String, Object> e : ps.entrySet()) {
            List<Object> a = at("params", e.getKey());
            if (!isIdent(e.getKey())) error(a, "Parameter name \"" + e.getKey() + "\" is not a valid name.");
            Map<String, Object> p = map(e.getValue());
            Param x = new Param();
            x.name = e.getKey();
            for (String k : p.keySet()) {
                if (PARAM_KEYS.contains(k)) continue;
                error(plus(a, k), "type".equals(k)
                        ? "Parameter \"" + x.name + "\" declares its value as integer or float (value), not a type."
                        : "Parameter \"" + x.name + "\" has no \"" + k + "\"; use value, default, min, max or doc.");
            }
            String v = str(p.get("value"));
            if (v != null && !v.isEmpty()) {
                if (v.equals("integer") || v.equals("float")) x.value = v;
                else error(plus(a, "value"), "Parameter value must be integer or float.");
            }
            x.defaultValue = paramNumber(x, p, "default", a);
            Object mn = paramNumber(x, p, "min", a), mx = paramNumber(x, p, "max", a);
            x.min = mn == null ? null : ((Number) mn).doubleValue();
            x.max = mx == null ? null : ((Number) mx).doubleValue();
            if (x.min != null && x.max != null && x.min > x.max) error(plus(a, "max"), "Parameter \"" + x.name + "\": max is less than min.");
            if (x.defaultValue != null) {
                double d = ((Number) x.defaultValue).doubleValue();
                if (x.min != null && d < x.min || x.max != null && d > x.max) {
                    error(plus(a, "default"), "Parameter \"" + x.name + "\": the default is outside min–max.");
                }
            }
            def.params.put(x.name, x);
            dollars.add(x.name);
        }
    }

    /** 파라미터의 default·min·max: JSON 수, integer 면 정수 */
    private Object paramNumber(Param x, Map<String, Object> p, String key, List<Object> a) {
        Object raw = p.get(key);
        if (raw == null || "".equals(raw)) return null;
        if (!(raw instanceof Number)) { error(plus(a, key), "Parameter \"" + x.name + "\": " + key + " must be a number."); return null; }
        Object n = number(raw);
        if (!x.isReal() && n instanceof Double && ((Double) n) != Math.rint((Double) n)) {
            error(plus(a, key), "Parameter \"" + x.name + "\" is integer, so " + key + " must be a whole number.");
        }
        return n;
    }

    private void consts() {
        Map<String, Object> cs = map(doc.get("consts"));
        for (Map.Entry<String, Object> e : cs.entrySet()) {
            List<Object> a = at("consts", e.getKey());
            if (!isIdent(e.getKey())) error(a, "Constant name \"" + e.getKey() + "\" is not a valid name.");
            if (def.params.containsKey(e.getKey())) error(a, "\"" + e.getKey() + "\" is both a constant and a parameter.");
            Object v = e.getValue();
            Object n = number(v);
            def.consts.put(e.getKey(), n != null ? n : v);
            dollars.add(e.getKey());
        }
    }

    // ── 타겟 ─────────────────────────────────────────────────────────

    private void targets() {
        Map<String, Object> groups = map(doc.get("targets"));
        for (Map.Entry<String, Object> e : groups.entrySet()) {
            List<Object> a = at("targets", e.getKey());
            if (!isIdent(e.getKey())) error(a, "Target group name \"" + e.getKey() + "\" is not a valid name.");
            Map<String, Object> g = map(e.getValue());
            TargetGroup tg = new TargetGroup();
            tg.name = e.getKey();
            tg.kind = str(g.get("kind"));
            if ("sequence".equals(tg.kind)) sequence(tg, g, a);
            else if ("named".equals(tg.kind)) named(tg, g, a);
            else if ("pattern".equals(tg.kind)) pattern(tg, g, a);
            else { error(plus(a, "kind"), "Target group kind must be sequence, named or pattern."); continue; }
            def.targets.put(tg.name, tg);
        }
    }

    private void sequence(TargetGroup tg, Map<String, Object> g, List<Object> a) {
        tg.base = intOr(g.get("base"), 0);
        tg.slotSize = intOr(g.get("slotSize"), 1);
        if (tg.slotSize < 1) { error(plus(a, "slotSize"), "Slot size must be 1 or more."); tg.slotSize = 1; }
        tg.unmappedZero = "zero".equals(str(g.get("unmapped")));
        int address = tg.base, end = tg.base;
        List<Object> items = list(g.get("items"));
        for (int i = 0; i < items.size(); i++) {
            List<Object> ia = plus(a, "items", i);
            Map<String, Object> it = map(items.get(i));
            SeqItem s = new SeqItem();
            if (it.get("at") != null) address = intOr(it.get("at"), address);
            s.address = address;
            if (it.get("skip") != null) {
                s.skip = true;
                s.slots = intOr(it.get("skip"), 0);
                if (s.slots < 1) error(plus(ia, "skip"), "Skip needs a number of slots.");
            } else {
                s.target = str(it.get("target"));
                if (s.target != null && s.target.isEmpty()) s.target = null;
                FieldSpec spec = fieldSpec(it, ia, false, true);
                if (spec == null) continue;
                // 비트 집합(BITS): 항목 자신은 타겟이 없고, 비트 필드마다 타겟·고정값을 갖는다
                boolean bits = spec.kind == Kind.BITS;
                if (bits && s.target != null) {
                    error(plus(ia, "target"), "A BITS item has no target of its own; give each bit field its target.");
                    s.target = null;
                }
                if (!bits && s.target == null && spec.constValue == null) error(ia, "An item needs a target, a const or skip.");
                int width = spec.fixedWidth();
                if (width <= 0) { error(plus(ia, "type"), "An item in a sequence needs a type with a fixed size."); continue; }
                s.spec = spec;
                s.slots = (width + tg.slotSize - 1) / tg.slotSize;
                s.writable = "rw".equals(str(it.get("access")));
                if (s.target != null) declare(s.target, tg.name, spec, s.writable, ia);
                if (bits) for (FieldSpec b : spec.fields) if (b.target != null) declare(b.target, tg.name, b, s.writable, b.at);
            }
            for (SeqItem o : tg.items) {
                if (s.address < o.address + o.slots && o.address < s.address + s.slots) {
                    error(ia, "Addresses " + s.address + "–" + (s.address + s.slots - 1) + " overlap another item.");
                    break;
                }
            }
            tg.items.add(s);
            address += s.slots;
            end = Math.max(end, address);
        }
        tg.items.sort(Comparator.comparingInt(x -> x.address));
        tg.totalSlots = end - tg.base;
    }

    private void named(TargetGroup tg, Map<String, Object> g, List<Object> a) {
        List<Object> items = list(g.get("items"));
        for (int i = 0; i < items.size(); i++) {
            List<Object> ia = plus(a, "items", i);
            Map<String, Object> it = map(items.get(i));
            String target = str(it.get("target"));
            if (target == null || target.isEmpty()) { error(plus(ia, "target"), "A named item needs a target."); continue; }
            onlyValue(it, ia, "Named target \"" + target + "\"", NAMED_KEYS);
            declare(target, tg.name, null, "rw".equals(str(it.get("access"))), ia);
            TargetInfo t = def.catalog.get(target);
            if (t != null && t.group.equals(tg.name)) t.value = valueName(it.get("value"), plus(ia, "value"));
        }
    }

    /** 이름 타겟 항목·패턴 묶음이 가질 수 있는 키 — 표현(type·size·scale…)은 메시지 필드에 둔다 (형식 8.3) */
    private static final Set<String> NAMED_KEYS = new HashSet<>(Arrays.asList("target", "value", "access", "doc"));
    private static final Set<String> PATTERN_KEYS = new HashSet<>(Arrays.asList("kind", "pattern", "value", "access", "doc"));

    private void onlyValue(Map<String, Object> m, List<Object> a, String what, Set<String> allowed) {
        for (String k : m.keySet()) {
            if (allowed.contains(k)) continue;
            error(plus(a, k), what + " declares only its value (binary, integer or float) and access. "
                    + "Put the representation (" + k + ") on the message field that carries it.");
            return;
        }
    }

    private String valueName(Object v, List<Object> a) {
        String s = str(v);
        if (s == null || s.isEmpty()) return null;
        if (!Definition.VALUES.contains(s)) { error(a, "Value must be binary, integer or float."); return null; }
        return s;
    }

    private void pattern(TargetGroup tg, Map<String, Object> g, List<Object> a) {
        String p = str(g.get("pattern"));
        try {
            tg.pattern = Pattern.compile(p == null ? "" : p);
        } catch (PatternSyntaxException e) {
            error(plus(a, "pattern"), "Pattern is not a valid regular expression: " + e.getDescription());
            tg.pattern = Pattern.compile("(?!)");
        }
        onlyValue(g, a, "Pattern group \"" + tg.name + "\"", PATTERN_KEYS);
        tg.patternValue = valueName(g.get("value"), plus(a, "value"));
        tg.patternWritable = "rw".equals(str(g.get("access")));
        def.patterns.add(tg);
    }

    private void declare(String target, String group, FieldSpec spec, boolean writable, List<Object> a) {
        if (def.catalog.containsKey(target)) {
            error(plus(a, "target"), "Target \"" + target + "\" is declared twice.");
            return;
        }
        TargetInfo t = new TargetInfo();
        t.target = target;
        t.group = group;
        t.spec = spec;
        t.writable = writable;
        t.declared = true;
        def.catalog.put(target, t);
    }

    // ── 체크섬·프레이밍 ──────────────────────────────────────────────

    private void checksums() {
        Map<String, Object> cs = map(doc.get("checksums"));
        for (Map.Entry<String, Object> e : cs.entrySet()) {
            List<Object> a = at("checksums", e.getKey());
            if (!isIdent(e.getKey())) error(a, "Checksum name \"" + e.getKey() + "\" is not a valid name.");
            Map<String, Object> c = map(e.getValue());
            ChecksumSpec x = new ChecksumSpec();
            x.name = e.getKey();
            String alg = str(c.get("algorithm"));
            if (alg == null) { error(plus(a, "algorithm"), "Choose an algorithm."); continue; }
            Checksums.Crc cat = Checksums.catalog(alg);
            if (cat != null) x.algorithm = cat;
            else if (alg.equals("CRC")) {
                int width = intOr(c.get("width"), 16);
                if (width < 1 || width > 64) { error(plus(a, "width"), "CRC width must be 1 to 64."); continue; }
                x.algorithm = new Checksums.Crc(width, longOr(c.get("poly"), 0), longOr(c.get("init"), 0),
                        Boolean.TRUE.equals(c.get("refin")), Boolean.TRUE.equals(c.get("refout")), longOr(c.get("xorout"), 0));
                if (c.get("poly") == null) error(plus(a, "poly"), "A custom CRC needs poly.");
            } else if (alg.equals("SUM")) x.algorithm = Checksums.sum(intOr(c.get("width"), 8), str(c.get("complement")));
            else if (alg.equals("XOR8")) x.algorithm = Checksums.XOR8;
            else if (alg.equals("LRC8")) x.algorithm = Checksums.LRC8;
            else if (alg.equals("EXPR")) {
                Expr init = exprOrNull(c.get("init"), plus(a, "init"), null);
                Expr step = exprOrNull(c.get("step"), plus(a, "step"), null);
                Expr fin = exprOrNull(c.get("final"), plus(a, "final"), null);
                if (c.get("step") == null) { error(plus(a, "step"), "An EXPR checksum needs step."); continue; }
                if (step == null) continue;
                x.algorithm = Checksums.expr(init, step, fin);
            } else { error(plus(a, "algorithm"), "Unknown algorithm \"" + alg + "\"."); continue; }
            Map<String, Object> covers = map(c.get("covers"));
            x.from = str(covers.get("from"));
            x.to = str(covers.get("to"));
            if (x.from == null || x.to == null) error(plus(a, "covers"), "Say which bytes the checksum covers (from and to).");
            x.overValue = "value".equals(str(c.get("over")));
            x.flagOnly = "flag".equals(str(c.get("onMismatch")));
            def.checksums.put(x.name, x);
        }
    }

    private void framing() {
        Map<String, Object> f = map(doc.get("framing"));
        Object sync = f.get("syncOn");
        if (sync instanceof Number) def.syncOn = new byte[]{(byte) ((Number) sync).intValue()};
        else if (sync instanceof String && !((String) sync).isEmpty()) {
            String s = (String) sync;
            byte[] hex = s.startsWith("0x") || s.startsWith("0X") ? Values.parseHex(s) : null;
            def.syncOn = hex != null ? hex : s.getBytes(StandardCharsets.UTF_8);
        }
        if (f.get("maxLength") != null) def.maxLength = Math.max(1, intOr(f.get("maxLength"), 1024));
        if (f.get("idleFlushMs") != null) def.idleFlushMs = intOr(f.get("idleFlushMs"), 0);
        if (map(f.get("escape")).get("mode") != null && !"".equals(map(f.get("escape")).get("mode"))) {
            warn(at("framing", "escape"), "Byte stuffing is not supported yet; it is ignored.");
        }
    }

    // ── 메시지 ───────────────────────────────────────────────────────

    private void messages() {
        List<Object> ms = list(doc.get("messages"));
        if (ms.isEmpty()) error(at("messages"), "Add at least one received (rx) message.");
        Map<String, Object> frames = map(doc.get("frames"));
        Set<String> names = new HashSet<>();
        List<Object[]> pendingRules = new ArrayList<>();
        for (int i = 0; i < ms.size(); i++) {
            List<Object> a = at("messages", i);
            Map<String, Object> m = map(ms.get(i));
            MessageSpec x = new MessageSpec();
            x.name = str(m.get("name"));
            x.index = i;
            if (x.name == null || !isIdent(x.name)) { error(plus(a, "name"), "A message needs a valid name."); x.name = "message" + i; }
            else if (RESERVED_MESSAGE_NAMES.contains(x.name)) error(plus(a, "name"), "Message name \"" + x.name + "\" is reserved for Reply; choose another name.");
            else if (!names.add(x.name)) error(plus(a, "name"), "Message name \"" + x.name + "\" is used twice.");
            String dir = str(m.get("direction"));
            if (!"rx".equals(dir) && !"tx".equals(dir)) { error(plus(a, "direction"), "Direction must be rx or tx."); continue; }
            x.rx = "rx".equals(dir);
            String frame = str(m.get("frame"));
            if (frame != null && !frame.isEmpty()) {
                if (!frames.containsKey(frame)) { error(plus(a, "frame"), "Frame \"" + frame + "\" does not exist."); continue; }
                Map<String, Object> fr = map(frames.get(frame));
                List<Object> fa = at("frames", frame);
                fields(list(fr.get("header")), plus(fa, "header"), x.header, x.rx);
                fields(list(fr.get("footer")), plus(fa, "footer"), x.footer, x.rx);
                if (fr.get("bodySize") != null) x.bodySize = exprOrNull(fr.get("bodySize"), plus(fa, "bodySize"), dollars);
            } else {
                fields(list(m.get("header")), plus(a, "header"), x.header, x.rx);
                fields(list(m.get("footer")), plus(a, "footer"), x.footer, x.rx);
            }
            fields(list(m.get("body")), plus(a, "body"), x.body, x.rx);
            bodyTransform(x, m, a);
            if (x.rx) {
                if (m.get("match") != null && !"".equals(m.get("match"))) x.match = exprOrNull(m.get("match"), plus(a, "match"), dollars);
                if (m.get("size") != null) x.size = intOr(m.get("size"), 0);
                pendingRules.add(new Object[]{x, list(m.get("respond")), plus(a, "respond")});
                def.rx.add(x);
            } else {
                Map<String, Object> set = map(m.get("set"));
                for (Map.Entry<String, Object> e : set.entrySet()) {
                    List<Object> sa = plus(a, "set", e.getKey());
                    if (!hasTopField(x, e.getKey())) error(sa, "\"" + e.getKey() + "\" is not a header or footer field of this message.");
                    Expr ex = exprOrNull(e.getValue(), sa, dollars);
                    if (ex != null) x.set.put(e.getKey(), ex);
                }
                def.tx.put(x.name, x);
                txSources(x, a);
            }
        }
        for (Object[] p : pendingRules) {
            @SuppressWarnings("unchecked") List<Object> rules = (List<Object>) p[1];
            @SuppressWarnings("unchecked") List<Object> ra = (List<Object>) p[2];
            respond((MessageSpec) p[0], rules, ra);
        }
        if (def.rx.isEmpty() && !ms.isEmpty()) error(at("messages"), "Add at least one received (rx) message.");
    }

    /** 변환 목록 (필드의 transform, 메시지의 bodyTransform 공통) */
    private List<Transform> transforms(Object raw, List<Object> a) {
        List<Transform> out = new ArrayList<>();
        for (Object t : list(raw)) {
            Map<String, Object> tm = map(t);
            Transform x = new Transform();
            x.op = str(tm.get("op"));
            x.value = longOr(tm.get("value"), 0);
            x.lowerCase = "lower".equals(tm.get("case"));
            if (!"ascii-hex".equals(x.op) && !"add".equals(x.op) && !"xor".equals(x.op)) {
                error(a, "Transform op must be ascii-hex, add or xor.");
                continue;
            }
            out.add(x);
        }
        return out;
    }

    /**
     * 본문 변환 (형식 6.8). 받을 때 본문의 전송 바이트가 어디까지인지 알아야 한다:
     * 프레임의 bodySize, 없으면 받은 바이트에서 고정 크기 푸터를 뺀 나머지.
     */
    private void bodyTransform(MessageSpec x, Map<String, Object> m, List<Object> a) {
        x.bodyTransform.addAll(transforms(m.get("bodyTransform"), plus(a, "bodyTransform")));
        int w = 0;
        for (FieldSpec f : x.footer) {
            int fw = f.when != null || f.isArray() ? -1 : f.fixedWidth();
            if (fw < 0) { w = -1; break; }
            w += fw;
        }
        x.footerWidth = w;
        if (x.bodyTransform.isEmpty() || !x.rx || x.bodySize != null) return;
        if (w < 0) {
            error(plus(a, "bodyTransform"), "A transformed body needs the frame's bodySize or a footer of fixed size, to know where the body ends.");
        } else {
            warn(plus(a, "bodyTransform"), "Without a bodySize the transformed body runs to the end of the received bytes (minus the footer). Over TCP give the frame a bodySize.");
        }
    }

    private static boolean hasTopField(MessageSpec x, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) return false;
        String region = path.substring(0, dot), name = path.substring(dot + 1);
        if (!region.equals("header") && !region.equals("footer") && !region.equals("body")) return false;
        for (FieldSpec f : x.region(region)) if (name.equals(f.name)) return true;
        return false;
    }

    /** tx 의 모든 필드가 값 출처를 가졌는지 (형식 5.2) */
    private void txSources(MessageSpec x, List<Object> a) {
        for (String r : new String[]{"header", "body", "footer"}) {
            for (FieldSpec f : x.region(r)) {
                String path = f.name == null ? null : r + "." + f.name;
                if (path != null && x.set.containsKey(path)) continue;
                missingSource(f, f.at, a, r);
            }
        }
    }

    private void missingSource(FieldSpec f, List<Object> fieldAt, List<Object> messageAt, String region) {
        if (f.kind == Kind.STRUCT) { for (FieldSpec c : f.fields) missingSource(c, c.at, messageAt, region); return; }
        if (f.kind == Kind.BITS) { for (FieldSpec c : f.fields) missingSource(c, c.at, messageAt, region); return; }
        if (f.kind == Kind.PAD || f.constValue != null || f.value != null || f.target != null || f.checksum != null) return;
        boolean shared = fieldAt != null && !fieldAt.isEmpty() && "frames".equals(fieldAt.get(0));
        if (shared) {
            error(plus(messageAt, "set"), "Frame field " + region + "." + (f.name == null ? "(unnamed)" : f.name)
                    + " has no value; set it in this message.");
        } else {
            error(fieldAt, "Field " + (f.name == null ? "(unnamed)" : "\"" + f.name + "\"") + " has no value to send (const, value or target).");
        }
    }

    private void respond(MessageSpec x, List<Object> rules, List<Object> a) {
        for (int j = 0; j < rules.size(); j++) {
            List<Object> ra = plus(a, j);
            Map<String, Object> r = map(rules.get(j));
            Rule rule = new Rule();
            rule.index = j;
            rule.name = str(r.get("name"));
            if (r.get("when") != null && !"".equals(r.get("when"))) rule.when = exprOrNull(r.get("when"), plus(ra, "when"), dollars);
            Object reply = r.get("reply");
            rule.reply = reply == null ? "none" : String.valueOf(reply);
            if (!rule.reply.equals("none") && !rule.reply.equals("echo") && !def.tx.containsKey(rule.reply)) {
                error(plus(ra, "reply"), "Reply \"" + rule.reply + "\" is not a message the device sends (tx).");
            }
            for (Map.Entry<String, Object> e : map(r.get("args")).entrySet()) {
                if (!isIdent(e.getKey())) { error(plus(ra, "args", e.getKey()), "Argument name \"" + e.getKey() + "\" is not a valid name."); continue; }
                Expr ex = exprOrNull(e.getValue(), plus(ra, "args", e.getKey()), dollars);
                if (ex != null) rule.args.put(e.getKey(), ex);
            }
            rule.delayMs = Math.max(0, intOr(r.get("delayMs"), 0));
            List<Object> ws = list(r.get("write"));
            for (int k = 0; k < ws.size(); k++) {
                List<Object> wa = plus(ra, "write", k);
                Map<String, Object> w = map(ws.get(k));
                WriteSpec s = new WriteSpec();
                if (w.get("target") != null) {
                    s.target = str(w.get("target"));
                    s.value = exprOrNull(w.get("value"), plus(wa, "value"), dollars);
                    TargetInfo t = def.targetInfo(s.target);
                    if (t == null) warn(plus(wa, "target"), "Target \"" + s.target + "\" is not declared; the write will be skipped.");
                    else if (!t.writable) warn(plus(wa, "target"), "Target \"" + s.target + "\" is not rw; the write will be skipped.");
                } else {
                    s.map = str(w.get("map"));
                    TargetGroup g = s.map == null ? null : def.targets.get(s.map);
                    if (g == null || !"sequence".equals(g.kind)) { error(plus(wa, "map"), "Write map must be a sequence target group."); continue; }
                    s.address = exprOrNull(w.get("address"), plus(wa, "address"), dollars);
                    s.bytes = exprOrNull(w.get("bytes"), plus(wa, "bytes"), dollars);
                }
                if (w.get("count") != null) s.count = exprOrNull(w.get("count"), plus(wa, "count"), dollars);
                if ((s.target != null && s.value == null) || (s.map != null && (s.address == null || s.bytes == null))) continue;
                rule.writes.add(s);
            }
            x.respond.add(rule);
        }
    }

    /** 모든 타입을 한 번씩 따로 컴파일한다 — 아무 메시지도 쓰지 않는 타입의 문제도 저장할 때 보이게 */
    private void typeDefinitions() {
        for (String t : structures.keySet()) {
            structStack.push(t);
            try {
                fields(list(map(structures.get(t)).get("fields")), at("structures", t, "fields"), new ArrayList<>(), true);
            } finally {
                structStack.pop();
            }
        }
    }

    private void onUnmatched() {
        String r = str(map(doc.get("onUnmatched")).get("reply"));
        if (r == null || r.isEmpty()) return;
        if (!r.equals("none") && !r.equals("echo") && !def.tx.containsKey(r)) {
            error(at("onUnmatched", "reply"), "Reply \"" + r + "\" is not a message the device sends (tx).");
        }
        def.onUnmatched = r;
    }

    // ── 필드 ─────────────────────────────────────────────────────────

    private void fields(List<Object> raw, List<Object> a, List<FieldSpec> into, boolean rx) {
        Set<String> names = new HashSet<>();
        for (int j = 0; j < raw.size(); j++) {
            List<Object> fa = plus(a, j);
            FieldSpec f = fieldSpec(map(raw.get(j)), fa, false, false);
            if (f == null) continue;
            if (f.name != null && !names.add(f.name)) error(plus(fa, "name"), "Field name \"" + f.name + "\" is used twice here.");
            into.add(f);
        }
    }

    /**
     * 필드 하나 (타겟 선언 항목도 같은 모양).
     * @param allowBit  BIT 타입을 받는가 (이름 타겟·BITS 하위 필드)
     * @param item      타겟 선언 항목이면 true — 이름이 없어도 된다
     */
    private FieldSpec fieldSpec(Map<String, Object> m, List<Object> a, boolean allowBit, boolean item) {
        FieldSpec f = new FieldSpec();
        f.at = a;
        f.name = str(m.get("name"));
        if (f.name != null && f.name.isEmpty()) f.name = null;
        checkFieldName(f.name, plus(a, "name"), "Field");
        f.target = item ? null : str(m.get("target"));
        if (f.target != null && f.target.isEmpty()) f.target = null;
        f.checksum = str(m.get("checksum"));
        if (f.checksum != null && f.checksum.isEmpty()) f.checksum = null;
        if (f.checksum != null && !map(doc.get("checksums")).containsKey(f.checksum)) {
            error(plus(a, "checksum"), "Checksum \"" + f.checksum + "\" does not exist.");
        }
        f.encoding = str(m.get("encoding")) != null ? str(m.get("encoding")) : defaultEncoding;
        f.onOverflow = str(m.get("onOverflow")) != null ? str(m.get("onOverflow")) : defaultOverflow;

        String type = str(m.get("type"));
        if (type != null && type.isEmpty()) type = null;
        String structure = str(m.get("structure"));
        if (structure != null && structure.isEmpty()) structure = null;
        List<Object> inline = m.get("fields") instanceof List ? list(m.get("fields")) : null;
        if (structure != null) {
            // 구조체는 한 단계뿐이다 — 구조체 안에서 구조체를 쓰지 않는다 (형식 6.10)
            if (type != null) error(plus(a, "type"), "Field " + label(f) + " has a type and a structure; give it one.");
            if (!structStack.isEmpty()) {
                error(plus(a, "structure"), "Structure \"" + structStack.peek() + "\" uses structure \"" + structure + "\"; a structure cannot contain another structure.");
                return null;
            }
            if (!structures.containsKey(structure)) { error(plus(a, "structure"), "Structure \"" + structure + "\" does not exist."); return null; }
            structStack.push(structure);
            try {
                f.kind = Kind.STRUCT;
                fields(list(map(structures.get(structure)).get("fields")), at("structures", structure, "fields"), f.fields, true);
            } finally {
                structStack.pop();
            }
        } else if (type == null && inline != null) {
            error(plus(a, "fields"), "Field " + label(f) + " has inline fields; define the group in structures and use \"structure\": name.");
            return null;
        } else if (type == null) {
            if (m.get("bits") != null && allowBit) { f.kind = Kind.BIT; }
            else { error(plus(a, "type"), "Field " + label(f) + " needs a type."); return null; }
        } else if (NUMBER_TYPES.contains(type)) {
            f.kind = Kind.NUM;
            f.num = type;
        } else if (SPECIAL.contains(type)) {
            f.kind = Kind.valueOf(type);
        } else if (type.equals("BIT")) {
            if (!allowBit) { error(plus(a, "type"), "BIT is only for named targets and bit fields."); return null; }
            f.kind = Kind.BIT;
        } else if (structures.containsKey(type)) {
            error(plus(a, "type"), "\"" + type + "\" is a structure; write \"structure\": \"" + type + "\" instead of type.");
            return null;
        } else {
            error(plus(a, "type"), "Unknown type \"" + type + "\".");
            return null;
        }

        if (m.get("scale") != null) { f.scale = dbl(m.get("scale"), 1); f.scaled = true; }
        if (m.get("offset") != null) { f.offset = dbl(m.get("offset"), 0); f.scaled = true; }
        if (f.scale == 0) { error(plus(a, "scale"), "Scale cannot be 0."); f.scale = 1; }
        Object size = m.get("size");
        if (size != null && !"".equals(size)) {
            Integer n = intLiteral(size);
            if (n != null) f.size = n;
            else f.sizeExpr = exprOrNull(size, plus(a, "size"), dollars);
        }
        Object count = m.get("count");
        if (count != null && !"".equals(count)) {
            Integer n = intLiteral(count);
            if (n != null) f.count = n;
            else f.countExpr = exprOrNull(count, plus(a, "count"), dollars);
        }
        if (m.get("terminator") instanceof String && !((String) m.get("terminator")).isEmpty()) {
            f.terminator = ((String) m.get("terminator")).getBytes(Codec.charset(f.encoding));
        }
        if (m.get("pad") instanceof String) f.pad = (String) m.get("pad");
        if (m.get("align") instanceof String) f.align = (String) m.get("align");
        if (m.get("decimals") != null) f.decimals = intOr(m.get("decimals"), 0);
        if (m.get("point") instanceof Boolean) f.point = (Boolean) m.get("point");
        if (m.get("sign") instanceof String) f.sign = (String) m.get("sign");
        if ("lower".equals(m.get("case"))) f.lowerCase = true;
        if ("little".equals(m.get("byteOrder"))) f.littleBytes = true;
        if (f.kind == Kind.NUM) numberEncoding(f, m, a);
        if ("msb".equals(m.get("bitOrder"))) f.msbFirst = true;
        if (m.get("bits") != null) f.bits = intOr(m.get("bits"), 0);
        if (m.get("fill") != null) f.fill = longOr(m.get("fill"), 0);
        if (m.get("when") != null && !"".equals(m.get("when"))) f.when = exprOrNull(m.get("when"), plus(a, "when"), dollars);
        if (m.get("expect") != null && !"".equals(m.get("expect"))) f.expect = exprOrNull(m.get("expect"), plus(a, "expect"), dollars);
        if (m.get("value") != null && !"".equals(m.get("value"))) f.value = exprOrNull(m.get("value"), plus(a, "value"), dollars);
        if (m.get("const") != null) f.constValue = constOf(f, m.get("const"), plus(a, "const"));

        f.transforms.addAll(transforms(m.get("transform"), plus(a, "transform")));
        if (!f.transforms.isEmpty() && f.kind != Kind.STRUCT) error(plus(a, "transform"), "Transforms go on a structure field.");

        if (f.kind == Kind.BITS) {
            if (f.size == null) { error(plus(a, "size"), "BITS needs a size in bytes."); return null; }
            if (f.size < 1 || f.size > 8) { error(plus(a, "size"), "BITS size must be 1 to 8 bytes."); return null; }
            int total = f.size * 8;
            boolean[] used = new boolean[total];
            // 자리를 적지 않은 비트 필드는 앞 필드 다음 자리다 (lsb: 위로, msb: 아래로) — 예전 문서와 같은 배치
            int cursor = f.msbFirst ? total : 0;
            List<Object> subs = list(m.get("fields"));
            for (int k = 0; k < subs.size(); k++) {
                List<Object> sa = plus(a, "fields", k);
                Map<String, Object> sm = map(subs.get(k));
                FieldSpec sub = new FieldSpec();
                sub.at = sa;
                sub.kind = Kind.BIT;
                sub.name = str(sm.get("name"));
                if (sub.name != null && sub.name.isEmpty()) sub.name = null;
                checkFieldName(sub.name, plus(sa, "name"), "Bit field");
                sub.bits = sm.get("bits") == null ? 1 : intOr(sm.get("bits"), 0);
                if (sub.bits < 1) { error(plus(sa, "bits"), "A bit field needs bits (1 or more)."); sub.bits = 1; }
                int pos;
                if (sm.get("bit") != null && !"".equals(sm.get("bit"))) {
                    pos = intOr(sm.get("bit"), -1);
                    cursor = f.msbFirst ? pos : pos + sub.bits;
                } else if (f.msbFirst) {
                    cursor -= sub.bits;
                    pos = cursor;
                } else {
                    pos = cursor;
                    cursor += sub.bits;
                }
                String where = sub.bits == 1 ? "bit " + pos : "bits " + pos + "–" + (pos + sub.bits - 1);
                if (pos < 0 || pos + sub.bits > total) {
                    error(plus(sa, "bit"), "Bit field " + (k) + " (" + where + ") is outside the " + total + " bits (0–" + (total - 1) + ").");
                } else {
                    for (int b2 = pos; b2 < pos + sub.bits; b2++) {
                        if (used[b2]) { error(plus(sa, "bit"), "Bit field " + k + " (" + where + ") overlaps another bit field at bit " + b2 + "."); break; }
                    }
                    for (int b2 = pos; b2 < pos + sub.bits; b2++) used[b2] = true;
                }
                sub.bitAt = Math.max(0, pos);
                sub.target = str(sm.get("target"));
                if (sub.target != null && sub.target.isEmpty()) sub.target = null;
                if (sm.get("const") != null) sub.constValue = number(sm.get("const"));
                if (sm.get("value") != null && !"".equals(sm.get("value"))) sub.value = exprOrNull(sm.get("value"), plus(sa, "value"), dollars);
                sub.onOverflow = f.onOverflow;
                f.fields.add(sub);
            }
        }
        if ((f.kind == Kind.BCD || f.kind == Kind.PAD) && f.size == null && f.sizeExpr == null) {
            error(plus(a, "size"), f.kind + " needs a size.");
        }
        if (f.kind == Kind.HEXNUM && f.size == null && f.sizeExpr == null && f.terminator == null) {
            error(plus(a, "size"), "HEXNUM needs a size (characters).");
        }
        if (!item && f.name == null && f.constValue == null && f.kind != Kind.PAD && f.checksum == null && f.target == null) {
            error(plus(a, "name"), "A field needs a name unless it is const, PAD or carries a target.");
        }
        return f;
    }

    private Object constOf(FieldSpec f, Object v, List<Object> a) {
        switch (f.kind) {
            case TEXT: return v instanceof String ? v : String.valueOf(v);
            case BYTES: {
                byte[] b = v instanceof String ? Values.parseHex((String) v) : null;
                if (b == null) error(a, "A BYTES const is hex text such as \"02 03\".");
                return b;
            }
            case STRUCT: case BITS:
                error(a, "A structure cannot have a const.");
                return null;
            default: {
                Object n = number(v);
                if (n == null) error(a, "This const must be a number or hex text such as \"0x02\".");
                return n;
            }
        }
    }

    // ── 시험 ─────────────────────────────────────────────────────────

    private void tests() {
        List<Object> ts = list(doc.get("tests"));
        for (int i = 0; i < ts.size(); i++) {
            List<Object> a = at("tests", i);
            Map<String, Object> t = map(ts.get(i));
            TestCase c = new TestCase();
            c.index = i;
            c.name = str(t.get("name")) != null ? str(t.get("name")) : "test " + i;
            c.params.putAll(map(t.get("params")));
            for (Map.Entry<String, Object> e : map(t.get("data")).entrySet()) {
                Object n = number(e.getValue());
                if (n == null) { error(plus(a, "data"), "Server Data value of \"" + e.getKey() + "\" must be a number."); continue; }
                c.data.put(e.getKey(), ((Number) n).doubleValue());
            }
            c.send = t.get("send") instanceof String ? Values.parseHex((String) t.get("send")) : null;
            if (c.send == null) error(plus(a, "send"), "Send must be hex bytes such as \"01 03 00 00\".");
            if (t.get("expect") != null) {
                c.expect = t.get("expect") instanceof String ? Values.parseHex((String) t.get("expect")) : null;
                if (c.expect == null) error(plus(a, "expect"), "Expect must be hex bytes, or no reply.");
            }
            for (Map.Entry<String, Object> e : map(t.get("expectWrites")).entrySet()) {
                Object n = number(e.getValue());
                if (n == null) { error(plus(a, "expectWrites"), "Expected value of \"" + e.getKey() + "\" must be a number."); continue; }
                c.expectWrites.put(e.getKey(), ((Number) n).doubleValue());
            }
            def.tests.add(c);
        }
    }

    /** 문서에 글자로 나온 타겟도 목록에 넣는다 (9.1). 선언에 없으면 경고 */
    private void literalTargets() {
        for (MessageSpec m : def.rx) walk(m);
        for (MessageSpec m : def.tx.values()) walk(m);
    }

    private void walk(MessageSpec m) {
        for (String r : new String[]{"header", "body", "footer"}) for (FieldSpec f : m.region(r)) walk(f);
    }

    private void walk(FieldSpec f) {
        if (f.target != null && def.targetInfo(f.target) == null) {
            TargetInfo t = new TargetInfo();
            t.target = f.target;
            t.spec = f;
            def.catalog.put(f.target, t);
            warn(plus(f.at, "target"), "Target \"" + f.target + "\" is not declared in Targets.");
        }
        for (FieldSpec c : f.fields) walk(c);
    }

    /**
     * 타겟을 실은 필드마다: 표현이 수를 나르는가, 선언한 값과 맞는가 (형식 8.3).
     * 값을 선언하지 않은 이름 타겟은 처음 실은 필드의 표현에서 값을 추론하고, 필드마다 다르면 경고한다.
     */
    private void targetValues() {
        Map<String, String> seen = new HashMap<>();
        for (MessageSpec m : def.rx) for (String r : new String[]{"header", "body", "footer"}) for (FieldSpec f : m.region(r)) checkValue(f, seen);
        for (MessageSpec m : def.tx.values()) for (String r : new String[]{"header", "body", "footer"}) for (FieldSpec f : m.region(r)) checkValue(f, seen);
    }

    private void checkValue(FieldSpec f, Map<String, String> seen) {
        for (FieldSpec c : f.fields) checkValue(c, seen);
        if (f.target == null) return;
        String carried = Definition.valueOf(f);
        if (carried == null) {
            error(plus(f.at, "target"), "Field " + label(f) + " is " + f.kind + " — a target holds a number, so give the field a number type.");
            return;
        }
        TargetInfo t = def.targetInfo(f.target);
        if (t == null) return;
        if (t.value != null) {
            boolean ok = "float".equals(t.value) || !"float".equals(carried);
            if (!ok) error(plus(f.at, "target"), "Target \"" + f.target + "\" is " + t.value + ", but field " + label(f)
                    + " carries a fractional value (scale, offset, decimals or FP). Declare the target as float or change the field.");
            return;
        }
        if (t.declared && t.spec == null) t.spec = f;    // 이름 타겟: 처음 실은 필드에서 값을 추론
        String before = seen.putIfAbsent(f.target, carried);
        if (before != null && !before.equals(carried)) {
            warn(plus(f.at, "target"), "Target \"" + f.target + "\" is carried as " + carried + " here and as " + before
                    + " elsewhere. Declare its value (binary, integer or float) in Targets.");
        }
    }

    // ── 값 읽기 ──────────────────────────────────────────────────────

    private Expr exprOrNull(Object v, List<Object> a, Set<String> allowedDollars) {
        if (v == null) return null;
        String src = v instanceof Number || v instanceof Boolean ? String.valueOf(v) : v instanceof String ? (String) v : null;
        if (src == null) { error(a, "This must be an expression."); return null; }
        try {
            return Parser.parse(src, allowedDollars);
        } catch (Parser.SyntaxError e) {
            error(a, e.getMessage());
            return null;
        }
    }

    /** 필드·비트 필드 이름: 식별자이고 예약어가 아니어야 한다. 없으면(null) 검사하지 않는다 */
    private void checkFieldName(String name, List<Object> at, String what) {
        if (name == null) return;
        if (!isIdent(name)) error(at, what + " name \"" + name + "\" is not a valid name.");
        else if (RESERVED_FIELD_NAMES.contains(name)) error(at, what + " name \"" + name + "\" is reserved; choose another name.");
    }

    private static boolean isIdent(String s) { return s != null && IDENT.matcher(s).matches(); }

    private static String label(FieldSpec f) {
        if (f.name != null) return "\"" + f.name + "\"";
        return f.target != null ? "for \"" + f.target + "\"" : "(unnamed)";
    }

    /** 숫자형의 표현 형식 (형식 6.1). 크기·바이트 순서는 속성이다 */
    static final Set<String> NUMBER_TYPES = new HashSet<>(Arrays.asList("UINT", "INT", "FLOAT"));
    static final Set<String> BYTE_ORDERS = new HashSet<>(Arrays.asList("big", "little", "word-swap", "byte-swap"));

    /** size: 정수 1·2·3·4·8, 실수 4·8. byteOrder: 2·3 바이트는 big·little, 4·8 바이트는 넷 다 */
    private void numberEncoding(FieldSpec f, Map<String, Object> m, List<Object> a) {
        boolean real = f.isReal();
        if (f.size == null) {
            error(plus(a, "size"), f.num + " needs a size in bytes (" + (real ? "4 or 8" : "1, 2, 3, 4 or 8") + ").");
            f.size = real ? 4 : 2;
        } else if (real ? (f.size != 4 && f.size != 8) : (f.size < 1 || f.size > 8 || f.size > 4 && f.size < 8)) {
            error(plus(a, "size"), f.num + " size must be " + (real ? "4 or 8" : "1, 2, 3, 4 or 8") + " bytes, not " + f.size + ".");
            f.size = real ? 4 : 2;
        }
        String order = str(m.get("byteOrder"));
        if (order == null || order.isEmpty()) order = "big";
        if (!BYTE_ORDERS.contains(order)) {
            error(plus(a, "byteOrder"), "Byte order must be big, little, word-swap or byte-swap.");
            order = "big";
        } else if (f.size < 4 && (order.equals("word-swap") || order.equals("byte-swap"))) {
            error(plus(a, "byteOrder"), order + " needs 4 or 8 bytes; a " + f.size + "-byte number is big or little.");
            order = "big";
        }
        f.byteOrder = f.size == 1 ? "big" : order;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.emptyList();
    }

    private static String str(Object o) { return o == null ? null : o instanceof String ? (String) o : String.valueOf(o); }

    /** 수: JSON 수 또는 "0x02" / "12" / "1.5" 글자. 정수는 Long */
    static Object number(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof java.math.BigInteger) return ((Number) v).longValue();
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            String s = ((String) v).trim();
            try {
                if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
                if (s.startsWith("0b") || s.startsWith("0B")) return Long.parseLong(s.substring(2), 2);
                if (s.matches("[+-]?\\d+")) return Long.parseLong(s);
                if (s.matches("[+-]?\\d*\\.\\d+([eE][+-]?\\d+)?")) return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer intLiteral(Object v) {
        Object n = number(v);
        if (n instanceof Long) return ((Long) n).intValue();
        if (n instanceof Double && (Double) n == Math.rint((Double) n)) return (int) (double) (Double) n;
        return null;
    }

    private static int intOr(Object v, int fallback) {
        Integer n = intLiteral(v);
        return n == null ? fallback : n;
    }

    private static long longOr(Object v, long fallback) {
        Object n = number(v);
        if (n instanceof Long) return (Long) n;
        if (n instanceof Double) return (long) (double) (Double) n;
        return fallback;
    }

    private static double dbl(Object v, double fallback) {
        Object n = number(v);
        return n == null ? fallback : ((Number) n).doubleValue();
    }
}
