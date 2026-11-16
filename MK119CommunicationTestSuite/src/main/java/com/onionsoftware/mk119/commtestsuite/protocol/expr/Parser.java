package com.onionsoftware.mk119.commtestsuite.protocol.expr;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 식 파서 (형식 설계서 6절). 우선순위 오름(Pratt) 방식이다.
 * <pre>
 *   순위 (위가 먼저 묶임)
 *   a.b  a[i]  f(…)
 *   단항 -  !  ~
 *   *  /  //  %
 *   +  -
 *   &lt;&lt;  &gt;&gt;
 *   &lt;  &lt;=  &gt;  &gt;=  in
 *   ==  !=
 *   &amp;   ^   |   &amp;&amp;   ||   c ? a : b
 * </pre>
 * 문법 오류·모르는 함수·인자 수·모르는 $이름은 {@link SyntaxError} — 저장을 거부하는 컴파일 오류다.
 */
public final class Parser {
    public static final class SyntaxError extends Exception {
        public SyntaxError(String message) { super(message, null, false, false); }
    }

    /** 함수 이름 → {최소, 최대} 인자 수 */
    static final Map<String, int[]> FUNCTIONS = new HashMap<>();
    static {
        String[][] table = {
                {"data", "1", "1"}, {"read", "3", "3"}, {"fits", "3", "3"}, {"writable", "3", "3"}, {"slots", "1", "1"},
                {"valid", "1", "1"}, {"sizeof", "1", "1"}, {"has", "1", "1"}, {"len", "1", "1"},
                {"min", "2", "2"}, {"max", "2", "2"}, {"abs", "1", "1"}, {"round", "1", "1"}, {"floor", "1", "1"}, {"ceil", "1", "1"},
                {"int", "1", "1"}, {"real", "1", "1"}, {"text", "1", "1"}, {"hex", "1", "2"}, {"substr", "2", "3"},
                {"now", "0", "1"}, {"bytes", "1", "1"},
                {"find", "2", "2"}, {"pack", "2", "3"}, {"unpack", "1", "2"}, {"any", "2", "2"}, {"all", "2", "2"},
        };
        for (String[] r : table) FUNCTIONS.put(r[0], new int[]{Integer.parseInt(r[1]), Integer.parseInt(r[2])});
    }

    private final String src;
    private final Set<String> dollars;
    private int pos;

    private Parser(String src, Set<String> dollars) {
        this.src = src;
        this.dollars = dollars;
    }

    /**
     * @param dollars 쓸 수 있는 $이름 (consts·params). null 이면 검사하지 않는다
     */
    public static Expr parse(String source, Set<String> dollars) throws SyntaxError {
        if (source == null || source.trim().isEmpty()) throw new SyntaxError("The expression is empty.");
        Parser p = new Parser(source, dollars);
        Expr e = p.expr(0);
        p.skipSpace();
        if (p.pos < p.src.length()) throw p.error("Unexpected \"" + p.src.charAt(p.pos) + "\"");
        e.source = source;
        return e;
    }

    // ── 연산자 ───────────────────────────────────────────────────────

    private static final String[][] LEVELS = {
            {"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<=", ">=", "<", ">", "in"}, {"<<", ">>"}, {"+", "-"}, {"*", "//", "/", "%"},
    };
    private static final int TERNARY = 0, UNARY = LEVELS.length + 1;

    /** level 이상으로 묶이는 식 */
    private Expr expr(int level) throws SyntaxError {
        if (level == TERNARY) {
            Expr c = expr(1);
            skipSpace();
            if (eat("?")) {
                Expr a = expr(TERNARY);
                skipSpace();
                if (!eat(":")) throw error("Expected \":\" in \"c ? a : b\"");
                Expr b = expr(TERNARY);
                return mark(new Expr.Ternary(c, a, b));
            }
            return c;
        }
        if (level == UNARY) return unary();
        Expr left = expr(level + 1);
        while (true) {
            skipSpace();
            String op = binaryOp(LEVELS[level - 1]);
            if (op == null) return left;
            Expr right = expr(level + 1);
            left = mark(new Expr.Binary(op, left, right));
        }
    }

    /** 이 순위의 연산자 하나를 먹는다. 더 긴 연산자(<<, ||, //, ==)와 헷갈리지 않게 본다 */
    private String binaryOp(String[] ops) {
        for (String op : ops) {
            if (!src.startsWith(op, pos)) continue;
            String next = pos + op.length() < src.length() ? String.valueOf(src.charAt(pos + op.length())) : "";
            if (op.equals("|") && next.equals("|")) continue;
            if (op.equals("&") && next.equals("&")) continue;
            if ((op.equals("<") || op.equals(">")) && (next.equals("<") || next.equals(">") || next.equals("="))) continue;
            if (op.equals("/") && next.equals("/")) continue;
            if (op.equals("in") && (next.matches("[A-Za-z0-9_]") || isIdentChar(next))) continue;
            pos += op.length();
            return op;
        }
        return null;
    }

    private Expr unary() throws SyntaxError {
        skipSpace();
        if (eat("-")) return mark(new Expr.Unary("-", unary()));
        if (src.startsWith("!", pos) && !src.startsWith("!=", pos)) { pos++; return mark(new Expr.Unary("!", unary())); }
        if (eat("~")) return mark(new Expr.Unary("~", unary()));
        return postfix(primary());
    }

    private Expr postfix(Expr e) throws SyntaxError {
        while (true) {
            skipSpace();
            if (src.startsWith(".", pos) && e instanceof Expr.Path) {
                pos++;
                ((Expr.Path) e).parts.add(identifier("a field name after \".\""));
            } else if (src.startsWith("[", pos) && e instanceof Expr.Path) {
                pos++;
                Expr index = expr(TERNARY);
                skipSpace();
                if (!eat("]")) throw error("Expected \"]\"");
                ((Expr.Path) e).parts.add(index);
            } else {
                return e;
            }
        }
    }

    private Expr primary() throws SyntaxError {
        skipSpace();
        if (pos >= src.length()) throw error("The expression ends too early");
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            Expr e = expr(TERNARY);
            skipSpace();
            if (!eat(")")) throw error("Expected \")\"");
            return e;
        }
        if (c == '[') {
            pos++;
            List<Expr> items = new ArrayList<>();
            skipSpace();
            if (!eat("]")) {
                do { items.add(expr(TERNARY)); skipSpace(); } while (eat(","));
                if (!eat("]")) throw error("Expected \"]\"");
            }
            return mark(new Expr.ListLit(items));
        }
        if (c == '\'') return mark(new Expr.Lit(string()));
        if ((c == 'x' || c == 'X') && pos + 1 < src.length() && src.charAt(pos + 1) == '\'') {
            pos++;
            String h = string();
            if (!h.matches("[0-9A-Fa-f\\s]*") || h.replaceAll("\\s", "").length() % 2 != 0) throw error("x'…' must hold pairs of hex digits");
            return mark(new Expr.Lit(com.onionsoftware.mk119.commtestsuite.protocol.Values.parseHex(h)));
        }
        if (Character.isDigit(c)) return mark(new Expr.Lit(number()));
        if (c == '$') {
            pos++;
            String name = identifier("a name after \"$\"");
            if (dollars != null && !dollars.contains(name)) throw error("$" + name + " is not a constant or a parameter");
            return mark(new Expr.Dollar(name));
        }
        if (isIdentStart(c)) {
            String name = identifier("a name");
            if (name.equals("true")) return mark(new Expr.Lit(Boolean.TRUE));
            if (name.equals("false")) return mark(new Expr.Lit(Boolean.FALSE));
            skipSpace();
            if (src.startsWith("(", pos)) return call(name);
            return mark(new Expr.Path(name));
        }
        throw error("Unexpected \"" + c + "\"");
    }

    private Expr call(String name) throws SyntaxError {
        int[] arity = FUNCTIONS.get(name);
        if (arity == null) throw error("Unknown function " + name + "()");
        pos++;   // (
        List<Expr> args = new ArrayList<>();
        skipSpace();
        if (!eat(")")) {
            do { args.add(expr(TERNARY)); skipSpace(); } while (eat(","));
            if (!eat(")")) throw error("Expected \")\" after the arguments of " + name + "()");
        }
        if (args.size() < arity[0] || args.size() > arity[1]) {
            throw error(name + "() takes " + (arity[0] == arity[1] ? String.valueOf(arity[0]) : arity[0] + " to " + arity[1])
                    + (arity[1] == 1 ? " argument" : " arguments") + ", not " + args.size());
        }
        if ((name.equals("sizeof") || name.equals("has")) && !(args.get(0) instanceof Expr.Path)) {
            throw error(name + "() takes a field path such as body.values");
        }
        return mark(new Expr.Call(name, args));
    }

    // ── 낱말 ─────────────────────────────────────────────────────────

    private Object number() throws SyntaxError {
        int start = pos;
        if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
            pos += 2;
            while (pos < src.length() && Character.digit(src.charAt(pos), 16) >= 0) pos++;
            if (pos == start + 2) throw error("0x needs hex digits");
            return Long.parseLong(src.substring(start + 2, pos), 16);
        }
        if (src.startsWith("0b", pos) || src.startsWith("0B", pos)) {
            pos += 2;
            while (pos < src.length() && (src.charAt(pos) == '0' || src.charAt(pos) == '1')) pos++;
            if (pos == start + 2) throw error("0b needs binary digits");
            return Long.parseLong(src.substring(start + 2, pos), 2);
        }
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        boolean real = false;
        if (pos + 1 < src.length() && src.charAt(pos) == '.' && Character.isDigit(src.charAt(pos + 1))) {
            real = true;
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            int save = pos;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                real = true;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            } else {
                pos = save;
            }
        }
        String t = src.substring(start, pos);
        try {
            return real ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw error("\"" + t + "\" is not a number");
        }
    }

    /** '…' — \' \\ \r \n \t \xHH */
    private String string() throws SyntaxError {
        pos++;   // '
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw error("The text is missing its closing '");
            char c = src.charAt(pos++);
            if (c == '\'') break;
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= src.length()) throw error("The text ends after \\");
            char e = src.charAt(pos++);
            switch (e) {
                case 'r': sb.append('\r'); break;
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case '\\': sb.append('\\'); break;
                case '\'': sb.append('\''); break;
                case 'x':
                    if (pos + 2 > src.length()) throw error("\\x needs two hex digits");
                    try { sb.append((char) Integer.parseInt(src.substring(pos, pos + 2), 16)); }
                    catch (NumberFormatException ex) { throw error("\\x needs two hex digits"); }
                    pos += 2;
                    break;
                default: throw error("Unknown escape \\" + e);
            }
        }
        return sb.toString();
    }

    private String identifier(String what) throws SyntaxError {
        skipSpace();
        if (pos >= src.length() || !isIdentStart(src.charAt(pos))) throw error("Expected " + what);
        int start = pos;
        while (pos < src.length() && isIdentChar(String.valueOf(src.charAt(pos)))) pos++;
        return src.substring(start, pos);
    }

    static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentChar(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private void skipSpace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private boolean eat(String t) {
        skipSpace();
        if (src.startsWith(t, pos)) { pos += t.length(); return true; }
        return false;
    }

    private <T extends Expr> T mark(T e) {
        e.source = src;
        return e;
    }

    private SyntaxError error(String message) {
        return new SyntaxError(message + " at position " + (pos + 1) + " of \"" + src + "\".");
    }

    /** 글자 리터럴을 바이트로 (식에서 'AB' 를 바이트처럼 쓸 때) */
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
