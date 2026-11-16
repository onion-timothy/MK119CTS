#!/bin/zsh
# 장비 통신 설정 검사 사례(src/test/resources/device-settings-cases.json)를 화면 쪽 JS 로 돌린다.
# 같은 표를 서버는 DeviceSettingsValidatorTest 가 돌린다 — 둘 다 통과해야 화면과 서버가 같은 말을 한다.
# node 대신 JavaScriptCore(osascript -l JavaScript) 로 돈다.
ROOT="${0:A:h}/../MK119CommunicationTestSuite"
exec osascript -l JavaScript - "$ROOT" <<'JS'
ObjC.import('Foundation');
function run(argv) {
    const ROOT = argv[0];
    const read = p => $.NSString.stringWithContentsOfFileEncodingError(ROOT + '/' + p, $.NSUTF8StringEncoding, null).js;
    const window = {};
    // 브라우저처럼 window 에 건 이름을 전역에서도 찾게 한다
    ['cts-datatypes.js', 'cts-device-form.js'].forEach(f => {
        (new Function('window', 'global', 'document', read('src/main/resources/static/js/' + f)))(window, window, undefined);
        Object.assign(globalThis, window);
    });
    const { CtsDeviceForm } = window;

    // batchAddDevices 의 settingsOf · validateCommSettings 와 같은 순서 (거기는 첫 것만 쓴다)
    const blank = v => v === null || v === undefined || String(v).trim() === '';
    function settingsOf(raw) {
        if (blank(raw)) return {};
        if (raw && typeof raw === 'object' && !Array.isArray(raw)) return raw;
        if (typeof raw === 'string') {
            try { const p = JSON.parse(raw); if (p && typeof p === 'object' && !Array.isArray(p)) return p; } catch (e) {}
        }
        return null;
    }
    function problems(protocol, raw) {
        const cs = settingsOf(raw);
        if (cs === null) return ['Communication settings must be a JSON object.'];
        const out = CtsDeviceForm.problems(protocol, cs).map(p => p.message);
        const info = CtsDeviceForm.PROTOCOLS[protocol];
        // 저장된 정의는 앱 밖에서 모른다(null) — 서버의 단위 시험과 같이 id 모양까지만 본다
        if (info && info.definition) CtsDeviceForm.definitionProblems(protocol, cs, null).forEach(p => out.push(p.message));
        return out;
    }

    const cases = JSON.parse(read('src/test/resources/device-settings-cases.json')).cases;
    let failed = 0;
    const lines = [];
    cases.forEach(c => {
        const got = problems(c.protocol, c.cs);
        if (JSON.stringify(got) !== JSON.stringify(c.expect)) {
            failed++;
            lines.push('FAIL ' + c.name + '\n  expect ' + JSON.stringify(c.expect) + '\n  got    ' + JSON.stringify(got));
        }
    });
    lines.push((cases.length - failed) + '/' + cases.length + ' passed');
    if (failed) throw new Error(lines.join('\n'));
    return lines.join('\n');
}
JS
