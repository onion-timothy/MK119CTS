package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.protocol.Compiler;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition;
import com.onionsoftware.mk119.commtestsuite.protocol.DefinitionTests;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 저장 전 문서 검사: 엔진 컴파일러 → 문서의 tests → 이름 겹침 (docs/PROTOCOL_ENGINE_DESIGN.md 2.2).
 * 문제 하나는 {level, at, text} 이고, at 은 편집기 문제 목록과 같은 JSON 경로다.
 * 컴파일되지 않는 문서는 장비가 쓸 수 없으므로 저장을 거부한다. 시험이 하나라도 실패해도 거부한다.
 */
@Component
@RequiredArgsConstructor
public class ProtocolDefinitionChecker {
    private final ProtocolDefinitionService service;

    public static final class Result {
        public final List<Map<String, Object>> problems = new ArrayList<>();
        public final List<Map<String, Object>> tests = new ArrayList<>();
        /** 컴파일된 정의 (오류가 있으면 null). 저장하면 Registry 에 넣는다 */
        public Definition definition;

        public boolean ok() {
            for (Map<String, Object> p : problems) if ("error".equals(p.get("level"))) return false;
            for (Map<String, Object> t : tests) if (!Boolean.TRUE.equals(t.get("ok"))) return false;
            return true;
        }

        void error(List<Object> at, String text) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", "error");
            m.put("at", at);
            m.put("text", text);
            problems.add(m);
        }
    }

    /** @param selfId 고치는 정의의 id (새 문서면 null) — 이름 겹침에서 자신을 뺀다 */
    public Result check(Object document, Integer selfId) {
        Result r = new Result();
        Compiler.Result c = Compiler.compile(document);
        for (Compiler.Problem p : c.problems) r.problems.add(p.toMap());
        r.definition = c.definition;
        if (c.ok()) {
            for (DefinitionTests.Outcome o : DefinitionTests.run(c.definition)) r.tests.add(o.toMap());
        }
        if (document instanceof Map) {
            Object name = ((Map<?, ?>) document).get("name");
            String n = name == null ? "" : name.toString().trim();
            if (n.length() > 100) r.error(Collections.singletonList("name"), "Document name is longer than 100 characters.");
            else if (!n.isEmpty() && service.nameTaken(n, selfId)) {
                r.error(Collections.singletonList("name"), "Another protocol definition is already named \"" + n + "\".");
            }
        }
        return r;
    }
}
