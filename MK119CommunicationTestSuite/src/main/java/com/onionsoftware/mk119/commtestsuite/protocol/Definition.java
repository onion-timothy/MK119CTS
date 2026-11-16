package com.onionsoftware.mk119.commtestsuite.protocol;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.protocol.expr.Expr;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 컴파일된 프로토콜 정의. {@link Compiler} 가 만들고 바꾸지 않는다 — 여러 장비·연결이 함께 쓴다.
 * 이름은 모두 풀려 있고 식은 파싱되어 있다 (docs/PROTOCOL_ENGINE_DESIGN.md 3절).
 */
public final class Definition {
    public String name;
    public final Map<String, Param> params = new LinkedHashMap<>();
    public final Map<String, Object> consts = new LinkedHashMap<>();
    public final Map<String, ChecksumSpec> checksums = new LinkedHashMap<>();
    public final Map<String, TargetGroup> targets = new LinkedHashMap<>();
    public final List<MessageSpec> rx = new ArrayList<>();
    public final Map<String, MessageSpec> tx = new LinkedHashMap<>();
    public final List<TestCase> tests = new ArrayList<>();

    /** 프레이밍 (형식 10절) */
    public byte[] syncOn;
    public int maxLength = 1024;
    public Integer idleFlushMs;

    /** 어느 rx 에도 맞지 않는 프레임: none / echo / tx 메시지 이름 */
    public String onUnmatched = "none";

    /** 타겟 목록: 선언된 타겟 + 문서에 글자로 나온 타겟 (docs/PROTOCOL_ENGINE_DESIGN.md 9.1) */
    public final Map<String, TargetInfo> catalog = new LinkedHashMap<>();
    public final List<TargetGroup> patterns = new ArrayList<>();

    public Param param(String n) { return params.get(n); }

    /** 이 타겟이 목록에 있거나 패턴에 맞는가 */
    public TargetInfo targetInfo(String target) {
        TargetInfo t = catalog.get(target);
        if (t != null) return t;
        for (TargetGroup g : patterns) {
            if (g.pattern.matcher(target).matches()) {
                TargetInfo p = new TargetInfo();
                p.target = target;
                p.group = g.name;
                p.spec = g.patternSpec;
                p.value = g.patternValue;
                p.writable = g.patternWritable;
                return p;
            }
        }
        return null;
    }

    // ── 조각 ─────────────────────────────────────────────────────────

    public enum Kind { NUM, BCD, TEXT, TEXTNUM, HEXNUM, BYTES, PAD, BITS, STRUCT, BIT }

    /** 장비마다 다른 값 (형식 11). 패킷 모양이 아니라 값이라 표현이 없다 — 값의 종류와 범위만 */
    public static final class Param {
        public String name;
        /** integer(기본) / float */
        public String value = "integer";
        public Object defaultValue;
        public Double min, max;

        public boolean isReal() { return "float".equals(value); }
    }

    /** 필드 하나. 타겟 선언의 항목도 같은 모양을 쓴다 (인코딩 정보) */
    public static final class FieldSpec {
        public String name;
        public Kind kind;
        /** 숫자형(NUM)의 형식: UINT / INT / FLOAT. 바이트 수는 size, 순서는 byteOrder (형식 6.1) */
        public String num;
        /** 숫자형의 바이트 순서: big / little / word-swap / byte-swap */
        public String byteOrder = "big";
        /** 고정 크기 (바이트, HEXNUM 은 글자 수). 식이면 sizeExpr */
        public Integer size;
        public Expr sizeExpr;
        public Integer count;
        public Expr countExpr;
        public byte[] terminator;
        public Expr when, expect, value;
        /** const: 숫자형은 Long/Double, TEXT 는 String, BYTES 는 byte[] */
        public Object constValue;
        public String target;
        public String checksum;
        public double scale = 1, offset = 0;
        public boolean scaled;
        public String onOverflow = "error";
        public String encoding = "ascii";
        public String pad;
        public String align;
        public int decimals;
        public boolean point = true;
        public String sign = "auto";
        public boolean lowerCase;
        public boolean littleBytes;
        public boolean msbFirst;
        public int bits;
        /** 비트 필드의 자리: 값의 맨 아래 비트가 0 (컴파일러가 정한다 — 형식 6.7) */
        public int bitAt = -1;
        public long fill;
        public final List<FieldSpec> fields = new ArrayList<>();
        public final List<Transform> transforms = new ArrayList<>();
        /** 문서 안의 위치 (문제 보고용) */
        public List<Object> at;

        public boolean isArray() { return count != null || countExpr != null; }

        /** 크기만 다른 사본 — size 가 식인 필드를 실행 때 그 크기로 인코딩·디코딩할 때 쓴다 */
        public FieldSpec withSize(int n) {
            FieldSpec c = new FieldSpec();
            c.name = name; c.kind = kind; c.num = num; c.byteOrder = byteOrder; c.size = n; c.sizeExpr = null;
            c.count = count; c.countExpr = countExpr; c.terminator = terminator;
            c.when = when; c.expect = expect; c.value = value; c.constValue = constValue;
            c.target = target; c.checksum = checksum; c.scale = scale; c.offset = offset; c.scaled = scaled;
            c.onOverflow = onOverflow; c.encoding = encoding; c.pad = pad; c.align = align;
            c.decimals = decimals; c.point = point; c.sign = sign; c.lowerCase = lowerCase;
            c.littleBytes = littleBytes; c.msbFirst = msbFirst; c.bits = bits; c.bitAt = bitAt; c.fill = fill;
            c.fields.addAll(fields); c.transforms.addAll(transforms); c.at = at;
            return c;
        }
        public boolean isReal() { return "FLOAT".equals(num); }
        public boolean isSigned() { return !"UINT".equals(num); }
        /** 숫자형의 이름 한 줄 (예: "UINT 2", "INT 4 word-swap") */
        public String numText() { return num + " " + size + ("big".equals(byteOrder) || size == null || size < 2 ? "" : " " + byteOrder); }

        public boolean isContainer() { return kind == Kind.STRUCT || kind == Kind.BITS; }

        /** 고정 폭이면 바이트 수, 아니면 -1 */
        public int fixedWidth() {
            switch (kind) {
                case NUM: return size != null ? size : -1;
                case BCD: case BYTES: case PAD: case BITS: case HEXNUM:
                    return size != null ? size : -1;
                case TEXT: case TEXTNUM:
                    return size != null ? size : -1;
                default:
                    return -1;
            }
        }
    }

    public static final class Transform {
        public String op;      // ascii-hex / add / xor
        public long value;
        public boolean lowerCase;
    }

    public static final class MessageSpec {
        public String name;
        public boolean rx;
        public final List<FieldSpec> header = new ArrayList<>(), body = new ArrayList<>(), footer = new ArrayList<>();
        public Expr bodySize;
        /** 본문 전체에 거는 변환 (형식 6.8). 받을 때 되돌린 뒤 본문 필드를 읽는다 */
        public final List<Transform> bodyTransform = new ArrayList<>();
        /** 푸터의 고정 바이트 수. 정해지지 않으면 -1 — bodySize 없이 본문을 변환할 때 본문 끝을 정한다 */
        public int footerWidth = -1;
        public Integer size;
        public Expr match;
        /** tx: 프레임 필드 값 바꾸기 — 경로("header.function") → 식 */
        public final Map<String, Expr> set = new LinkedHashMap<>();
        public final List<Rule> respond = new ArrayList<>();
        public int index;

        public List<FieldSpec> region(String r) {
            return "header".equals(r) ? header : "body".equals(r) ? body : footer;
        }
    }

    public static final class Rule {
        public String name;
        public int index;
        public Expr when;
        public final List<WriteSpec> writes = new ArrayList<>();
        /** tx 메시지 이름 / none / echo */
        public String reply = "none";
        public final Map<String, Expr> args = new LinkedHashMap<>();
        public long delayMs;

        public String label() { return name != null ? name : "rule " + index; }
    }

    public static final class WriteSpec {
        public String target;
        public Expr value;
        public String map;
        public Expr address, bytes;
        /** 반복 횟수(형식 5.6). 있으면 i = 0..count-1 로 차례로 실행한다 */
        public Expr count;
    }

    public static final class ChecksumSpec {
        public String name;
        public Checksums.Algorithm algorithm;
        public String from, to;
        public boolean overValue;
        public boolean flagOnly;
    }

    public static final class TargetGroup {
        public String name;
        public String kind;   // sequence / named / pattern
        public int base, slotSize = 1;
        public boolean unmappedZero;
        public final List<SeqItem> items = new ArrayList<>();
        public Pattern pattern;
        public FieldSpec patternSpec;
        /** 패턴 타겟의 값: binary / integer / float (형식 8.4). 없으면 모른다 */
        public String patternValue;
        public boolean patternWritable;
        public int totalSlots;
    }

    /** 순차 타겟의 항목: 타겟 / 고정 내용 / 빈 칸 */
    public static final class SeqItem {
        public String target;
        public FieldSpec spec;
        public boolean skip;
        public int address, slots;
        public boolean writable;

        /** 비트 집합 항목 — 값은 비트 필드마다의 타겟이 갖는다 */
        public boolean isBits() { return spec != null && spec.kind == Kind.BITS; }

        /** Server Data 와 이어진 항목인가 (자기 타겟 또는 비트 필드의 타겟) */
        public boolean hasTargets() {
            if (target != null) return true;
            if (!isBits()) return false;
            for (FieldSpec b : spec.fields) if (b.target != null) return true;
            return false;
        }
    }

    public static final class TargetInfo {
        public String target;
        public String group;
        /**
         * 인코딩 정보. 순차 타겟은 선언의 것, 이름 타겟은 그 타겟을 실은 첫 필드의 것(값을 추론할 때만 쓴다).
         * 아무 데서도 쓰지 않으면 null
         */
        public FieldSpec spec;
        /** 선언한 값: binary / integer / float (이름·패턴 타겟, 형식 8.3). 없으면 spec 에서 추론 */
        public String value;
        public boolean writable;
        public boolean declared;

        /** 값의 종류: 선언한 값, 없으면 표현에서 추론 (binary / integer / float, 모르면 null) */
        public String valueKind() { return value != null ? value : valueOf(spec); }

        /**
         * 이 타겟에 등록할 수 있는 Value Simulation data type (형식 설계서 5.9). 타입을 모르면 null.
         *   정수형(UI·SI·BCD·HEXNUM·소수 없는 TEXTNUM), scale 없음 → INTEGER
         *   위와 같지만 scale·offset·소수가 있거나 FP → INTEGER, FLOAT
         *   1비트(BIT) → BINARY, INTEGER. 여러 비트짜리 비트 필드 → INTEGER
         */
        public List<DataType> dataTypes() {
            String v = valueKind();
            if (v == null) return null;
            switch (v) {
                case "binary": return Arrays.asList(DataType.BINARY, DataType.INTEGER);
                case "integer": return Collections.singletonList(DataType.INTEGER);
                default: return Arrays.asList(DataType.INTEGER, DataType.FLOAT);
            }
        }
    }

    /** 값의 이름 (형식 8.3) */
    public static final List<String> VALUES = Collections.unmodifiableList(Arrays.asList("binary", "integer", "float"));

    /**
     * 표현이 나르는 값의 종류.
     *   1비트(BIT) → binary, 여러 비트 → integer
     *   정수형(UI·SI·BCD·HEXNUM·소수 없는 TEXTNUM), scale 없음 → integer
     *   그 밖의 수(FP·scale·offset·소수) → float
     *   글자·바이트·구조체 → null (수가 아니다)
     */
    public static String valueOf(FieldSpec s) {
        if (s == null) return null;
        switch (s.kind) {
            // 여러 비트짜리 비트 필드(예: 3비트 모드)는 0/1 이 아니다
            case BIT: return s.bits > 1 ? "integer" : "binary";
            case NUM: return s.isReal() || s.scaled ? "float" : "integer";
            case BCD: case HEXNUM: return s.scaled ? "float" : "integer";
            case TEXTNUM: return s.scaled || s.decimals > 0 ? "float" : "integer";
            default: return null;
        }
    }

    public static final class TestCase {
        public String name;
        public final Map<String, Object> params = new LinkedHashMap<>();
        public final Map<String, Double> data = new LinkedHashMap<>();
        public byte[] send;
        public byte[] expect;
        public final Map<String, Double> expectWrites = new LinkedHashMap<>();
        public int index;
    }
}
