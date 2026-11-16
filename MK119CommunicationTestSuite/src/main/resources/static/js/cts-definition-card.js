/**
 * 장비의 "Protocol definition" 카드 (fragments/device-form.html :: definition-card).
 * GENERIC·DAEYUN_RCU 장비가 저장된 프로토콜 정의를 고르고 그 파라미터를 채운다 (docs/PROTOCOL_ENGINE_DESIGN.md 10.1).
 * 규칙은 CtsDeviceForm.definitionProblems — 서버(DeviceSettingsValidator)와 같은 문구다.
 *
 *     const card = CtsDefinitionCard.mount(document.getElementById('definition-card'));
 *     card.load().then(() => card.set(protocol, cs));   // 저장된 정의 목록을 읽고 장비 값으로
 *     card.read()                    // → { definitionId, params }  (빈 칸은 빼서 기본값을 쓰게)
 *     card.problems(protocol)        // → [{ key, id, message }]
 */
(function (global) {
    'use strict';

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[m]));
    }

    function mount(root) {
        const $ = sel => root.querySelector(sel);
        const select = $('#def-select'), params = $('#def-params'), desc = $('#def-desc'),
              open = $('#def-open'), summary = $('#def-summary');
        let list = [];                 // 저장된 정의 [{ id, name, description, rx, tx }]
        const details = {};            // id → 정의 문서 요약 { id, name, params, messages }
        let protocol = 'GENERIC';
        let current = null;
        let values = {};               // 파라미터 칸 값. 정의를 바꿔도 같은 이름이면 남긴다

        function load() {
            return fetch('/api/protocols').then(r => r.ok ? r.json() : []).then(function (data) {
                list = data || [];
                options(select.value);
            });
        }

        function options(keep) {
            const info = (global.CtsDeviceForm.PROTOCOLS[protocol] || {});
            const none = info.definition === 'optional' ? 'None — serial (RS) requests get no reply' : 'Choose a definition…';
            select.innerHTML = '<option value="">' + esc(none) + '</option>'
                + list.map(d => '<option value="' + d.id + '">' + esc(d.name) + '</option>').join('');
            // 목록에 없는 id (지워졌거나 아직 못 읽음) 도 그대로 보여 준다 — 검사가 알린다
            if (keep && !list.some(d => String(d.id) === String(keep))) {
                const o = new Option('Definition ' + keep + ' (not found)', keep);
                select.appendChild(o);
            }
            select.value = keep || '';
        }

        function fetchDetail(id) {
            if (details[id]) return Promise.resolve(details[id]);
            return fetch('/api/protocols/' + encodeURIComponent(id)).then(r => r.ok ? r.json() : null).then(function (d) {
                if (!d) return null;
                const doc = d.document || {};
                details[id] = { id: d.id, name: d.name, description: d.description, params: doc.params || {},
                                rx: d.rx, tx: d.tx, messages: (doc.messages || []) };
                return details[id];
            }).catch(() => null);
        }

        function readParams() {
            params.querySelectorAll('input[data-param]').forEach(i => { values[i.dataset.param] = i.value; });
        }

        function render() {
            const id = select.value;
            open.classList.toggle('disabled', !id);
            open.setAttribute('href', id ? '/protocols/edit/' + id : '#');
            if (!id) {
                current = null;
                desc.textContent = '';
                params.innerHTML = '';
                summary.textContent = '';
                return Promise.resolve();
            }
            return fetchDetail(id).then(function (d) {
                if (select.value !== id) return;
                current = d;
                if (!d) {
                    desc.textContent = '';
                    params.innerHTML = '';
                    summary.textContent = '';
                    return;
                }
                desc.textContent = d.description || '';
                summary.textContent = d.rx + ' received · ' + d.tx + ' sent message' + (d.tx === 1 ? '' : 's');
                const names = Object.keys(d.params);
                params.innerHTML = names.length ? names.map(function (n) {
                    const p = d.params[n] || {};
                    const range = p.min != null || p.max != null ? (p.min != null ? p.min : '…') + '–' + (p.max != null ? p.max : '…') : '';
                    const hint = [p.value || 'integer', range].filter(Boolean).join(' · ');
                    return '<div><label class="form-label" for="def-param-' + esc(n) + '">' + esc(n)
                        + (hint ? '<span class="pk-sub">' + esc(hint) + '</span>' : '') + '</label>'
                        + '<input type="text" class="form-control" inputmode="decimal" id="def-param-' + esc(n) + '" data-param="' + esc(n) + '"'
                        + ' value="' + esc(values[n] != null ? values[n] : '') + '"'
                        + ' placeholder="' + esc(p.default != null ? 'Default ' + p.default : 'Required') + '"'
                        + (p.doc ? ' title="' + esc(p.doc) + '"' : '') + '></div>';
                }).join('') : '<div class="ed-note mb-0">This definition has no parameters.</div>';
            });
        }

        select.addEventListener('change', function () { readParams(); render(); root.dispatchEvent(new Event('definitionchange')); });

        return {
            load: load,
            /** 프로토콜과 장비 값으로 칸을 채운다 */
            set: function (p, cs) {
                protocol = p;
                cs = cs || {};
                values = {};
                const ps = cs.params && typeof cs.params === 'object' ? cs.params : {};
                Object.keys(ps).forEach(k => { values[k] = ps[k] == null ? '' : String(ps[k]); });
                options(cs.definitionId == null ? '' : String(cs.definitionId));
                return render();
            },
            /** 프로토콜만 바꾼다 (새 장비에서 프로토콜을 고를 때) */
            setProtocol: function (p) { protocol = p; options(select.value); return render(); },
            read: function () {
                readParams();
                const out = {};
                if (select.value !== '') out.definitionId = /^\d+$/.test(select.value) ? parseInt(select.value, 10) : select.value;
                const ps = {};
                Object.keys(values).forEach(function (k) {
                    if (current && !Object.prototype.hasOwnProperty.call(current.params, k)) return;   // 다른 정의의 칸
                    const t = String(values[k]).trim();
                    if (t !== '') ps[k] = /^[+-]?\d+$/.test(t) ? parseInt(t, 10) : /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(t) ? Number(t) : t;
                });
                if (Object.keys(ps).length) out.params = ps;
                return out;
            },
            problems: function (p) {
                return global.CtsDeviceForm.definitionProblems(p || protocol, this.read(), current);
            },
            /** 문제 칸에 is-invalid */
            mark: function (list) {
                const bad = {};
                (list || []).forEach(x => { bad[x.id] = true; });
                select.classList.toggle('is-invalid', !!bad['def-select']);
                params.querySelectorAll('input[data-param]').forEach(i => i.classList.toggle('is-invalid', !!bad[i.id]));
            },
            current: () => current,
            ready: () => render(),
        };
    }

    /**
     * 저장된 정의를 모두 읽는다 (일괄 등록이 행마다 동기로 검사하려고).
     * → { list, byId: { id: { id, name, params, rx, tx } }, byName: { 이름: id } }
     */
    function loadAll() {
        return fetch('/api/protocols').then(r => r.ok ? r.json() : []).then(function (list) {
            return Promise.all((list || []).map(d => fetch('/api/protocols/' + d.id).then(r => r.ok ? r.json() : null).catch(() => null)))
                .then(function (full) {
                    const byId = {}, byName = {};
                    full.filter(Boolean).forEach(function (d) {
                        byId[d.id] = { id: d.id, name: d.name, description: d.description, params: (d.document || {}).params || {}, rx: d.rx, tx: d.tx };
                        byName[d.name] = d.id;
                    });
                    return { list: list || [], byId: byId, byName: byName };
                });
        });
    }

    /**
     * 파일(엑셀)에서 온 설정: 정의를 이름으로 줄 수 있다 — { "definition": "power-meter", "params": {…} }.
     * 아는 이름이면 definitionId 로 바꾼다. 모르는 이름이면 그대로 두고 unknownName 에 알린다.
     */
    function normalize(cs, all) {
        if (!cs || typeof cs !== 'object') return { cs: cs, unknownName: null };
        const out = Object.assign({}, cs);
        let unknownName = null;
        if (out.definitionId == null && out.definition != null && String(out.definition).trim() !== '') {
            const id = all && all.byName[String(out.definition).trim()];
            if (id != null) out.definitionId = id;
            else unknownName = String(out.definition).trim();
        }
        delete out.definition;
        return { cs: out, unknownName: unknownName };
    }

    global.CtsDefinitionCard = { mount: mount, loadAll: loadAll, normalize: normalize };
})(window);
