package com.onionsoftware.mk119.commtestsuite.db.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.ProtocolDefinition;
import com.onionsoftware.mk119.commtestsuite.db.repository.ProtocolDefinitionRepository;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 프로토콜 정의 저장소. 문서 검사는 {@link ProtocolDefinitionChecker} 가 한다.
 * 쓰는 장비는 GENERIC·DAEYUN_RCU 장비의 communicationSettings.definitionId 로 찾는다.
 */
@Service
@RequiredArgsConstructor
public class ProtocolDefinitionService {
    /** 정의를 가리킬 수 있는 프로토콜 */
    public static final Set<Protocol> DEFINITION_PROTOCOLS = Collections.unmodifiableSet(EnumSet.of(Protocol.GENERIC, Protocol.DAEYUN_RCU));
    public static final String SETTING_ID = "definitionId";

    private final ProtocolDefinitionRepository repository;
    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;

    public List<ProtocolDefinition> selectAll() {
        return repository.findAllByOrderByNameAsc();
    }

    public Optional<ProtocolDefinition> select(Integer id) {
        return id == null ? Optional.empty() : repository.findById(id);
    }

    public boolean nameTaken(String name, Integer exceptId) {
        return exceptId == null ? repository.existsByName(name) : repository.existsByNameAndIdNot(name, exceptId);
    }

    @Transactional
    public ProtocolDefinition insert(Map<String, Object> document) {
        ProtocolDefinition d = new ProtocolDefinition();
        LocalDateTime now = LocalDateTime.now();
        d.setCreatedAt(now);
        apply(d, document, now);
        return repository.saveAndFlush(d);
    }

    /** version 이 다르면 ObjectOptimisticLockingFailureException — 호출자가 409 로 바꾼다 */
    @Transactional
    public ProtocolDefinition update(ProtocolDefinition current, Map<String, Object> document, Integer version) {
        if (version != null && !version.equals(current.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(ProtocolDefinition.class, current.getId());
        }
        apply(current, document, LocalDateTime.now());
        return repository.saveAndFlush(current);
    }

    @Transactional
    public void delete(Integer id) {
        repository.deleteById(id);
    }

    /** 이름이 겹치지 않는 복제 이름: "x copy", "x copy 2", … */
    public String copyName(String base) {
        String name = base + " copy";
        for (int n = 2; nameTaken(name, null); n++) name = base + " copy " + n;
        return name;
    }

    public Map<String, Object> document(ProtocolDefinition d) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.readValue(d.getContent(), LinkedHashMap.class);
            return m;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Protocol definition " + d.getId() + " has broken JSON", e);
        }
    }

    /** 이 정의를 가리키는 장비. id 순 */
    public List<Device> usedBy(Integer id) {
        List<Device> out = new ArrayList<>();
        for (Device d : deviceService.selectDevices()) {
            if (id != null && id.equals(definitionIdOf(d))) out.add(d);
        }
        return out;
    }

    /** 정의 id → 쓰는 장비 수 */
    public Map<Integer, Integer> usageCounts() {
        Map<Integer, Integer> out = new HashMap<>();
        for (Device d : deviceService.selectDevices()) {
            Integer id = definitionIdOf(d);
            if (id != null) out.merge(id, 1, Integer::sum);
        }
        return out;
    }

    public static Integer definitionIdOf(Device d) {
        if (d == null || !DEFINITION_PROTOCOLS.contains(d.getProtocol()) || d.getCommunicationSettings() == null) return null;
        Object v = d.getCommunicationSettings().get(SETTING_ID);
        if (v == null) return null;
        try {
            return (int) Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void apply(ProtocolDefinition d, Map<String, Object> document, LocalDateTime now) {
        Object name = document.get("name");
        Object doc = document.get("doc");
        d.setName(name == null ? "" : name.toString().trim());
        String description = doc == null ? null : doc.toString();
        if (description != null && description.length() > 1000) description = description.substring(0, 1000);
        d.setDescription(description);
        try {
            d.setContent(objectMapper.writeValueAsString(document));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The document cannot be written as JSON.", e);
        }
        d.setUpdatedAt(now);
    }
}
