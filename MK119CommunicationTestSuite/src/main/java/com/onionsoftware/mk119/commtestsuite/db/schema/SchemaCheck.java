package com.onionsoftware.mk119.commtestsuite.db.schema;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManagerFactory;
import java.util.*;

/**
 * 엔티티가 쓰는 테이블·열이 DB 에 다 있는지 본다.
 * <p>
 * ddl-auto=update 는 DDL 하나가 실패해도 WARN 한 줄만 남기고 계속 뜬다. 그러면 그 테이블을 쓰는 화면이
 * 나중에 500 으로만 드러난다(SQL Server 에서 LONGTEXT 로 protocol_definitions 가 안 만들어졌던 일).
 * 기동 직후 여기서 맞춰 보고 빠진 것을 이름까지 알린다.
 */
@Component
public class SchemaCheck {

    private final EntityManagerFactory entityManagerFactory;
    private final SchemaInspector schemaInspector;

    public SchemaCheck(EntityManagerFactory entityManagerFactory, SchemaInspector schemaInspector) {
        this.entityManagerFactory = entityManagerFactory;
        this.schemaInspector = schemaInspector;
    }

    /** 엔티티가 기대하는 테이블 → 열(소문자, 따옴표 없이) */
    public Map<String, Set<String>> expected() {
        Map<String, Set<String>> out = new TreeMap<>();
        SessionFactoryImplementor sf = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        for (EntityPersister p : sf.getMetamodel().entityPersisters().values()) {
            if (!(p instanceof AbstractEntityPersister)) continue;
            AbstractEntityPersister ep = (AbstractEntityPersister) p;
            Set<String> cols = out.computeIfAbsent(bare(ep.getTableName()), k -> new TreeSet<>());
            for (String c : ep.getIdentifierColumnNames()) cols.add(bare(c));
            String[] props = ep.getPropertyNames();
            for (int i = 0; i < props.length; i++) {
                for (String c : ep.getPropertyColumnNames(i)) {
                    if (c != null) cols.add(bare(c));
                }
            }
        }
        return out;
    }

    /** 빠진 테이블·열. 다 있으면 빈 목록 */
    public List<String> problems() {
        Map<String, Map<String, SchemaInspector.Column>> actual = schemaInspector.columns();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : expected().entrySet()) {
            Map<String, SchemaInspector.Column> cols = actual.get(e.getKey());
            if (cols == null) {
                out.add("Table " + e.getKey() + " is missing.");
                continue;
            }
            for (String c : e.getValue()) {
                if (!cols.containsKey(c)) out.add("Column " + e.getKey() + "." + c + " is missing.");
            }
        }
        return out;
    }

    /** 방언 따옴표([x], "x", `x`)와 스키마 접두어를 떼고 소문자로 */
    static String bare(String name) {
        String n = name;
        int dot = n.lastIndexOf('.');
        if (dot >= 0) n = n.substring(dot + 1);
        StringBuilder sb = new StringBuilder();
        for (char ch : n.toCharArray()) if (ch != '[' && ch != ']' && ch != '"' && ch != '`') sb.append(ch);
        n = sb.toString();
        return n.toLowerCase(Locale.ROOT);
    }
}
