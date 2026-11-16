package com.onionsoftware.mk119.commtestsuite.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.ProtocolDefinition;
import com.onionsoftware.mk119.commtestsuite.db.repository.ProtocolDefinitionRepository;
import com.onionsoftware.mk119.commtestsuite.db.service.ProtocolDefinitionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * id → 최신 컴파일된 정의 (docs/PROTOCOL_ENGINE_DESIGN.md 10.3).
 * 저장 API 가 새 판으로 바꿔 끼우고, 엔진은 요청마다 여기서 읽는다 — 연결을 끊지 않고 다음 요청부터 새 판이다.
 * 처음 찾을 때 DB 에서 읽어 컴파일한다. 컴파일되지 않는 문서는 null (장비는 응답하지 않고 로그를 남긴다).
 * <p>
 * 정적 규칙(DataTypeRules 등)이 장비의 정의를 볼 수 있게 {@link #current()} 를 둔다 — 스프링이 하나만 만든다.
 */
@Component
@RequiredArgsConstructor
public class DefinitionRegistry {
    private static volatile DefinitionRegistry instance;

    private final ProtocolDefinitionRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<Integer, Optional<Definition>> cache = new ConcurrentHashMap<>();

    @javax.annotation.PostConstruct
    void register() { instance = this; }

    /** 앱 밖(단위 시험)에서는 null */
    public static DefinitionRegistry current() { return instance; }

    /** 컴파일된 정의. 없거나 컴파일되지 않으면 null */
    public Definition get(Integer id) {
        if (id == null) return null;
        return cache.computeIfAbsent(id, this::load).orElse(null);
    }

    /** 장비가 가리키는 정의 */
    public Definition forDevice(Device d) {
        return get(ProtocolDefinitionService.definitionIdOf(d));
    }

    public void put(Integer id, Definition def) {
        cache.put(id, Optional.ofNullable(def));
    }

    public void remove(Integer id) {
        cache.remove(id);
    }

    private Optional<Definition> load(Integer id) {
        Optional<ProtocolDefinition> row = repository.findById(id);
        if (!row.isPresent()) return Optional.empty();
        try {
            Object doc = objectMapper.readValue(row.get().getContent(), LinkedHashMap.class);
            Compiler.Result r = Compiler.compile(doc);
            if (!r.ok()) {
                LoggerFactory.getLogger("io").error("Protocol definition {} (\"{}\") does not compile: {}", id, row.get().getName(),
                        r.problems.isEmpty() ? "" : r.problems.get(0).text);
            }
            return Optional.ofNullable(r.definition);
        } catch (Exception e) {
            LoggerFactory.getLogger("io").error("Protocol definition {} could not be read", id, e);
            return Optional.empty();
        }
    }
}
