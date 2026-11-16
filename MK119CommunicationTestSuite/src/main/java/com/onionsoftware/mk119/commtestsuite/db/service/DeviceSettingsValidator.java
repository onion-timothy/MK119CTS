package com.onionsoftware.mk119.commtestsuite.db.service;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition;
import com.onionsoftware.mk119.commtestsuite.protocol.DefinitionRegistry;
import com.onionsoftware.mk119.commtestsuite.protocol.Values;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 장비 통신 설정(communicationSettings) 검사.
 * <p>
 * 기준은 장비 편집기다. 규칙과 문구가 {@code static/js/cts-device-form.js}(설정 칸) ·
 * 의 problems·definitionProblems 와 같다 — 고칠 때 함께 고친다.
 * 둘이 같은 답을 내는지는 {@code src/test/resources/device-settings-cases.json} 을
 * JUnit(DeviceSettingsValidatorTest)과 JXA(tools/check-device-settings.sh)가 같이 돌려 본다.
 * <p>
 * 값은 JS 가 보는 그대로 본다. JSON 숫자 1 은 Gson·Jackson 이 1.0 으로 읽지만 JS 의 String(1) 은 "1" 이다.
 */
public final class DeviceSettingsValidator {

    private DeviceSettingsValidator() {}

    private static final long INT_MAX = Integer.MAX_VALUE;

    /** 설정 칸 하나. options 는 [값, 화면 글] 쌍 */
    private static final class Field {
        final String key, label;
        Long min, max;
        String[][] options;
        boolean ipv4;

        Field(String key, String label) { this.key = key; this.label = label; }
        Field range(long min, long max) { this.min = min; this.max = max; return this; }
        Field options(String[]... o) { this.options = o; return this; }
        Field ipv4() { this.ipv4 = true; return this; }
    }

    /** 프로토콜 정의를 가리키는 프로토콜 (cts-device-form.js 의 PROTOCOLS[].definition). GENERIC 은 반드시, RCU 는 RS 를 쓸 때만 */
    private static final Set<String> DEFINITION_PROTOCOLS = new HashSet<>(Arrays.asList("GENERIC", "DAEYUN_RCU"));

    /** cts-device-form.js 의 SETTINGS 와 같은 표 */
    private static final Map<String, List<Field>> SETTINGS = new HashMap<>();
    static {
        SETTINGS.put("MODBUS", Arrays.asList(
                new Field("unitId", "Unit ID").range(0, 255),
                new Field("packetType", "Packet type").options(new String[]{"0", "Modbus-RTU"}, new String[]{"1", "Modbus-TCP"}),
                new Field("registers", "Registers").range(1, INT_MAX)));
        SETTINGS.put("XGT_PLC", Arrays.asList(
                new Field("cpuId", "CPU ID").range(0, 255),
                new Field("maxBlockSize", "Max block size").range(1, 1400),
                new Field("registers", "Registers").range(1, INT_MAX)));
        SETTINGS.put("DAEYUN_RCU", Arrays.asList(
                new Field("cpuAddr", "CPU address").range(0, 255),
                new Field("diCount", "DI channels").range(1, 32),
                new Field("doCount", "DO channels").range(1, 32),
                new Field("aiCount", "AI channels").range(1, 8)));
        SETTINGS.put("SNMP", Arrays.asList(
                new Field("snmpVersion", "Version").options(new String[]{"0", "SNMPv1"}, new String[]{"1", "SNMPv2"}),
                new Field("getCommunity", "Get community"),
                new Field("setCommunity", "Set community")));
        SETTINGS.put("BACNET", Arrays.asList(
                new Field("deviceInstanceId", "Device instance").range(1, 65536),
                new Field("broadcast", "Broadcast address")));
        SETTINGS.put("OPC_AGENT", Collections.singletonList(
                new Field("registers", "Tags").range(1, INT_MAX)));
        SETTINGS.put("DB", Arrays.asList(
                new Field("dbdriver", "Driver"),
                new Field("dbUrl", "URL"),
                new Field("dbUsername", "Username"),
                new Field("dbPassword", "Password"),
                new Field("dbDatabase", "Database")));
        SETTINGS.put("ETHERNET_IP", Arrays.asList(
                new Field("cpuSlot", "CPU slot").range(0, 255),
                // EthernetIPData 가 Short.parseShort 로 읽어서 상한이 32767 이다
                new Field("syn_port", "Implicit SYN port").range(0, 32767),
                new Field("implicit_t2o_addr", "T→O address").ipv4(),
                new Field("implicit_t2o_assy_size", "T→O assembly size").range(0, INT_MAX),
                new Field("implicit_o2t_assy_size", "O→T assembly size").range(0, INT_MAX)));
    }

    private static final Pattern WHOLE = Pattern.compile("^-?\\d+$");
    private static final Pattern OCTET = Pattern.compile("^\\d{1,3}$");

    // ── 진입점 ───────────────────────────────────────────────

    /** 첫 문제. 문제가 없으면 null. 모르는 프로토콜은 여기서 보지 않는다(행 검사가 따로 막는다) */
    public static String problem(String protocol, Object raw) {
        List<String> all = problems(protocol, raw);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 칸 순서대로 모든 문제. 설정 칸 → 프로토콜 정의·파라미터 */
    public static List<String> problems(String protocol, Object raw) {
        Map<?, ?> cs = settingsOf(raw);
        if (cs == null) return Collections.singletonList("Communication settings must be a JSON object.");
        List<String> out = new ArrayList<>();
        for (Field f : SETTINGS.getOrDefault(protocol, Collections.emptyList())) {
            String m = problemOf(f, cs.get(f.key));
            if (m != null) out.add(m);
        }
        if (DEFINITION_PROTOCOLS.contains(protocol)) definitionProblems(protocol, cs, out);
        return out;
    }

    /** 비어 있으면 빈 설정, JSON 객체 글자면 풀어서, 그 밖이면 null — batchAddDevices 의 settingsOf */
    private static Map<?, ?> settingsOf(Object raw) {
        if (raw == null || raw.toString().trim().isEmpty()) return Collections.emptyMap();
        if (raw instanceof Map) return (Map<?, ?>) raw;
        if (raw instanceof String) {
            try {
                Object p = new Gson().fromJson((String) raw, Object.class);
                if (p instanceof Map) return (Map<?, ?>) p;
            } catch (Exception ignored) { /* 아래 null */ }
        }
        return null;
    }

    // ── 설정 칸 ──────────────────────────────────────────────

    private static String problemOf(Field f, Object value) {
        String v = value == null ? "" : js(value);
        String t = v.trim();
        if (t.isEmpty()) return f.label + " cannot be empty.";
        if (f.options != null) {
            for (String[] o : f.options) if (o[0].equals(t)) return null;
            return f.label + " must be " + optionText(f) + ".";
        }
        if (f.min != null) {
            boolean ok = WHOLE.matcher(t).matches();
            if (ok) {
                BigInteger n = new BigInteger(t);
                ok = n.compareTo(BigInteger.valueOf(f.min)) >= 0 && n.compareTo(BigInteger.valueOf(f.max)) <= 0;
            }
            if (!ok) return f.label + " must be a whole number from " + f.min + " to " + f.max + ".";
        }
        if (f.ipv4 && !isIpv4(v)) return f.label + " must be an IPv4 address.";
        return null;
    }

    private static String optionText(Field f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.options.length; i++) {
            if (i > 0) sb.append(i == f.options.length - 1 ? " or " : ", ");
            sb.append(f.options[i][1]);
        }
        return sb.toString();
    }

    private static boolean isIpv4(String v) {
        String[] parts = v.trim().split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (!OCTET.matcher(p).matches() || Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    // ── 프로토콜 정의 ────────────────────────────────────────
    // cts-device-form.js 의 definitionProblems 와 같은 순서·문구

    /**
     * 프로토콜 정의와 파라미터 (docs/PROTOCOL_ENGINE_DESIGN.md 10.1).
     * GENERIC 은 정의가 반드시 있어야 하고, RCU 는 RS(시리얼 중계)를 쓸 때만 고른다.
     * 정의는 저장된 것이어야 하고 컴파일되어야 한다. 파라미터는 정의의 타입·범위를 따른다(빈 칸은 기본값).
     */
    private static void definitionProblems(String protocol, Map<?, ?> cs, List<String> out) {
        Object raw = cs.get("definitionId");
        String idText = raw == null ? "" : js(raw).trim();
        if (idText.isEmpty()) {
            if ("GENERIC".equals(protocol)) out.add("Choose a protocol definition.");
            return;
        }
        if (!WHOLE.matcher(idText).matches()) { out.add("Protocol definition must be the id of a saved definition."); return; }
        DefinitionRegistry registry = DefinitionRegistry.current();
        if (registry == null) return;   // 앱 밖(단위 시험)
        Definition def = registry.get(Integer.valueOf(idText));
        if (def == null) { out.add("Protocol definition " + idText + " does not exist or does not compile."); return; }
        Map<?, ?> params = cs.get("params") instanceof Map ? (Map<?, ?>) cs.get("params") : Collections.emptyMap();
        for (Object k : params.keySet()) {
            if (!def.params.containsKey(String.valueOf(k))) out.add(k + " is not a parameter of \"" + def.name + "\".");
        }
        for (Definition.Param p : def.params.values()) {
            Object v = params.get(p.name);
            String t = v == null ? "" : js(v).trim();
            if (t.isEmpty()) {
                if (p.defaultValue == null) out.add("Parameter " + p.name + " needs a value.");
                continue;
            }
            String m = paramProblem(p, t);
            if (m != null) out.add(m);
        }
    }

    /** 파라미터 값 하나. integer 면 정수, 선언의 min·max 안 (형식 11) */
    static String paramProblem(Definition.Param p, String t) {
        // cts-device-form.js 의 paramProblem 과 같은 모양만 수로 본다 (Infinity·NaN·0x… 는 아니다)
        if (!t.matches("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")) return "Parameter " + p.name + " must be a number.";
        double v = Double.parseDouble(t);
        if (!p.isReal() && v != Math.rint(v)) return "Parameter " + p.name + " must be a whole number.";
        double lo = p.min != null ? p.min : -Double.MAX_VALUE, hi = p.max != null ? p.max : Double.MAX_VALUE;
        if (v < lo || v > hi) {
            if (p.min != null && p.max != null) return "Parameter " + p.name + " must be from " + Values.fmt(lo) + " to " + Values.fmt(hi) + ".";
            return "Parameter " + p.name + " must be " + (p.min != null ? "at least " + Values.fmt(lo) : "at most " + Values.fmt(hi)) + ".";
        }
        return null;
    }

    // ── JS 와 같게 보기 ──────────────────────────────────────

    /** JS 의 String(v). 정수인 숫자는 소수점 없이, 배열은 쉼표로 이어서([] 은 빈 글자), 객체는 "[object Object]" */
    static String js(Object v) {
        if (v == null) return "null";
        if (v instanceof List) {
            StringJoiner sj = new StringJoiner(",");
            for (Object o : (List<?>) v) sj.add(o == null ? "" : js(o));
            return sj.toString();
        }
        if (v instanceof Map) return "[object Object]";
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e21) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    /** null 이면 빈 글자 */
    private static String str(Object v) {
        return v == null ? "" : js(v);
    }

}
