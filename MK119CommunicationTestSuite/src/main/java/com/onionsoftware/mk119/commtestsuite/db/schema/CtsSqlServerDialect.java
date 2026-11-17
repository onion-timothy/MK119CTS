package com.onionsoftware.mk119.commtestsuite.db.schema;

import org.hibernate.dialect.SQLServer2012Dialect;
import org.hibernate.type.descriptor.sql.LongNVarcharTypeDescriptor;
import org.hibernate.type.descriptor.sql.NClobTypeDescriptor;
import org.hibernate.type.descriptor.sql.NVarcharTypeDescriptor;
import org.hibernate.type.descriptor.sql.SqlTypeDescriptor;

import java.sql.Types;

/**
 * SQL Server 에서 문자열을 유니코드(nvarchar)로 다루는 방언.
 * <p>
 * 기본 방언은 String 을 varchar 로 만든다. SQL Server 의 varchar 는 DB collation 의 코드 페이지를 따라서
 * Korean_Wansung 이면 CP949 밖의 글자(베트남어 성조, 이모지 등)가 '?' 로 바뀐다. MariaDB(utf8mb4)는 다 저장하므로
 * 두 DB 가 같은 글자를 저장하게 하려면 SQL Server 쪽을 nvarchar 로 맞춰야 한다.
 * <ul>
 *   <li>DDL: varchar(n) → nvarchar(n), @Lob(CLOB)·varchar(MAX) → nvarchar(MAX)</li>
 *   <li>바인딩: setString·setClob 대신 setNString·setNClob 으로 보낸다</li>
 * </ul>
 * 이미 만들어진 열은 Hibernate(update)가 바꾸지 않으므로 {@link SqlServerUnicodeColumns} 가 기동 시 한 번 바꾼다.
 * MariaDB 에는 쓰지 않는다 — {@link DialectSelector} 가 URL 로 고른다.
 */
public class CtsSqlServerDialect extends SQLServer2012Dialect {

    /** nvarchar(n) 의 n 상한. 넘으면 nvarchar(MAX) */
    private static final int NVARCHAR_MAX_LENGTH = 4000;

    public CtsSqlServerDialect() {
        super();
        registerColumnType(Types.VARCHAR, "nvarchar(MAX)");
        registerColumnType(Types.VARCHAR, NVARCHAR_MAX_LENGTH, "nvarchar($l)");
        // 부모가 8000 칸에 varchar($l) 를 걸어 두었다. 4000 을 넘는 길이가 그쪽으로 가지 않게 덮는다
        registerColumnType(Types.VARCHAR, 8000, "nvarchar(MAX)");
        registerColumnType(Types.LONGVARCHAR, "nvarchar(MAX)");
        registerColumnType(Types.CLOB, "nvarchar(MAX)");
    }

    @Override
    protected SqlTypeDescriptor getSqlTypeDescriptorOverride(int sqlCode) {
        switch (sqlCode) {
            case Types.VARCHAR:
                return NVarcharTypeDescriptor.INSTANCE;
            case Types.LONGVARCHAR:
                return LongNVarcharTypeDescriptor.INSTANCE;
            case Types.CLOB:
                return NClobTypeDescriptor.DEFAULT;
            default:
                return super.getSqlTypeDescriptorOverride(sqlCode);
        }
    }
}
