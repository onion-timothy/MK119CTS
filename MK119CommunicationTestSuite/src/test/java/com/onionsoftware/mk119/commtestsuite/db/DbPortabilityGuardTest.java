package com.onionsoftware.mk119.commtestsuite.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 한쪽 DB 에서만 되는 SQL·매핑이 소스에 들어오지 않게 막는다. 앱은 MariaDB 와 SQL Server 에서 똑같이 돌아야 한다.
 * DB 없이 소스만 읽으므로 기본 test 에서 늘 돈다. 실제 두 DB 에서의 동작은 DbPortabilityIT(./gradlew dbTest)가 본다.
 * <ul>
 *   <li>@Column(columnDefinition = …) 금지 — DB 타입 이름을 적으면 다른 DB 에서 테이블이 안 만들어진다(예전 LONGTEXT)</li>
 *   <li>문자열 안의 백틱은 @Column(name = "`예약어`") 에만 — Hibernate 가 DB 에 맞는 따옴표로 바꾸는 자리다.
 *       직접 쓰는 SQL 의 백틱은 MariaDB 전용이다</li>
 *   <li>한쪽 DB 전용 함수·구문 금지(DATABASE(), LIMIT n, IFNULL, ISNULL, GETDATE, TOP n, AUTO_INCREMENT …)</li>
 * </ul>
 * 한쪽 전용 SQL 이 꼭 필요하면 DbKind 로 갈라 한 파일에 모으고 {@link #DIALECT_FILES} 에 올린다.
 */
class DbPortabilityGuardTest {

    private static final Path MAIN = Paths.get("src/main/java");

    /** DB 하나 전용임을 알고 쓰는 파일 */
    private static final Set<String> DIALECT_FILES = new HashSet<>(Arrays.asList(
            "SqlServerUnicodeColumns.java",   // SQL Server 의 sys.* 를 읽고 nvarchar 로 바꾼다
            "CtsSqlServerDialect.java"));     // SQL Server 방언

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern COLUMN_NAME_QUOTED = Pattern.compile("@Column\\s*\\(\\s*name\\s*=\\s*\"`[A-Za-z_]+`\"");

    /** 한쪽 DB 전용 SQL. 대소문자 무관 */
    private static final Map<String, Pattern> FORBIDDEN = new LinkedHashMap<>();
    static {
        FORBIDDEN.put("DATABASE() (MariaDB)", Pattern.compile("\\bDATABASE\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("LIMIT n (MariaDB)", Pattern.compile("\\bLIMIT\\s+(\\d|\\?)", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("IFNULL (MariaDB)", Pattern.compile("\\bIFNULL\\s*\\(", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("GROUP_CONCAT (MariaDB)", Pattern.compile("\\bGROUP_CONCAT\\s*\\(", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("ON DUPLICATE KEY (MariaDB)", Pattern.compile("\\bON\\s+DUPLICATE\\s+KEY\\b", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("AUTO_INCREMENT (MariaDB)", Pattern.compile("\\bAUTO_INCREMENT\\b", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("LONGTEXT (MariaDB)", Pattern.compile("\\bLONGTEXT\\b", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("NOW() (MariaDB)", Pattern.compile("\\bNOW\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("ISNULL (SQL Server)", Pattern.compile("\\bISNULL\\s*\\(", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("GETDATE (SQL Server)", Pattern.compile("\\bGETDATE\\s*\\(", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("TOP n (SQL Server)", Pattern.compile("\\bSELECT\\s+TOP\\b", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("IDENT_CURRENT (SQL Server)", Pattern.compile("\\bIDENT_CURRENT\\s*\\(", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("sysobjects / sys.* (SQL Server)", Pattern.compile("\\bsysobjects\\b|\\bsys\\.(tables|columns|indexes|objects|types)\\b", Pattern.CASE_INSENSITIVE));
        FORBIDDEN.put("NVARCHAR (SQL Server)", Pattern.compile("\\bNVARCHAR\\b", Pattern.CASE_INSENSITIVE));
    }

    @Test
    void noDialectSpecificSqlInSources() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path f : javaFiles()) {
            if (DIALECT_FILES.contains(f.getFileName().toString())) continue;
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            boolean inBlockComment = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i), t = line.trim();
                if (inBlockComment) {
                    if (t.contains("*/")) inBlockComment = false;
                    continue;
                }
                if (t.startsWith("/*")) {
                    inBlockComment = !t.contains("*/");
                    continue;
                }
                if (t.startsWith("//") || t.startsWith("*")) continue;
                String code = stripLineComment(line);
                String where = MAIN.relativize(f) + ":" + (i + 1);

                if (code.contains("columnDefinition")) problems.add(where + " columnDefinition: " + t);

                Matcher m = STRING_LITERAL.matcher(code);
                while (m.find()) {
                    String lit = m.group(1);
                    if (lit.contains("`") && !COLUMN_NAME_QUOTED.matcher(code).find()) {
                        problems.add(where + " backtick in a string: " + t);
                    }
                    for (Map.Entry<String, Pattern> e : FORBIDDEN.entrySet()) {
                        if (e.getValue().matcher(lit).find()) problems.add(where + " " + e.getKey() + ": " + t);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "DB 하나에서만 되는 SQL·매핑이 있다 (" + problems.size() + "건):\n"
                + String.join("\n", problems));
    }

    @Test
    void noMyBatisMappers() throws IOException {
        List<String> found;
        try (Stream<Path> s = Files.walk(Paths.get("src/main/resources"))) {
            found = s.filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> {
                        try {
                            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains("<mapper");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString).collect(Collectors.toList());
        }
        assertTrue(found.isEmpty(), "MyBatis 매퍼 XML 은 지웠다. DB 접근은 JPA·JdbcTemplate 로 한다: " + found);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    /** 문자열 밖의 // 주석을 뗀다 */
    private static String stripLineComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '\\' && inString) {
                i++;
                continue;
            }
            if (c == '"') inString = !inString;
            if (!inString && c == '/' && line.charAt(i + 1) == '/') return line.substring(0, i);
        }
        return line;
    }
}
