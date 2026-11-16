package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.ProtocolDefinition;
import com.onionsoftware.mk119.commtestsuite.db.service.ProtocolDefinitionChecker;
import com.onionsoftware.mk119.commtestsuite.db.service.ProtocolDefinitionService;
import com.onionsoftware.mk119.commtestsuite.protocol.DefinitionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 프로토콜 정의 REST API — docs/PROTOCOL_ENGINE_DESIGN.md 2절.
 * <p>
 * 실패 응답은 {@code {ok: false, error}}, 문서 검사 실패면 {@code {ok: false, problems, tests}} 다.
 * problems 하나는 {level, at, text} 이고 at 은 편집기 문제 목록과 같은 JSON 경로다.
 */
@RestController
@RequestMapping("/api/protocols")
@RequiredArgsConstructor
public class ProtocolDefinitionController {
    private final ProtocolDefinitionService service;
    private final ProtocolDefinitionChecker checker;
    /** 저장하면 바꿔 끼운다 — 그 정의를 쓰는 장비는 연결을 유지한 채 다음 요청부터 새 판을 쓴다 */
    private final DefinitionRegistry registry;

    // ── 조회 ─────────────────────────────────────────────────────────────

    @GetMapping
    public List<Map<String, Object>> list() {
        Map<Integer, Integer> usage = service.usageCounts();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProtocolDefinition d : service.selectAll()) {
            Map<String, Object> m = header(d);
            m.put("usedBy", usage.getOrDefault(d.getId(), 0));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Integer id) {
        Optional<ProtocolDefinition> d = service.select(id);
        if (!d.isPresent()) return notFound(id);
        return ResponseEntity.ok(full(d.get()));
    }

    /**
     * 타겟 목록 (docs/PROTOCOL_ENGINE_DESIGN.md 9.1): 선언된 타겟 + 문서에 글자로 나온 타겟 + 패턴.
     * dataTypes 는 그 타겟에 등록할 수 있는 Value Simulation data type 이다 (모르면 null).
     */
    @GetMapping("/{id}/targets")
    public ResponseEntity<?> targets(@PathVariable Integer id) {
        if (!service.select(id).isPresent()) return notFound(id);
        com.onionsoftware.mk119.commtestsuite.protocol.Definition def = registry.get(id);
        if (def == null) return fail(HttpStatus.CONFLICT, "Protocol definition " + id + " does not compile.");
        List<Map<String, Object>> targets = new ArrayList<>();
        for (com.onionsoftware.mk119.commtestsuite.protocol.Definition.TargetInfo t : def.catalog.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("target", t.target);
            m.put("group", t.group);
            m.put("type", t.value != null ? t.value : typeText(t.spec));
            m.put("declared", t.declared);
            m.put("access", t.writable ? "rw" : "r");
            m.put("dataTypes", t.dataTypes());
            targets.add(m);
        }
        List<Map<String, Object>> patterns = new ArrayList<>();
        for (com.onionsoftware.mk119.commtestsuite.protocol.Definition.TargetGroup g : def.patterns) {
            com.onionsoftware.mk119.commtestsuite.protocol.Definition.TargetInfo probe = new com.onionsoftware.mk119.commtestsuite.protocol.Definition.TargetInfo();
            probe.spec = g.patternSpec;
            probe.value = g.patternValue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", g.name);
            m.put("pattern", g.pattern.pattern());
            m.put("type", g.patternValue != null ? g.patternValue : typeText(g.patternSpec));
            m.put("access", g.patternWritable ? "rw" : "r");
            m.put("dataTypes", probe.dataTypes());
            patterns.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", def.name);
        out.put("targets", targets);
        out.put("patterns", patterns);
        return ResponseEntity.ok(out);
    }

    private static String typeText(com.onionsoftware.mk119.commtestsuite.protocol.Definition.FieldSpec s) {
        if (s == null) return null;
        String t = s.kind == com.onionsoftware.mk119.commtestsuite.protocol.Definition.Kind.NUM ? s.numText() : s.kind.name();
        if (s.scaled && s.scale != 1) t += " ×" + com.onionsoftware.mk119.commtestsuite.protocol.Values.fmt(s.scale);
        return t;
    }

    // ── 생성 / 수정 / 삭제 ───────────────────────────────────────────────

    /** 본문: {document} */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Object document = body.get("document");
        ProtocolDefinitionChecker.Result r = checker.check(document, null);
        if (!r.ok()) return invalid(r);
        return saved(service.insert(asMap(document)), r);
    }

    /** 본문: {document, version}. version 이 지금 것과 다르면 409 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        Optional<ProtocolDefinition> cur = service.select(id);
        if (!cur.isPresent()) return notFound(id);
        Object document = body.get("document");
        ProtocolDefinitionChecker.Result r = checker.check(document, id);
        if (!r.ok()) return invalid(r);
        Integer version = body.get("version") instanceof Number ? ((Number) body.get("version")).intValue() : null;
        try {
            return saved(service.update(cur.get(), asMap(document), version), r);
        } catch (ObjectOptimisticLockingFailureException e) {
            return fail(HttpStatus.CONFLICT, "This protocol definition was saved somewhere else after you opened it. "
                    + "Reload to see that version, or download yours first.");
        }
    }

    /** 쓰는 장비가 있으면 409 — 장비를 먼저 바꾸게 한다 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        Optional<ProtocolDefinition> d = service.select(id);
        if (!d.isPresent()) return notFound(id);
        List<Device> users = service.usedBy(id);
        if (!users.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Device u : users) names.append(names.length() == 0 ? "" : ", ").append(u.getName());
            return fail(HttpStatus.CONFLICT, "\"" + d.get().getName() + "\" is used by " + users.size()
                    + (users.size() == 1 ? " device" : " devices") + " (" + names + "). Choose another definition for them first.");
        }
        service.delete(id);
        registry.remove(id);
        return ok(Collections.singletonMap("id", id));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<?> duplicate(@PathVariable Integer id) {
        Optional<ProtocolDefinition> d = service.select(id);
        if (!d.isPresent()) return notFound(id);
        Map<String, Object> doc = service.document(d.get());
        doc.put("name", service.copyName(d.get().getName()));
        ProtocolDefinitionChecker.Result r = checker.check(doc, null);
        if (!r.ok()) return invalid(r);
        return saved(service.insert(doc), r);
    }

    /** 저장하지 않고 검사만. 본문: {document, id?} */
    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody Map<String, Object> body) {
        Integer self = body.get("id") instanceof Number ? ((Number) body.get("id")).intValue() : null;
        ProtocolDefinitionChecker.Result r = checker.check(body.get("document"), self);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("problems", r.problems);
        m.put("tests", r.tests);
        return m;
    }

    // ── 응답 모양 ─────────────────────────────────────────────────────────

    private Map<String, Object> header(ProtocolDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("description", d.getDescription());
        m.put("version", d.getVersion());
        m.put("updatedAt", d.getUpdatedAt());
        int rx = 0, tx = 0;
        Object msgs = service.document(d).get("messages");
        if (msgs instanceof List) {
            for (Object o : (List<?>) msgs) {
                Object dir = o instanceof Map ? ((Map<?, ?>) o).get("direction") : null;
                if ("rx".equals(dir)) rx++;
                else if ("tx".equals(dir)) tx++;
            }
        }
        m.put("rx", rx);
        m.put("tx", tx);
        return m;
    }

    private Map<String, Object> full(ProtocolDefinition d) {
        Map<String, Object> m = header(d);
        m.put("createdAt", d.getCreatedAt());
        m.put("document", service.document(d));
        List<Map<String, Object>> users = new ArrayList<>();
        for (Device u : service.usedBy(d.getId())) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", u.getId());
            x.put("name", u.getName());
            x.put("protocol", u.getProtocol());
            users.add(x);
        }
        m.put("devices", users);
        m.put("usedBy", users.size());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object document) {
        return (Map<String, Object>) document;
    }

    private ResponseEntity<?> saved(ProtocolDefinition d, ProtocolDefinitionChecker.Result r) {
        registry.put(d.getId(), r.definition);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", full(d));
        m.put("problems", r.problems);   // 경고는 저장을 막지 않지만 돌려준다
        return ok(m);
    }

    private static ResponseEntity<?> ok(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.putAll(extra);
        return ResponseEntity.ok(m);
    }

    private static ResponseEntity<?> invalid(ProtocolDefinitionChecker.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("problems", r.problems);
        m.put("tests", r.tests);
        return ResponseEntity.badRequest().body(m);
    }

    private static ResponseEntity<?> fail(HttpStatus status, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return ResponseEntity.status(status).body(m);
    }

    private static ResponseEntity<?> notFound(Integer id) {
        return fail(HttpStatus.NOT_FOUND, "Protocol definition " + id + " does not exist.");
    }
}
