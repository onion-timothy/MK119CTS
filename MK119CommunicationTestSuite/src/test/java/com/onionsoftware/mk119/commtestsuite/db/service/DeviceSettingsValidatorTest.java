package com.onionsoftware.mk119.commtestsuite.db.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * device-settings-cases.json 의 사례를 서버 검사로 돌린다. 같은 표를 tools/check-device-settings.sh 가
 * 화면 쪽(js/cts-device-form.js)으로 돌린다 — 둘 다 통과해야 화면과 서버가 같은 말을 한다.
 * 요청 본문처럼 Jackson 으로 읽는다(정수는 Integer, 소수는 Double).
 */
class DeviceSettingsValidatorTest {

    @TestFactory
    Stream<DynamicTest> cases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/device-settings-cases.json")) {
            root = mapper.readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.get("cases")) {
            String name = c.get("name").asText();
            String protocol = c.get("protocol").asText();
            Object cs = mapper.convertValue(c.get("cs"), Object.class);
            List<String> expect = new ArrayList<>();
            c.get("expect").forEach(e -> expect.add(e.asText()));
            tests.add(DynamicTest.dynamicTest(name, () ->
                    assertEquals(expect, DeviceSettingsValidator.problems(protocol, cs), name)));
        }
        return tests.stream();
    }
}
