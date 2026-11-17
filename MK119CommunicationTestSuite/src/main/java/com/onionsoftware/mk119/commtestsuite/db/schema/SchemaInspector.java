package com.onionsoftware.mk119.commtestsuite.db.schema;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 지금 붙은 DB 의 테이블·열을 JDBC 메타데이터로 본다. information_schema 를 직접 읽으면
 * DB 마다 "지금 DB" 를 가리키는 법이 달라서(MariaDB 의 DATABASE(), SQL Server 의 DB_NAME()) 쓰지 않는다.
 * <p>
 * 이름은 대소문자를 가리지 않고 비교한다. 지금 DB(catalog)·스키마 안만 본다.
 * 트랜잭션 안에서 부르면 그 연결로 본다 — 같은 트랜잭션에서 바꾼 것도 보인다.
 */
@Component
public class SchemaInspector {

    /** 열 하나. type 은 DB 가 부르는 이름 그대로(nvarchar, text, LONGTEXT …) */
    public static final class Column {
        public final String table, name, type;
        public final int size;
        public final boolean nullable;

        Column(String table, String name, String type, int size, boolean nullable) {
            this.table = table;
            this.name = name;
            this.type = type;
            this.size = size;
            this.nullable = nullable;
        }

        @Override
        public String toString() {
            return table + "." + name + " " + type + "(" + size + ")" + (nullable ? "" : " not null");
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public SchemaInspector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DbKind kind() {
        return jdbcTemplate.execute((ConnectionCallback<DbKind>) c ->
                DbKind.fromProductName(c.getMetaData().getDatabaseProductName()));
    }

    /** 소문자 테이블 이름 → (소문자 열 이름 → 열). 순서는 DB 가 준 열 순서 */
    public Map<String, Map<String, Column>> columns() {
        return jdbcTemplate.execute((ConnectionCallback<Map<String, Map<String, Column>>>) c -> {
            Map<String, Map<String, Column>> out = new TreeMap<>();
            DatabaseMetaData md = c.getMetaData();
            String catalog = c.getCatalog(), schema = schemaOf(c);
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = md.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
            for (String t : tables) out.put(t, new LinkedHashMap<>());
            try (ResultSet rs = md.getColumns(catalog, schema, "%", "%")) {
                while (rs.next()) {
                    String t = rs.getString("TABLE_NAME");
                    Map<String, Column> cols = out.get(t.toLowerCase(Locale.ROOT));
                    if (cols == null) continue;   // 뷰 등
                    String n = rs.getString("COLUMN_NAME");
                    cols.put(n.toLowerCase(Locale.ROOT), new Column(t, n, rs.getString("TYPE_NAME"),
                            rs.getInt("COLUMN_SIZE"), rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                }
            }
            return out;
        });
    }

    /** 테이블의 열. 테이블이 없으면 null */
    public Map<String, Column> columns(String table) {
        return columns().get(table.toLowerCase(Locale.ROOT));
    }

    public boolean hasTable(String table) {
        return columns(table) != null;
    }

    public boolean hasColumn(String table, String column) {
        Map<String, Column> cols = columns(table);
        return cols != null && cols.containsKey(column.toLowerCase(Locale.ROOT));
    }

    /** SQL Server 는 dbo 같은 스키마가 있고 MariaDB 는 없다(null). 드라이버가 모르면 null */
    private static String schemaOf(Connection c) {
        try {
            return c.getSchema();
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
            return null;
        }
    }
}
