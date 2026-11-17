package com.onionsoftware.mk119.commtestsuite.db.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Hibernate 방언을 datasource URL 로 고른다. DB 를 바꿀 때 application.properties 의 datasource 줄만 바꾸면 된다.
 * <ul>
 *   <li>jdbc:sqlserver: → {@link CtsSqlServerDialect} (항상. 문자열을 nvarchar 로 다뤄야 하므로 다른 방언을 적어 두었어도 바꾼다)</li>
 *   <li>jdbc:mariadb: → 적어 둔 MariaDB/MySQL 방언이 있으면 그것, 없으면 MariaDBDialect</li>
 * </ul>
 * spring.jpa.database-platform 보다 뒤에 적용되므로 예전 설정 파일에 MariaDBDialect 가 남아 있어도
 * SQL Server 에 MariaDB 구문이 나가지 않는다.
 */
@Configuration
public class DialectSelector {
    private static final Logger logger = LoggerFactory.getLogger(DialectSelector.class);

    static final String MARIADB_DEFAULT = "org.hibernate.dialect.MariaDBDialect";

    @Bean
    public HibernatePropertiesCustomizer ctsDialectCustomizer(Environment env) {
        return props -> {
            String chosen = choose(env.getProperty("spring.datasource.url"),
                    firstNonBlank(env.getProperty("spring.jpa.properties.hibernate.dialect"),
                            env.getProperty("spring.jpa.database-platform")));
            if (chosen != null) {
                props.put("hibernate.dialect", chosen);
                logger.info("Hibernate dialect: {}", chosen);
            }
        };
    }

    /** @return 쓸 방언 클래스 이름. 정하지 못하면 null(스프링 기본 동작) */
    static String choose(String url, String configured) {
        switch (DbKind.fromUrl(url)) {
            case SQLSERVER:
                if (configured != null && !CtsSqlServerDialect.class.getName().equals(configured)) {
                    logger.warn("Configured dialect {} is replaced with {} for SQL Server.", configured,
                            CtsSqlServerDialect.class.getName());
                }
                return CtsSqlServerDialect.class.getName();
            case MARIADB:
                if (configured != null && (configured.contains("MariaDB") || configured.contains("MySQL"))) return configured;
                if (configured != null) {
                    logger.warn("Configured dialect {} does not match MariaDB. Using {}.", configured, MARIADB_DEFAULT);
                }
                return MARIADB_DEFAULT;
            default:
                return configured;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return null;
    }
}
