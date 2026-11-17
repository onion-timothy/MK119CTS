package com.onionsoftware.mk119.commtestsuite.db.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SQL Server 전용. 앱 테이블의 varchar·char·text 열을 nvarchar·nchar 로 바꾼다.
 * <p>
 * {@link CtsSqlServerDialect} 는 새로 만드는 열만 nvarchar 로 만든다. 예전 버전이 만든 열(varchar(255), text)은
 * DB collation 의 코드 페이지에 묶여 있어서 MariaDB 가 저장하는 글자를 다 담지 못한다. 기동 시 한 번 여기서 바꾼다.
 * 이미 바뀐 열은 대상이 아니므로 매번 불러도 된다.
 * <ul>
 *   <li>varchar(n) → nvarchar(n) (n &gt; 4000 이면 MAX), varchar(MAX)·text → nvarchar(MAX), char(n) → nchar(n)</li>
 *   <li>collation·NULL 여부는 그대로 둔다</li>
 *   <li>그 열에 걸린 인덱스·UNIQUE 제약·기본값은 지웠다가 같은 이름·같은 모양으로 다시 건다.
 *       열 통계는 지운다(SQL Server 가 다시 만든다)</li>
 *   <li>PRIMARY KEY·외래 키에 걸린 문자열 열은 다루지 않는다 — 있으면 예외로 멈춘다. 지금 스키마에는 없다</li>
 * </ul>
 * 트랜잭션 안에서 불러야 한다. SQL Server 의 DDL 은 트랜잭션을 따르므로 중간에 실패하면 전부 되돌아간다.
 * <p>
 * 이 파일은 SQL Server 전용 SQL(sys.*, [ ] 따옴표)을 쓴다. DbPortabilityGuardTest 의 허용 목록에 있다.
 */
@Component
public class SqlServerUnicodeColumns {
    private static final Logger logger = LoggerFactory.getLogger("agent");

    private final JdbcTemplate jdbcTemplate;

    public SqlServerUnicodeColumns(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 바꿀 열 하나 */
    static final class Col {
        final int columnId;
        final String name, type, collation;
        final int maxLength;
        final boolean nullable;

        Col(int columnId, String name, String type, int maxLength, boolean nullable, String collation) {
            this.columnId = columnId;
            this.name = name;
            this.type = type;
            this.maxLength = maxLength;
            this.nullable = nullable;
            this.collation = collation;
        }

        /** 새 타입. maxLength 는 바이트 수이고 varchar·char 는 한 글자 1바이트다. -1 은 MAX */
        String target() {
            if ("text".equals(type) || maxLength == -1 || maxLength > 4000) return "nvarchar(MAX)";
            return ("char".equals(type) ? "nchar(" : "nvarchar(") + maxLength + ")";
        }
    }

    /**
     * @param tables 앱 테이블 이름(대소문자 무관). 이 밖의 테이블은 건드리지 않는다
     * @return 바꾼 열 이름 목록(table.column)
     */
    public List<String> convert(Collection<String> tables) {
        Set<String> wanted = tables.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, Integer> tableIds = new TreeMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT t.object_id, t.name FROM sys.tables t WHERE t.schema_id = SCHEMA_ID()")) {
            String name = (String) r.get("name");
            if (wanted.contains(name.toLowerCase(Locale.ROOT))) tableIds.put(name, ((Number) r.get("object_id")).intValue());
        }
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, Integer> t : tableIds.entrySet()) {
            List<Col> cols = narrowColumns(t.getValue());
            if (cols.isEmpty()) continue;
            convertTable(t.getKey(), t.getValue(), cols);
            for (Col c : cols) done.add(t.getKey() + "." + c.name + " " + c.type + " -> " + c.target());
        }
        return done;
    }

    private List<Col> narrowColumns(int tableId) {
        List<Col> out = new ArrayList<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT c.column_id, c.name, ty.name AS type_name, c.max_length, c.is_nullable, c.collation_name"
                        + " FROM sys.columns c JOIN sys.types ty ON ty.user_type_id = c.user_type_id"
                        + " WHERE c.object_id = ? AND ty.name IN ('varchar', 'char', 'text') AND c.is_computed = 0"
                        + " ORDER BY c.column_id", tableId)) {
            out.add(new Col(((Number) r.get("column_id")).intValue(), (String) r.get("name"), (String) r.get("type_name"),
                    ((Number) r.get("max_length")).intValue(), toBool(r.get("is_nullable")), (String) r.get("collation_name")));
        }
        return out;
    }

    private void convertTable(String table, int tableId, List<Col> cols) {
        String ids = cols.stream().map(c -> String.valueOf(c.columnId)).collect(Collectors.joining(","));
        String t = q(table);

        // 외래 키에 걸린 문자열 열은 양쪽 테이블을 같이 바꿔야 해서 다루지 않는다
        Integer fks = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys.foreign_key_columns"
                + " WHERE (parent_object_id = ? AND parent_column_id IN (" + ids + "))"
                + " OR (referenced_object_id = ? AND referenced_column_id IN (" + ids + "))", Integer.class, tableId, tableId);
        if (fks != null && fks > 0) throw new IllegalStateException("Table " + table + " has a foreign key on a text column.");

        // 1) 열 통계(인덱스가 아닌 것)를 지운다
        for (Map<String, Object> s : jdbcTemplate.queryForList(
                "SELECT DISTINCT s.name FROM sys.stats s JOIN sys.stats_columns sc"
                        + " ON sc.object_id = s.object_id AND sc.stats_id = s.stats_id"
                        + " WHERE s.object_id = ? AND sc.column_id IN (" + ids + ")"
                        + " AND NOT EXISTS (SELECT 1 FROM sys.indexes i WHERE i.object_id = s.object_id AND i.index_id = s.stats_id)",
                tableId)) {
            jdbcTemplate.execute("DROP STATISTICS " + t + "." + q((String) s.get("name")));
        }

        // 2) 그 열이 든 인덱스·UNIQUE 제약을 적어 두고 지운다
        List<String> recreate = new ArrayList<>();
        for (Map<String, Object> ix : jdbcTemplate.queryForList(
                "SELECT i.index_id, i.name, i.type_desc, i.is_unique, i.is_primary_key, i.is_unique_constraint,"
                        + " i.has_filter, i.filter_definition FROM sys.indexes i"
                        + " WHERE i.object_id = ? AND i.index_id > 0 AND EXISTS (SELECT 1 FROM sys.index_columns ic"
                        + " WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.column_id IN (" + ids + "))",
                tableId)) {
            String name = (String) ix.get("name");
            if (toBool(ix.get("is_primary_key"))) {
                throw new IllegalStateException("Primary key " + name + " of " + table + " is on a text column.");
            }
            int indexId = ((Number) ix.get("index_id")).intValue();
            List<String> keys = new ArrayList<>(), includes = new ArrayList<>();
            for (Map<String, Object> ic : jdbcTemplate.queryForList(
                    "SELECT c.name, ic.is_descending_key, ic.is_included_column, ic.key_ordinal FROM sys.index_columns ic"
                            + " JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id"
                            + " WHERE ic.object_id = ? AND ic.index_id = ? ORDER BY ic.key_ordinal, ic.index_column_id",
                    tableId, indexId)) {
                String col = q((String) ic.get("name"));
                if (toBool(ic.get("is_included_column"))) includes.add(col);
                else keys.add(col + (toBool(ic.get("is_descending_key")) ? " DESC" : ""));
            }
            String kind = "CLUSTERED".equals(ix.get("type_desc")) ? "CLUSTERED" : "NONCLUSTERED";
            if (toBool(ix.get("is_unique_constraint"))) {
                jdbcTemplate.execute("ALTER TABLE " + t + " DROP CONSTRAINT " + q(name));
                recreate.add("ALTER TABLE " + t + " ADD CONSTRAINT " + q(name) + " UNIQUE " + kind
                        + " (" + String.join(", ", keys) + ")");
            } else {
                jdbcTemplate.execute("DROP INDEX " + q(name) + " ON " + t);
                recreate.add("CREATE " + (toBool(ix.get("is_unique")) ? "UNIQUE " : "") + kind + " INDEX " + q(name)
                        + " ON " + t + " (" + String.join(", ", keys) + ")"
                        + (includes.isEmpty() ? "" : " INCLUDE (" + String.join(", ", includes) + ")")
                        + (toBool(ix.get("has_filter")) ? " WHERE " + ix.get("filter_definition") : ""));
            }
        }

        // 3) 기본값·CHECK 제약(열 단위)
        for (Map<String, Object> d : jdbcTemplate.queryForList(
                "SELECT dc.name, dc.definition, c.name AS col FROM sys.default_constraints dc"
                        + " JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id"
                        + " WHERE dc.parent_object_id = ? AND dc.parent_column_id IN (" + ids + ")", tableId)) {
            jdbcTemplate.execute("ALTER TABLE " + t + " DROP CONSTRAINT " + q((String) d.get("name")));
            recreate.add("ALTER TABLE " + t + " ADD CONSTRAINT " + q((String) d.get("name"))
                    + " DEFAULT " + d.get("definition") + " FOR " + q((String) d.get("col")));
        }
        for (Map<String, Object> ck : jdbcTemplate.queryForList(
                "SELECT name, definition FROM sys.check_constraints WHERE parent_object_id = ? AND parent_column_id IN (" + ids + ")",
                tableId)) {
            jdbcTemplate.execute("ALTER TABLE " + t + " DROP CONSTRAINT " + q((String) ck.get("name")));
            recreate.add("ALTER TABLE " + t + " ADD CONSTRAINT " + q((String) ck.get("name")) + " CHECK " + ck.get("definition"));
        }

        // 4) 열을 바꾼다
        for (Col c : cols) {
            jdbcTemplate.execute("ALTER TABLE " + t + " ALTER COLUMN " + q(c.name) + " " + c.target()
                    + (c.collation == null ? "" : " COLLATE " + c.collation)
                    + (c.nullable ? " NULL" : " NOT NULL"));
        }

        // 5) 지운 것을 다시 건다
        for (String sql : recreate) jdbcTemplate.execute(sql);

        logger.info("Converted {} column(s) of {} to Unicode: {}", cols.size(), table,
                cols.stream().map(c -> c.name + " " + c.type + " -> " + c.target()).collect(Collectors.joining(", ")));
    }

    /** SQL Server 식 따옴표 */
    static String q(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return o != null && "true".equalsIgnoreCase(o.toString());
    }
}
