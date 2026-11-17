package com.onionsoftware.mk119.commtestsuite.db.bean;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 프로토콜 정의 문서(cts-protocol). 장비와 따로 저장하고, GENERIC·DAEYUN_RCU 장비가 id 로 가리킨다.
 * 형식은 docs/PROTOCOL_DEFINITION_FORMAT.md, 저장·실행은 docs/PROTOCOL_ENGINE_DESIGN.md.
 */
@Getter
@Setter
@Entity
@Table(name = "protocol_definitions")
public class ProtocolDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 문서의 name 과 같다. 저장할 때 서버가 문서 쪽 값을 쓴다 */
    @Column(length = 100, nullable = false, unique = true)
    private String name;

    /** 문서의 doc. 목록에 보이는 용도 */
    @Column(length = 1000)
    private String description;

    /**
     * 문서 JSON 전체. @Lob 만 둔다 — 방언이 MariaDB 는 longtext, SQL Server 는 nvarchar(MAX) 로 만든다.
     * columnDefinition 으로 DB 타입 이름을 적으면 한쪽 DB 에서 테이블이 아예 안 만들어진다(예전 LONGTEXT).
     */
    @Lob
    @Column(nullable = false)
    private String content;

    /** 낙관적 잠금. 두 창에서 고쳐도 나중 저장이 앞의 것을 덮지 않게 한다 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
