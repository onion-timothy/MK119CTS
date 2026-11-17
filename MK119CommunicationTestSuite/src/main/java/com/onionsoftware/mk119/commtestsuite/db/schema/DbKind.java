package com.onionsoftware.mk119.commtestsuite.db.schema;

import java.util.Locale;

/**
 * 앱이 붙는 DB 종류. 지원하는 것은 MariaDB 와 SQL Server 둘이다.
 * <p>
 * 둘에서 똑같이 돌아야 하므로 SQL 은 둘이 함께 아는 문법만 쓴다(백틱·DATABASE()·LIMIT·ISNULL 등 금지).
 * 한쪽 전용 SQL 이 꼭 필요하면 이 값으로 갈라서 쓰고, DbPortabilityGuardTest 의 허용 목록에 그 파일을 올린다.
 */
public enum DbKind {
    MARIADB, SQLSERVER, OTHER;

    /** JDBC URL 로 판별한다. 연결 없이 알 수 있어 Hibernate 가 뜨기 전에도 쓸 수 있다 */
    public static DbKind fromUrl(String url) {
        if (url == null) return OTHER;
        String u = url.trim().toLowerCase(Locale.ROOT);
        // log4jdbc 를 끼우면 jdbc:log4jdbc:mariadb:... 가 된다
        if (u.startsWith("jdbc:log4jdbc:")) u = "jdbc:" + u.substring("jdbc:log4jdbc:".length());
        if (u.startsWith("jdbc:mariadb:") || u.startsWith("jdbc:mysql:")) return MARIADB;
        if (u.startsWith("jdbc:sqlserver:")) return SQLSERVER;
        return OTHER;
    }

    /** DatabaseMetaData.getDatabaseProductName() 으로 판별한다 */
    public static DbKind fromProductName(String name) {
        if (name == null) return OTHER;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("mariadb") || n.contains("mysql")) return MARIADB;
        if (n.contains("sql server")) return SQLSERVER;
        return OTHER;
    }
}
