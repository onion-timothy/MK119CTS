/**
 * 패킷 편집기 (templates/protocol_editor.html). 문서 모델·검사는 cts-protocol-model.js.
 *
 * 화면은 셋으로 나뉜다: 왼쪽 개요(Outline) · 가운데 고른 항목 · 오른쪽 속성(Inspector)과 문제(Problems).
 * 필드 표에는 이름·타입·길이·값 요약만 두고, 나머지 속성은 줄을 고르면 오른쪽에서 편집한다.
 *
 * 화면이 아니라 상태(doc)가 원본이다. 입력은 바로 상태에 쓰고, 구조가 바뀔 때만 다시 그린다.
 * 입력은 바로 상태에 쓰고, 줄을 늘리고 줄이거나 모양이 바뀔 때만 다시 그린다.
 *
 * 고른 것은 주소창 # 에 남는다. 새로 고쳐도 같은 자리로 돌아온다.
 *     #messages/0?item=body.1       #frames/rtu?item=footer.0       #messages/2?sub=body.0.fields
 * ?example=power-meter 로 열면 예시 문서로 시작한다.
 */
(function () {
    'use strict';

    const M = window.CtsProtocolModel;
    /** 저장하지 않은 고침을 정의별로 남긴다: 접두어 + (id 또는 'new') */
    const DRAFT_PREFIX = 'cts.protocolEditor.draft.';
    /** 목록 화면이 저장 전 문서(JSON 파일 가져오기)를 넘기는 자리 — templates/protocols.html 과 짝 */
    const IMPORT_KEY = 'cts.protocolEditor.import';
    /** 템플릿이 넣어 주는 값: 고치는 정의의 id (새 문서면 null), 시작할 예시 이름 */
    const PAGE = window.CTS_PROTOCOL_PAGE || { id: null, example: null };
    /** 이름으로 고르는 묶음 (나머지는 한 화면짜리) */
    const KEYED = ['targets', 'structures', 'frames', 'messages'];

    let doc = M.newDoc();
    let sel = { sec: 'general', key: null, sub: null, item: null };
    let problems = [];
    let validateTimer = null, saveTimer = null;
    /** 서버에 있는 판: id·version 과 그때의 문서 글. savedJson 과 다르면 저장할 것이 있다 */
    let defId = PAGE.id, defVersion = null, savedJson = null;
    /** 마지막 저장 시도에서 서버가 돌려준 문제. 다음 고침에서 지운다 */
    let serverProblems = [];
    let saving = false;
    /** 테스트 번호 → 마지막 실행 결과 */
    let testResults = null;

    const $ = id => document.getElementById(id);

    // ── 작은 도구 ─────────────────────────────────────────────

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, m =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', '\'': '&#39;' }[m]));
    }
    function P(path) { return esc(JSON.stringify(path)); }
    function keys(o) { return o && typeof o === 'object' ? Object.keys(o) : []; }
    function list(v) { return Array.isArray(v) ? v : []; }
    function getAt(path) {
        let o = doc;
        for (let i = 0; i < path.length; i++) { if (o == null) return undefined; o = o[path[i]]; }
        return o;
    }
    function setAt(path, v) {
        let o = doc;
        for (let i = 0; i < path.length - 1; i++) {
            const k = path[i];
            if (o[k] == null || typeof o[k] !== 'object') o[k] = typeof path[i + 1] === 'number' ? [] : {};
            o = o[k];
        }
        o[path[path.length - 1]] = v;
    }
    function delAt(path) {
        const parent = getAt(path.slice(0, -1)), k = path[path.length - 1];
        if (Array.isArray(parent)) parent.splice(k, 1);
        else if (parent && typeof parent === 'object') delete parent[k];
    }
    function startsWith(path, prefix) {
        if (path.length < prefix.length) return false;
        for (let i = 0; i < prefix.length; i++) if (path[i] !== prefix[i]) return false;
        return true;
    }
    function uniqueName(obj, base) {
        let n = 1;
        while (obj && Object.prototype.hasOwnProperty.call(obj, base + n)) n++;
        return base + n;
    }

    /** 글자 안의 제어 문자를 \r \n \t \xHH 로 보이고, 입력은 거꾸로 푼다 */
    function escText(s) {
        return String(s).replace(/\\/g, '\\\\').replace(/\r/g, '\\r').replace(/\n/g, '\\n').replace(/\t/g, '\\t')
            .replace(/[\x00-\x1f]/g, c => '\\x' + ('0' + c.charCodeAt(0).toString(16)).slice(-2).toUpperCase());
    }
    function unescText(s) {
        return String(s).replace(/\\(r|n|t|\\|x[0-9A-Fa-f]{2})/g, function (m, c) {
            if (c === 'r') return '\r';
            if (c === 'n') return '\n';
            if (c === 't') return '\t';
            if (c === '\\') return '\\';
            return String.fromCharCode(parseInt(c.slice(1), 16));
        });
    }
    function scalar(raw) {
        const t = raw.trim();
        return /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : raw;
    }
    function linesOf(obj) { return keys(obj).map(k => k + ' = ' + (typeof obj[k] === 'string' ? obj[k] : JSON.stringify(obj[k]))).join('\n'); }
    function parseLines(text) {
        const out = {};
        String(text).split('\n').forEach(function (line) {
            const i = line.indexOf('=');
            if (i <= 0) return;
            const k = line.slice(0, i).trim();
            if (k) out[k] = scalar(line.slice(i + 1).trim());
        });
        return out;
    }

    /** 사람이 읽기 좋은 JSON: 짧은 객체·배열은 한 줄로 */
    function pretty(v, indent) {
        indent = indent || '';
        if (v === null || typeof v !== 'object') return JSON.stringify(v);
        const one = JSON.stringify(v, null, 1).replace(/\n\s*/g, ' ');
        if (indent && one.length + indent.length <= 110) return one;
        const inner = indent + '  ';
        if (Array.isArray(v)) {
            if (!v.length) return '[]';
            return '[\n' + v.map(x => inner + pretty(x, inner)).join(',\n') + '\n' + indent + ']';
        }
        const ks = Object.keys(v);
        if (!ks.length) return '{}';
        return '{\n' + ks.map(k => inner + JSON.stringify(k) + ': ' + pretty(v[k], inner)).join(',\n') + '\n' + indent + '}';
    }

    // ── 입력칸 ────────────────────────────────────────────────
    // data-p 는 상태 안 경로(JSON), data-v 는 글자를 값으로 바꾸는 방식이다 (readValue)

    function input(path, value, opts) {
        opts = opts || {};
        let shown = value == null ? '' : value;
        if ((opts.v === 'esc' || (opts.v === 'const' && opts.text)) && typeof shown === 'string') shown = escText(shown);
        if (typeof shown === 'object') shown = JSON.stringify(shown);
        return '<input type="text" class="form-control form-control-sm' + (opts.mono ? ' pe-mono' : '') + (opts.cls ? ' ' + opts.cls : '') + '"'
            + ' data-p="' + P(path) + '" data-v="' + (opts.v || 'text') + '"'
            + (opts.list ? ' list="' + opts.list + '"' : '')
            + ' value="' + esc(shown) + '" placeholder="' + esc(opts.ph || '') + '" autocomplete="off" spellcheck="false">';
    }
    function textarea(path, value, opts) {
        opts = opts || {};
        return '<textarea class="form-control form-control-sm' + (opts.mono ? ' pe-mono' : '') + '" rows="' + (opts.rows || 2) + '"'
            + ' data-p="' + P(path) + '" data-v="' + (opts.v || 'text') + '"'
            + ' placeholder="' + esc(opts.ph || '') + '" spellcheck="false">' + esc(value == null ? '' : value) + '</textarea>';
    }
    /** options: ['a', 'b'] 또는 [['값', '보이는 글'], …]. 목록에 없는 지금 값은 맨 앞에 남긴다 */
    function select(path, value, options, opts) {
        opts = opts || {};
        const norm = options.map(o => Array.isArray(o) ? o : [o, o]);
        const cur = value == null ? '' : String(value);
        let html = '';
        if (cur !== '' && !norm.some(o => String(o[0]) === cur)) html += '<option value="' + esc(cur) + '" selected>' + esc(cur) + '</option>';
        html += norm.map(o => '<option value="' + esc(o[0]) + '"' + (String(o[0]) === cur ? ' selected' : '') + '>' + esc(o[1]) + '</option>').join('');
        return '<select class="form-select form-select-sm"' + (path ? ' data-p="' + P(path) + '"' : '')
            + ' data-v="' + (opts.v || 'select') + '"' + (opts.act ? ' data-act="' + opts.act + '"' : '') + '>' + html + '</select>';
    }
    function cell(label, control, opts) {
        opts = opts || {};
        return '<div class="' + (opts.wide ? 'pe-wide' : opts.span ? 'pe-span-' + opts.span : '') + '"><label class="form-label">' + esc(label)
            + (opts.hint ? '<span class="pe-hint">' + esc(opts.hint) + '</span>' : '') + '</label>' + control + '</div>';
    }
    function btn(act, icon, text, attrs, cls) {
        return '<button type="button" class="btn btn-sm ' + (cls || 'btn-outline-primary') + '" data-act="' + act + '"' + (attrs || '') + '>'
            + (icon ? '<i class="fas fa-' + icon + '"></i>' : '') + (text ? ' ' + esc(text) : '') + '</button>';
    }
    function iconBtn(act, icon, title, attrs, cls) {
        return '<button type="button" class="btn btn-sm btn-icon ' + (cls || 'btn-ghost-secondary') + '" data-act="' + act + '"'
            + (attrs || '') + ' title="' + esc(title) + '"><i class="fas fa-' + icon + '"></i></button>';
    }
    function delBtn(path, title) { return iconBtn('del', 'times', title || 'Remove', ' data-p="' + P(path) + '"', 'btn-ghost-danger'); }
    function moveBtns(path) {
        return iconBtn('move', 'arrow-up', 'Move up', ' data-p="' + P(path) + '" data-d="-1"')
             + iconBtn('move', 'arrow-down', 'Move down', ' data-p="' + P(path) + '" data-d="1"');
    }
    /**
     * opts.list: 표·목록 카드. 칸이 모자라면 줄어들고 그 안에서 스크롤한다.
     * opts.fill: 남는 높이를 다 갖는 주 카드. 화면마다 하나이고, 없으면 renderMain 이 마지막 카드에 준다.
     */
    function card(title, sub, actions, body, opts) {
        opts = opts || {};
        const cls = (opts.list ? ' is-list' : '') + (opts.fill ? ' is-fill' : '') + (opts.cls ? ' ' + opts.cls : '');
        return '<div class="card cts-card pe-card' + cls + '"' + (opts.id ? ' id="' + opts.id + '"' : '') + '>'
            + '<div class="card-header"><h3 class="card-title">' + title + (sub ? '<span class="pk-sub">' + sub + '</span>' : '') + '</h3>'
            + (actions ? '<div class="card-actions">' + actions + '</div>' : '') + '</div>'
            + '<div class="card-body' + (opts.flush ? ' p-0' : '') + '">' + body + '</div></div>';
    }
    function empty(text) { return '<div class="pe-empty">' + esc(text) + '</div>'; }
    function badge(dir) { return '<span class="pe-badge is-' + dir + '">' + (dir === 'rx' ? 'RX' : dir === 'tx' ? 'TX' : '?') + '</span>'; }

    // ── 표 (Tabulator · CtsTable) ─────────────────────────────
    // 표는 모두 Tabulator 이고 공통 표 규칙(js/cts-table.js — 열 폭 균등, 행 영역 k×h, 고정 높이 표는 쪽 나눔)을 따른다.
    // 보기 함수는 HTML 에 자리(tableSlot)만 그리고, HTML 을 넣은 뒤 mountTables 가 표를 붙인다.
    // 행 데이터는 문서(doc)에서 그때그때 만든다 — 원본은 늘 doc 이다. 칸을 고치면 doc 에 쓰고 행을 다시 만든다.

    const ROW_H = 40;
    /** 표 카드가 칸이 모자라도 지키는 줄 수 (renderMain). 그보다 많은 줄은 쪽을 나눈다 */
    const MIN_ROWS = 1;
    let tableSeq = 0;
    let pendingTables = [];
    let liveTables = [];
    /** 영역 구분 줄(Fields 표의 Header·Body·Footer 머리)의 경로 끝. 문서에 없는 경로라 getAt 은 undefined 다 */
    const SEP = '#sep';
    /** 속성 모달이 떠 있는 동안: { doc, sel } 연 때의 문서·자리(Cancel 이면 되돌린다), ok 는 OK 로 닫는지 */
    let props = null;

    /**
     * spec: {
     *   paths: () => [경로…]       행마다 문서 안 경로
     *   row: (path, i) => {…}      칸 값. _path·_id·_i 는 붙여 준다
     *   columns: [...]             col* 로 만든 열
     *   empty: '…'                 행이 없을 때
     *   nav: true                  행을 누르면 그 항목으로 간다 (행의 _nav)
     *   auto: true                 높이를 내용만큼 (속성 모달 안) — 높이가 정해지지 않았으니 쪽을 나누지 않는다.
     *                              칸에서 바로 고친다
     * }
     * nav·auto 가 아닌 표(가운데 칸의 목록)는 칸을 고치지 않는다 — 줄을 누르면 속성 모달이 열린다.
     */
    function tableSlot(spec) {
        const id = 'pe-tab-' + (tableSeq++);
        pendingTables.push(Object.assign({ id: id }, spec));
        return '<div class="pe-tab cts-tabulator' + (spec.auto ? ' is-auto' : ' cts-fill') + '" id="' + id + '"></div>';
    }
    function buildRow(spec, path, i) {
        const d = spec.row(path, i) || {};
        d._path = path; d._id = JSON.stringify(path);
        if (!('_i' in d)) d._i = i;
        return d;
    }
    /** 행 겉모양: 고른 줄·문제 표시. data-row 는 markProblems·goAt 이 찾는 표지다 */
    function paintRow(el, path, spec) {
        const id = JSON.stringify(path);
        el.dataset.row = id;
        el.classList.toggle('pe-link', !!spec.nav);
        el.classList.toggle('is-sel', !spec.nav && !spec.auto && sel.item != null && id === JSON.stringify(sel.item));
        const lv = spec.nav ? null : worst(path);
        el.classList.toggle('is-bad', lv === 'error');
        el.classList.toggle('is-warn', lv === 'warn');
        el.classList.toggle('pe-sep-row', path[path.length - 1] === SEP);
    }

    function mountTables() {
        const specs = pendingTables;
        pendingTables = [];
        specs.forEach(function (spec) {
            const el = document.getElementById(spec.id);
            if (!el) return;
            const commits = {};
            const opens = !spec.nav && !spec.auto;
            const cols = spec.columns.map(function (c) {
                const o = Object.assign({ headerSort: false }, c);
                if (o._commit) { commits[o.field] = o._commit; delete o._commit; }
                // 가운데 칸의 목록은 읽기만 한다 — 고치는 것은 속성 모달에서
                if (opens) { delete o.editor; delete o.editable; }
                // 영역 구분 줄은 spec.sep 이 칸마다 그린다 (열의 formatter 는 필드 줄만 안다)
                if (spec.sep) {
                    const own = o.formatter;
                    o.formatter = function (cell) {
                        const d = cell.getRow().getData();
                        if (d._sep) return spec.sep(o.field, d);
                        if (own) return own.apply(this, arguments);
                        const v = cell.getValue();
                        return v == null ? '' : esc(v);
                    };
                }
                return o;
            });
            const opts = {
                data: spec.paths().map((p, i) => buildRow(spec, p, i)),
                index: '_id', layout: 'fitData', renderVertical: 'basic',
                placeholder: spec.empty || 'Nothing here yet.',
                columnDefaults: { vertAlign: 'middle' },
                rowFormatter: row => paintRow(row.getElement(), row.getData()._path, spec),
                columns: CtsTable.columns(cols),
            };
            if (!spec.auto) Object.assign(opts, { height: '100%', pagination: true, paginationSize: 10, paginationButtonCount: 3 });
            const tab = new Tabulator(el, opts);
            const live = { tab: tab, spec: spec, el: el, built: false };
            liveTables.push(live);
            CtsTable.attach(tab, spec.auto ? {} : { rowH: ROW_H });
            tab.on('tableBuilt', function () { live.built = true; revealSelected(live); });
            // 고친 값은 편집이 끝난 뒤에 쓴다 — 쓰다가 표를 다시 그리는 경우(타입 바꾸기)가 있어서
            tab.on('cellEdited', function (cell) {
                const fn = commits[cell.getField()];
                const path = cell.getRow().getData()._path, v = cell.getValue();
                if (fn) setTimeout(function () { fn(path, v); }, 0);
            });
            tab.on('rowClick', function (e, row) {
                if (e.target.closest('[data-act]')) return;
                const d = row.getData();
                // 표를 걷는 go() 는 클릭 처리가 끝난 뒤에
                if (spec.nav) { setTimeout(() => go(d._nav.sec, d._nav.key == null ? null : d._nav.key), 0); return; }
                if (opens && !d._sep) openProps(d._path);
            });
        });
    }
    /** root 안의 표를 걷는다. 다시 그리기 전에 부른다 */
    function destroyTables(root) {
        liveTables = liveTables.filter(function (t) {
            if (root && !root.contains(t.el)) return true;
            try { t.tab.destroy(); } catch (e) { /* 이미 걷혔다 */ }
            return false;
        });
    }
    /** 모달로 연 줄이 이 표에 있으면 그 쪽을 편다 (줄을 막 더했거나 옮긴 뒤) */
    function revealSelected(live) {
        const want = sel.item;
        if (!want || live.spec.auto) return;
        const row = live.tab.getRow(JSON.stringify(want));
        if (!row) return;
        Promise.resolve(row.pageTo()).catch(function () { /* 쪽을 못 넘겨도 고칠 수는 있다 */ });
    }
    /** 문서가 바뀐 뒤 표의 행을 다시 적는다. 바뀐 행만 고친다 (편집 중인 칸은 건드리지 않는다) */
    function refreshTables() {
        liveTables.forEach(function (t) {
            if (!t.built) return;
            const paths = t.spec.paths();
            const rows = t.tab.getRows();
            if (rows.length !== paths.length) { t.tab.replaceData(paths.map((p, i) => buildRow(t.spec, p, i))); return; }
            rows.forEach(function (r, i) {
                const fresh = buildRow(t.spec, paths[i], i);
                // update 는 값이 바뀐 열의 칸만 다시 그린다. 요약 칸(칩·길이)은 문서 전체를 보므로 줄을 다시 그린다
                if (JSON.stringify(fresh) !== JSON.stringify(r.getData())) { r.update(fresh); r.reformat(); }
                else paintRow(r.getElement(), paths[i], t.spec);
            });
        });
    }

    // 열 만들기. 정렬은 kind 로(CtsTable.columns): 수 오른쪽, 글 왼쪽, 기능 가운데. 폭은 적지 않는다(균등 분배)

    function placeholderHtml(ph) { return ph ? '<span class="pe-ph">' + esc(ph) + '</span>' : ''; }
    function rowOf(cell) { return cell.getRow().getData(); }

    /** 칸 값을 doc 의 (행 경로 + key) 에 쓴다. v 는 readValue 의 방식(num·scalar·keep…) */
    function commitAt(key, v) {
        return function (path, value) {
            const p = path.concat(key);
            const r = parseRaw(v || 'text', value == null ? '' : String(value), p);
            if (r.set) setAt(p, r.v); else delAt(p);
            afterEdit(p, 'table');
        };
    }
    function colNo() {
        return { title: '#', field: '_i', kind: 'number', cssClass: 'pe-ro' };
    }
    /**
     * 글·수 입력 칸. o: { key: 상대 경로(기본 [field]), v: 값 방식, num: 수 칸, mono, ph: 빈 칸 표시,
     *                    when: d => 편집할 수 있는가, fmt: d => 보일 HTML, commit, list: datalist id }
     */
    function colInput(title, field, o) {
        o = o || {};
        return {
            title: title, field: field, kind: o.num ? 'number' : 'text',
            cssClass: 'pe-ed',
            editor: 'input', editable: o.when ? (c => !!o.when(rowOf(c))) : true,
            editorParams: o.list ? { elementAttributes: { list: o.list, spellcheck: 'false', autocomplete: 'off' } } : { elementAttributes: { spellcheck: 'false' } },
            formatter: function (c) {
                const d = rowOf(c);
                if (o.fmt) { const h = o.fmt(d); if (h != null) return h; }
                const v = c.getValue();
                if (v == null || v === '') return o.when && !o.when(d) ? '' : placeholderHtml(o.ph);
                return o.mono ? '<span class="pe-mono">' + esc(v) + '</span>' : esc(v);
            },
            _commit: o.commit || commitAt(o.key || [field], o.v || (o.num ? 'num' : 'text')),
        };
    }
    /** 고르는 칸. options: d => 선택지(cts-select 형식). 보이는 글은 고른 선택지의 글(o.label 로 짧게 바꿀 수 있다) */
    function colSelect(title, field, options, o) {
        o = o || {};
        return {
            title: title, field: field, kind: 'text', cssClass: 'pe-ed pe-sel-cell',
            editor: CtsSelect.cellEditor(c => options(rowOf(c))),
            editable: o.when ? (c => !!o.when(rowOf(c))) : true,
            formatter: function (c) {
                const d = rowOf(c);
                if (o.when && !o.when(d)) return '';
                if (o.fmt) { const h = o.fmt(d); if (h != null) return h; }
                const v = c.getValue() == null ? '' : String(c.getValue());
                const label = o.label ? o.label(v, d) : optionLabel(options(d), v);
                return label === '' ? placeholderHtml(o.ph) : esc(label);
            },
            _commit: o.commit || commitAt(o.key || [field], o.v || 'select'),
        };
    }
    function optionLabel(options, v) {
        let found = null;
        (function walk(list) {
            list.forEach(function (x) {
                if (found != null) return;
                if (x && !Array.isArray(x) && typeof x === 'object' && x.group != null) { walk(x.options || []); return; }
                const pair = Array.isArray(x) ? x : [x, x];
                if (String(pair[0]) === v) found = String(pair[1]);
            });
        })(options || []);
        return found != null ? found : v;
    }
    /** 보기만 하는 칸 */
    function colView(title, field, fmt, kind) {
        return { title: title, field: field, kind: kind || 'text', formatter: c => fmt(rowOf(c)) };
    }
    /** 지우기 단추 — 머리글보다 넓은 단추 열이라 폭을 고정한다(공통 표 규칙의 예외) */
    function colDel(what) {
        return { title: '', field: '_del', kind: 'action', resizable: false, width: 44,
                 formatter: c => delBtn(rowOf(c)._path, what ? 'Remove this ' + what : 'Remove') };
    }

    // ── 고르기와 주소 ─────────────────────────────────────────

    /** 주소에는 보는 곳(차례의 항목)만 남긴다. 모달로 연 줄은 남기지 않는다 */
    function updateHash() {
        let s = encodeURIComponent(sel.sec);
        if (sel.key != null) s += '/' + encodeURIComponent(String(sel.key));
        try { history.replaceState(null, '', location.pathname + location.search + '#' + s); } catch (e) { /* 주소를 못 바꿔도 편집은 된다 */ }
    }
    function readHash() {
        const h = location.hash.replace(/^#/, '');
        if (!h) return;
        const qi = h.indexOf('?');
        const head = (qi >= 0 ? h.slice(0, qi) : h).split('/').map(decodeURIComponent);
        // 예전 주소(#params·#checksums 등)는 합친 페이지로
        const sec = PAGE_OF[head[0]] || head[0];
        if (!SECTIONS.some(s => s.sec === sec)) return;
        let key = PAGE_OF[head[0]] ? null : (head.length > 1 ? head[1] : null);
        if (sec === 'messages' && key != null) key = parseInt(key, 10);
        if (key != null && getAt([sec, key]) == null) key = null;
        sel = { sec: sec, key: key, sub: null, item: null };
    }
    /**
     * 차례를 왼쪽 칸에 늘 펴 두는 화면. 아니면(세로·1500px 보다 좁은 가로 창) 차례는 상단 바의 단추로 여는
     * 드롭다운이다 — 칸 하나·줄 하나를 차지하지 않아 가운데 칸이 폭·높이를 다 쓴다. css 의 같은 조건과 짝
     */
    const WIDE = window.matchMedia('(min-width: 1500px) and (orientation: landscape)');
    function setOutlineOpen(open) {
        document.querySelector('.pe-left').classList.toggle('is-open', open);
        $('pe-ol-toggle').setAttribute('aria-expanded', String(open || WIDE.matches));
        const b = $('pe-ol-btn');
        if (b) b.setAttribute('aria-expanded', String(open));
    }
    function outlineOpen() { return document.querySelector('.pe-left').classList.contains('is-open'); }
    /** 상단 바(제목 옆)의 차례 단추. 상단 바는 공통 조각이라 여기서 끼운다 */
    function mountOutlineButton() {
        const b = document.createElement('button');
        b.type = 'button';
        b.id = 'pe-ol-btn';
        b.className = 'btn btn-outline-secondary pe-ol-btn';
        b.setAttribute('aria-controls', 'pe-outline');
        b.setAttribute('aria-expanded', 'false');
        b.title = 'Document outline';
        b.innerHTML = '<i class="fas fa-list"></i><span class="pe-ol-btn-label" id="pe-ol-where">Document</span><i class="fas fa-chevron-down"></i>';
        $('pageSubtitle').after(b);
    }

    /** 차례의 항목으로 간다. opts.item 이 있으면 그 줄의 속성 모달을 연다 */
    function go(sec, key, opts) {
        opts = opts || {};
        if (PAGE_OF[sec]) { sec = PAGE_OF[sec]; key = null; }
        sel = { sec: sec, key: key == null ? null : key, sub: opts.sub || null, item: null };
        renderOutline();
        renderMain();
        renderInspector();
        markProblems();
        updateHash();
        $('pe-main').scrollTop = 0;
        if (opts.focus) focusPath(opts.focus);
        if (opts.item) openProps(opts.item);
    }

    // ── 속성 모달 ─────────────────────────────────────────────
    // 줄을 누르면 연다. 고치는 대로 문서에 들어가 표·검사가 바로 바뀌고, OK 는 그대로 두고 닫는다.
    // Cancel·×·Esc 는 연 때의 문서로 되돌린다. before 를 주면(줄을 막 더한 경우) 더하기 전으로 되돌린다.

    function openProps(path, before) {
        if (!path || getAt(path) == null) return;
        props = { doc: before || JSON.stringify(doc), sel: JSON.stringify(Object.assign({}, sel, { item: null })), ok: false };
        sel.item = path;
        renderInspector();
        paintRows();
        CtsModal.show('#pe-props');
    }
    function closeProps(ok) {
        if (!props) return;
        props.ok = ok;
        CtsModal.hide('#pe-props');
    }
    /** 모달이 닫힌 뒤(OK·Cancel·Esc 모두). Cancel 이고 고친 것이 있으면 연 때로 되돌린다 */
    function afterPropsHidden() {
        const p = props;
        props = null;
        sel.item = null;
        const box = $('pe-inspector-body');
        destroyTables(box);
        if (window.CtsSelect) CtsSelect.destroyAll(box);
        box.innerHTML = '';
        const edited = p && JSON.stringify(doc) !== p.doc;
        if (edited && !p.ok) {
            doc = JSON.parse(p.doc);
            sel = JSON.parse(p.sel);
            // 막 더한 메시지를 Cancel 하면 보던 항목이 없어진다 — 그 목록으로
            if (sel.key != null && getAt([sel.sec, sel.key]) == null) sel.key = null;
        }
        // 표가 아닌 것(메시지 요약 카드)도 고친 값을 보이게 다시 그린다
        if (edited) { rerender(); updateHash(); return; }
        paintRows();
    }
    /** 모달로 연 줄 표시(is-sel)와 문제 표시를 다시 칠한다 */
    function paintRows() {
        liveTables.forEach(function (t) {
            if (!t.built || t.spec.auto) return;
            t.tab.getRows().forEach(r => paintRow(r.getElement(), r.getData()._path, t.spec));
        });
    }
    function focusPath(path) {
        const node = document.querySelector('[data-p="' + CSS.escape(JSON.stringify(path)) + '"]');
        if (node) { node.focus(); node.scrollIntoView({ block: 'nearest' }); }
    }

    // ── 개요 ──────────────────────────────────────────────────

    /**
     * 차례의 항목. 성격이 비슷한 작은 섹션은 한 페이지에 카드로 모은다 (PAGE_OF):
     *   General = 문서·기본값 + 파라미터 + 상수,  Frames(목록) = 프레임 경계 + 프레임 목록 + 체크섬.
     * at: 그 항목에 문제 점을 찍을 문서 경로들 (합친 섹션의 경로도 여기에)
     */
    const PAGE_OF = { params: 'general', consts: 'general', checksums: 'frames', framing: 'frames' };
    const SECTIONS = [
        { sec: 'general',   label: 'General',    icon: 'file-alt', at: [['general'], ['name'], ['doc'], ['format'], ['defaults'], ['onUnmatched'], ['params'], ['consts']] },
        { sec: 'targets',   label: 'Targets',    icon: 'database', count: () => keys(doc.targets).length, children: () => keys(doc.targets).map(k => ({ key: k, label: k, tag: (doc.targets[k] || {}).kind })) },
        { sec: 'structures', label: 'Structures', icon: 'cubes', count: () => keys(doc.structures).length, children: () => keys(doc.structures).map(k => ({ key: k, label: k })) },
        { sec: 'frames',    label: 'Frames',     icon: 'layer-group', count: () => keys(doc.frames).length, children: () => keys(doc.frames).map(k => ({ key: k, label: k })), at: [['frames'], ['checksums'], ['framing']] },
        { sec: 'messages',  label: 'Messages',   icon: 'exchange-alt', count: () => list(doc.messages).length, children: () => list(doc.messages).map((m, i) => ({ key: i, label: (m && m.name) || '(unnamed)', dir: m && m.direction })) },
        { sec: 'tests',     label: 'Tests',      icon: 'vial', count: () => list(doc.tests).length },
        { sec: 'source',    label: 'JSON',       icon: 'code' },
    ];

    function renderOutline() {
        let html = '', where = '';
        SECTIONS.forEach(function (s) {
            if (sel.sec === s.sec) where = s.label;
            const active = sel.sec === s.sec && sel.key == null;
            html += '<div class="pe-ol-item' + (active ? ' is-active' : '') + '" data-nav="' + esc(JSON.stringify({ sec: s.sec })) + '" data-at="' + P([s.sec]) + '"'
                + (s.at ? ' data-ats="' + esc(JSON.stringify(s.at)) + '"' : '') + '>'
                + '<i class="fas fa-' + s.icon + ' pe-ol-icon"></i><span class="pe-ol-label">' + esc(s.label) + '</span>'
                + '<span class="pe-dot"></span>'
                + (s.count ? '<span class="pe-ol-count">' + s.count() + '</span>' : '') + '</div>';
            if (s.children) s.children().forEach(function (c) {
                const on = sel.sec === s.sec && sel.key === c.key;
                if (on) where += ' › ' + c.label;
                html +='<div class="pe-ol-item pe-ol-child' + (on ? ' is-active' : '') + '" data-nav="' + esc(JSON.stringify({ sec: s.sec, key: c.key })) + '" data-at="' + P([s.sec, c.key]) + '">'
                    + (c.dir ? badge(c.dir) : '') + '<span class="pe-ol-label">' + esc(c.label) + '</span>'
                    + '<span class="pe-dot"></span>'
                    + (c.tag ? '<span class="pe-ol-count">' + esc(c.tag) + '</span>' : '') + '</div>';
            });
        });
        $('pe-outline').innerHTML = html;
        // 접혀 있을 때 지금 어디를 보는지 머리와 상단 바 단추에 보인다
        $('pe-where').textContent = where;
        const w = $('pe-ol-where');
        if (w) w.textContent = where || 'Document';
    }

    // ── 가운데 ────────────────────────────────────────────────

    function renderMain() {
        const main = $('pe-main');
        // 표와 검색 목록은 드롭다운·이벤트를 body 에 걸어 둔다. 새로 그리기 전에 걷는다
        destroyTables(main);
        if (window.CtsSelect) CtsSelect.destroyAll(main);
        let html = '';
        switch (sel.sec) {
            case 'general':   html = columns(viewGeneral(), viewParams() + viewConsts()); break;
            case 'targets':   html = sel.key == null ? viewTargetsOverview() : viewTargetGroup(sel.key); break;
            case 'structures': html = sel.key == null ? viewStructuresOverview() : viewStructure(sel.key); break;
            case 'frames':    html = sel.key == null ? columns(viewFraming() + viewFramesOverview(), viewChecksums()) : viewFrame(sel.key); break;
            case 'messages':  html = sel.key == null ? viewMessagesOverview() : viewMessage(sel.key); break;
            case 'tests':     html = viewTests(); break;
            case 'source':    html = viewSource(); break;
        }
        main.innerHTML = html + datalists();
        // 주 카드를 정하지 않은 화면(입력 칸 묶음뿐이거나 카드 하나)은 마지막 카드가 남는 높이를 갖는다
        const cards = main.querySelectorAll(':scope > .pe-card');
        if (cards.length && !main.querySelector(':scope > .pe-card.is-fill, :scope > .pe-split')) cards[cards.length - 1].classList.add('is-fill');
        if (sel.sec === 'source') $('pe-source').value = pretty(doc) + '\n';
        // 표 카드는 먼저 제 줄이 다 들어갈 높이(카드 머리·표 머리·쪽 넘김 11rem — 재 보니 약 170px + 줄 수 × 행 높이)를 바라고,
        // 남으면 줄 수에 비례해 더 받고, 모자라면 그 높이에 비례해 줄어든다.
        // 줄이 다 안 들어가면 가운데 칸을 스크롤하지 않고 표가 쪽을 나눈다 — 카드는 MIN_ROWS 줄(빈 표·짧은 표는 그 줄 수)까지 준다.
        // 그래도 모자란 아주 낮은 창에서만 칸이 스크롤한다
        pendingTables.forEach(function (spec) {
            const host = document.getElementById(spec.id);
            const box = host && host.closest('.pe-card');
            if (!box || (box.parentNode !== main && !box.parentNode.matches('.pe-split, .pe-colx'))) return;
            const n = Math.max(1, spec.paths().length);
            box.style.flex = n + ' 1 calc(10rem + ' + (n * ROW_H) + 'px)';
            box.style.minHeight = 'calc(11rem + ' + (Math.min(n, MIN_ROWS) * ROW_H) + 'px)';
        });
        mountTables();
        if (window.CtsSelect) CtsSelect.mountAll(main);
    }

    /** 한 페이지에 카드를 두 열로 (가로 화면에서 가운데 칸이 넓을 때. 아니면 위아래로) — css .pe-cols */
    function columns(left, right) {
        return '<div class="pe-cols"><div class="pe-colx">' + left + '</div><div class="pe-colx">' + right + '</div></div>';
    }

    /** 타겟 이름·체크섬 이름 입력 보조 */
    function datalists() {
        const names = [];
        keys(doc.targets).forEach(g => list((doc.targets[g] || {}).items).forEach(function (it) {
            if (it && it.target) names.push(it.target);
            if (it && it.type === 'BITS') list(it.fields).forEach(b => { if (b && b.target) names.push(b.target); });
        }));
        return '<datalist id="pe-target-names">' + names.map(n => '<option value="' + esc(n) + '">').join('') + '</datalist>';
    }

    function viewGeneral() {
        const tx = list(doc.messages).filter(m => m && m.direction === 'tx' && m.name).map(m => m.name);
        const body = '<div class="pe-grid">'
            + cell('Name', input(['name'], doc.name, { v: 'keep', ph: 'e.g. power-meter' }), { hint: 'Devices refer to this name' })
            + cell('Format', '<input type="text" class="form-control form-control-sm" value="cts-protocol · version 1" disabled>')
            + cell('Description', textarea(['doc'], doc.doc, { rows: 3, ph: 'What device or protocol this describes' }), { wide: true })
            + '</div>';
        const defs = '<div class="pe-grid">'
            + cell('Text encoding', select(['defaults', 'encoding'], (doc.defaults || {}).encoding, [['', 'ascii (default)']].concat(M.ENCODINGS.filter(e => e !== 'ascii'))))
            + cell('When a value overflows', select(['defaults', 'onOverflow'], (doc.defaults || {}).onOverflow, [['', 'error (default) — no reply'], ['clamp', 'clamp to the type range'], ['wrap', 'wrap around']]))
            + cell('Frames no message matches', select(['onUnmatched', 'reply'], (doc.onUnmatched || {}).reply, [['', 'none (default) — no reply'], ['echo', 'echo the received bytes']].concat(tx.map(n => [n, 'reply with ' + n]))), { wide: true })
            + '</div>';
        return card('Document', '', '', body) + card('Defaults', 'apply to every message', '', defs);
    }

    function viewParams() {
        const table = tableSlot({
            paths: () => keys(doc.params).map(n => ['params', n]),
            row: function (path) {
                const p = (doc.params || {})[path[1]] || {};
                // value 를 비우면 integer 다
                return { name: path[1], value: p.value || 'integer', default: p.default, min: p.min, max: p.max, doc: p.doc };
            },
            columns: [
                colInput('Name', 'name', { commit: renameCommit }),
                // 파라미터는 값이라 표현이 없다 — 종류와 범위만 (형식 11)
                colSelect('Value', 'value', () => [['integer', 'integer'], ['float', 'float']]),
                colInput('Default', 'default', { num: true }),
                colInput('Min', 'min', { num: true }),
                colInput('Max', 'max', { num: true }),
                colInput('Description', 'doc'),
                colDel('parameter'),
            ],
            empty: 'No parameters. Parameters are values that differ per device, like the unit ID.',
        });
        return card('Parameters', 'values set per device · used as $name', btn('add-param', 'plus', 'Parameter'), table, { flush: true, list: true });
    }

    function viewConsts() {
        const table = tableSlot({
            paths: () => keys(doc.consts).map(n => ['consts', n]),
            row: path => ({ name: path[1], value: (doc.consts || {})[path[1]] }),
            columns: [
                colInput('Name', 'name', { commit: renameCommit }),
                // 비워도 상수는 남는다 (지우기는 × 로)
                colInput('Value', 'value', { mono: true, commit: (path, v) => { setAt(path, v == null || v === '' ? '' : scalar(String(v))); afterEdit(path, 'table'); } }),
                colDel('constant'),
            ],
            empty: 'No constants.',
        });
        return card('Constants', 'fixed values of the protocol · used as $name', btn('add-const', 'plus', 'Constant'), table, { flush: true, list: true });
    }

    /** 표에서 이름(params·consts 의 키)을 바꾼다 */
    function renameCommit(path, value) {
        rename(path, String(value == null ? '' : value));
        rerender(); updateHash();
    }

    function renameInput(path, name) {
        return '<input type="text" class="form-control form-control-sm" data-rename="' + P(path) + '" value="' + esc(name) + '" autocomplete="off" spellcheck="false">';
    }

    function viewChecksums() {
        const cs = doc.checksums || {};
        const blocks = keys(cs).map(function (n) {
            const c = cs[n] || {}, path = ['checksums', n];
            const cat = M.CRC_CATALOG[c.algorithm];
            let params = '';
            if (cat) {
                params = '<div class="pe-wide pe-note">width ' + cat.width + ' · poly ' + cat.poly + ' · init ' + cat.init
                    + ' · ' + (cat.refin ? 'reflected' : 'not reflected') + ' · xorout ' + cat.xorout + ' · check ' + cat.check + '</div>';
            } else if (c.algorithm === 'CRC') {
                params = cell('Width', input(path.concat(['width']), c.width, { v: 'num', ph: '16' }))
                    + cell('Poly', input(path.concat(['poly']), c.poly, { mono: true, ph: '0x1021' }))
                    + cell('Init', input(path.concat(['init']), c.init, { mono: true, ph: '0xFFFF' }))
                    + cell('XOR out', input(path.concat(['xorout']), c.xorout, { mono: true, ph: '0x0000' }))
                    + cell('Reflect in', select(path.concat(['refin']), c.refin, [['', 'no'], ['true', 'yes']], { v: 'bool' }))
                    + cell('Reflect out', select(path.concat(['refout']), c.refout, [['', 'no'], ['true', 'yes']], { v: 'bool' }));
            } else if (c.algorithm === 'SUM') {
                params = cell('Width', select(path.concat(['width']), c.width, [['8', '8 bits'], ['16', '16 bits'], ['32', '32 bits']], { v: 'num' }))
                    + cell('Complement', select(path.concat(['complement']), c.complement, [['', 'none'], ['ones', 'ones'], ['twos', 'twos']]));
            } else if (c.algorithm === 'EXPR') {
                params = cell('Init', input(path.concat(['init']), c.init, { mono: true, ph: '0' }))
                    + cell('Final', input(path.concat(['final']), c.final, { mono: true, ph: 'acc' }))
                    + cell('Step', input(path.concat(['step']), c.step, { mono: true, ph: '(acc + b) & 0xFF' }), { wide: true, hint: 'acc = running value, b = byte' });
            }
            const algos = Object.keys(M.CRC_CATALOG).map(a => [a, a])
                .concat([['CRC', 'CRC — custom parameters'], ['SUM', 'SUM — byte sum'], ['XOR8', 'XOR8 — BCC'], ['LRC8', 'LRC8 — Modbus ASCII'], ['EXPR', 'EXPR — expression']]);
            return '<div class="pk" data-row="' + P(path) + '">'
                + '<div class="pk-head"><span class="pk-tag">Checksum</span>'
                + '<div class="pe-head-name">' + renameInput(path, n) + '</div>'
                + '<div class="pe-head-algo">' + select(path.concat(['algorithm']), c.algorithm, algos, { act: 'algorithm' }) + '</div>'
                + '<span class="pk-del">' + delBtn(path, 'Remove this checksum') + '</span></div>'
                + '<div class="pe-block"><div class="pe-grid pe-grid-4">'
                + cell('Covers from', input(path.concat(['covers', 'from']), (c.covers || {}).from, { mono: true, ph: 'header' }))
                + cell('Covers to', input(path.concat(['covers', 'to']), (c.covers || {}).to, { mono: true, ph: 'body' }))
                + cell('Over', select(path.concat(['over']), c.over, [['', 'sent bytes'], ['value', 'before transforms']]))
                + cell('On mismatch', select(path.concat(['onMismatch']), c.onMismatch, [['', 'drop the frame'], ['flag', 'accept · valid() false']]))
                + params
                + cell('Description', input(path.concat(['doc']), c.doc), { wide: true })
                + '</div></div></div>';
        }).join('');
        const body = blocks ? '<div class="pk-list">' + blocks + '</div>'
            : empty('No checksums. Define one here, then mark its place with a field whose Checksum is this name.');
        return card('Checksums', 'algorithm and covered bytes', btn('add-checksum', 'plus', 'Checksum'), body, { list: true });
    }

    function viewFraming() {
        const f = doc.framing || {};
        const body = '<div class="pe-grid pe-grid-3">'
            + cell('Resync on', input(['framing', 'syncOn'], f.syncOn, { v: 'esc', mono: true, ph: '0x02 or #' }), { hint: 'skip to this byte' })
            + cell('Max length', input(['framing', 'maxLength'], f.maxLength, { v: 'num', ph: '1024' }), { hint: 'bytes' })
            + cell('Idle flush', input(['framing', 'idleFlushMs'], f.idleFlushMs, { v: 'num', ph: 'off' }), { hint: 'ms' })
            + '</div>';
        // 바이트 스터핑(escape)은 엔진이 아직 지원하지 않는다 — 칸을 두지 않는다 (docs/PROTOCOL_ENGINE_DESIGN.md 13절)
        return card('Framing', 'frame boundaries come from the message definitions; these help on noisy links', '', body);
    }

    // ── 타겟 ──

    function viewTargetsOverview() {
        const table = tableSlot({
            nav: true,
            paths: () => keys(doc.targets).map(g => ['targets', g]),
            row: function (path) {
                const t = doc.targets[path[1]] || {};
                return { group: path[1], kind: t.kind || '', _nav: { sec: 'targets', key: path[1] },
                         contents: t.kind === 'pattern' ? t.pattern || '' : list(t.items).length + ' items' };
            },
            columns: [
                colView('Group', 'group', d => esc(d.group)),
                colView('Kind', 'kind', d => esc(d.kind)),
                colView('Contents', 'contents', d => (doc.targets[d.group] || {}).kind === 'pattern' ? '<code>' + esc(d.contents) + '</code>' : esc(d.contents)),
            ],
            empty: 'No target groups. Targets connect packet fields to Server Data values.',
        });
        const actions = btn('add-group', 'plus', 'Sequence', ' data-d="sequence"') + btn('add-group', 'plus', 'Named', ' data-d="named"') + btn('add-group', 'plus', 'Pattern', ' data-d="pattern"');
        const help = '<div class="pe-help"><b>Sequence</b> — addressed slots like a register map; reads take a start address and a count.'
            + '<br><b>Named</b> — values reached by name, like a status flag or a setpoint.'
            + '<br><b>Pattern</b> — keys built by an expression at run time, like <code>ch1</code> … <code>ch32</code>.</div>';
        return card('Targets', 'Server Data keys this protocol reads and writes', actions, table + help, { flush: true, list: true });
    }

    /** 타겟 묶음 화면: 위는 설정 카드(바로 고친다, 4열로 낮게), 아래는 항목 표 (패턴 묶음은 표가 없다) */
    function viewTargetGroup(g) {
        const t = doc.targets[g] || {}, path = ['targets', g];
        const head = card('Target group', esc(g), delBtn(path, 'Remove this target group'), targetGroupSettings(path, t), { cls: 'pe-settings-card' });
        return t.kind === 'pattern' ? head : head + itemsTable(g, t);
    }
    /**
     * 타겟 묶음 설정. 4열:
     *   순차  Name · Kind · First address · Slot size / Unmapped · Description(3)
     *   이름  Name · Kind · Description(2)
     *   패턴  Name · Kind · Pattern(2) / Value · Access · Description(2)
     */
    function targetGroupSettings(path, t) {
        const g = path[1];
        let grid = '<div class="pe-grid pe-grid-4">'
            + cell('Name', renameInput(path, g))
            + cell('Kind', select(path.concat(['kind']), t.kind, [['sequence', 'sequence — by address'], ['named', 'named — by name'], ['pattern', 'pattern — built keys']], { act: 'group-kind' }));
        if (t.kind === 'sequence') {
            grid += cell('First address', input(path.concat(['base']), t.base, { v: 'num', ph: '0' }))
                + cell('Slot size', input(path.concat(['slotSize']), t.slotSize, { v: 'num', ph: '1' }), { hint: 'bytes' })
                + cell('Unmapped addresses', select(path.concat(['unmapped']), t.unmapped, [['', 'error (default)'], ['zero', 'read as zero']]))
                + cell('Description', input(path.concat(['doc']), t.doc), { span: 3 });
        } else if (t.kind === 'pattern') {
            // 패턴 타겟도 값만 선언한다 — 표현은 그 값을 싣는 필드가 갖는다 (형식 8.4)
            grid += cell('Pattern', input(path.concat(['pattern']), t.pattern, { mono: true, ph: '^ch[0-9]{1,2}$' }), { span: 2, hint: 'regular expression' })
                + cell('Value', select(path.concat(['value']), t.value, VALUE_OPTIONS))
                + cell('Access', select(path.concat(['access']), t.access, [['', 'read only'], ['rw', 'read and write']]))
                + cell('Description', input(path.concat(['doc']), t.doc), { span: 2 });
        } else {
            grid += cell('Description', input(path.concat(['doc']), t.doc), { span: 2 });
        }
        return grid + '</div>';
    }

    /** 이름·패턴 타겟이 선언하는 값 (형식 8.3). 비우면 그 타겟을 싣는 필드의 표현에서 추론한다 */
    const VALUE_OPTIONS = [['', '(from the fields)'], ['binary', 'binary — 0 or 1'], ['integer', 'integer'], ['float', 'float — has a fraction']];

    /** 순차 타겟 항목의 표현. short: 표 안이라 코드만. BITS(비트 집합)는 순차 타겟에만 */
    function itemTypes(kind, short) {
        const base = [['', kind === 'named' ? '(from the field)' : '(choose)']].concat([['UINT', short ? 'UINT' : 'UINT — unsigned integer'], ['INT', short ? 'INT' : 'INT — signed integer'], ['FLOAT', short ? 'FLOAT' : 'FLOAT — floating point']])
            .concat([['BCD', 'BCD'], ['TEXT', 'TEXT'], ['TEXTNUM', 'TEXTNUM'], ['HEXNUM', 'HEXNUM'], ['BYTES', 'BYTES']]);
        if (kind === 'named') base.splice(1, 0, ['BIT', short ? 'BIT' : 'BIT — 0 or 1']);
        if (kind === 'sequence') base.push(['BITS', short ? 'BITS' : 'BITS — a set of bits, a target per bit']);
        return base;
    }
    /** 항목 모양: skip / const / bits(비트 집합) / target */
    function itemKind(it) {
        if (!it) return 'target';
        if (it.skip != null) return 'skip';
        if (it.type === 'BITS') return 'bits';
        if (it.const != null && it.target == null) return 'const';
        return 'target';
    }
    /** 비트 집합 줄의 요약: 비트 타겟 이름들 (표 칸이 좁아 칩 하나로) */
    function bitChips(it) {
        const fs = list(it.fields);
        const names = fs.filter(b => b && b.target).map(b => b.target);
        return '<span class="pe-chip is-target" title="' + esc(fs.map(b => (b && (b.target || (b.const != null ? 'const ' + b.const : b.name)) || '—') + ' (' + (b && b.bits) + ')').join(', ')) + '">'
            + '<b>' + fs.length + ' bit fields</b>' + esc(names.length ? names.join(', ') : 'no targets') + '</span>';
    }

    /** 이름 타겟: 값과 access 만 (표현은 메시지 필드에) */
    function namedItemsTable(g) {
        const table = tableSlot({
            paths: () => list((doc.targets[g] || {}).items).map((x, i) => ['targets', g, 'items', i]),
            row: function (path) { const it = getAt(path) || {}; return { target: it.target, value: it.value || '', access: it.access || '', _sum: JSON.stringify(it) }; },
            columns: [
                colNo(),
                colInput('Target', 'target', { list: 'pe-target-names', ph: 'Server Data key' }),
                colSelect('Value', 'value', () => VALUE_OPTIONS, {
                    label: v => v,   // 표 칸에는 값 이름만 (설명은 목록에)
                    // 비워 두면 실은 필드에서 추론한 값을 흐리게 보인다
                    fmt: function (d) {
                        if (d.value) return null;
                        const v = inferredValue(d.target);
                        return placeholderHtml(v ? v + ' (from the fields)' : '(from the fields)');
                    } }),
                colSelect('Access', 'access', () => [['', 'r'], ['rw', 'rw']]),
                colDel('target'),
            ],
            empty: 'No targets yet. A named target is a Server Data value; the message fields that carry it give its representation.',
        });
        return card('Items', 'value only · the representation is on each message field', btn('add-item', 'plus', 'Target', ' data-p="' + P(['targets', g, 'items']) + '" data-d="target"'),
            table, { flush: true, list: true });
    }
    /** 값을 선언하지 않은 이름 타겟: 그것을 실은 첫 필드의 표현으로 본 값 */
    function inferredValue(target) {
        let v = null;
        walkFields(function (f) {
            if (v) return;
            if (f.target === target) v = M.valueOfField(f, doc);
            list(f.type === 'BITS' ? f.fields : []).forEach(b => { if (!v && b && b.target === target) v = M.valueOfField(b, doc); });
        });
        return v;
    }

    function itemsTable(g, t) {
        if (t.kind === 'named') return namedItemsTable(g);
        const seq = t.kind === 'sequence';
        const hasScale = it => { const k = it.type ? M.kindOf({ type: it.type }, doc) : null; return !!k && (M.KIND_ATTRS[k] || []).indexOf('scale') >= 0; };
        const cols = [colNo()];
        if (seq) cols.push(colView('Address', 'addr', d => '<span class="pe-addr">' + esc(d.addr) + '</span>'));
        cols.push(
            colInput('Target', 'target', {
                list: 'pe-target-names', ph: 'Server Data key',
                when: d => d.kind === 'target',
                fmt: function (d) {
                    const it = getAt(d._path) || {};
                    if (d.kind === 'skip') return '<span class="pe-chip"><b>skip</b>' + esc(it.skip) + ' slots</span>';
                    if (d.kind === 'const') return '<span class="pe-chip is-const"><b>const</b>' + esc(M.showConst(it.const)) + '</span>';
                    if (d.kind === 'bits') return bitChips(it);
                    return null;
                },
            }),
            colSelect('Type', 'type', () => itemTypes(t.kind, true), { when: d => d.kind !== 'skip', commit: (path, v) => setItemType(path, v),
                fmt: d => { const it = getAt(d._path) || {}; return M.isNumberType(it.type) ? esc(M.numberText(it)) : null; } }),
            colInput('Scale', 'scale', { num: true, ph: '1', when: d => d.kind !== 'skip' && hasScale(getAt(d._path) || {}) }),
            colSelect('Access', 'access', () => [['', 'r'], ['rw', 'rw']], { when: d => d.kind === 'target' || d.kind === 'bits' }),
            colDel('item'));
        const table = tableSlot({
            paths: () => list((doc.targets[g] || {}).items).map((x, i) => ['targets', g, 'items', i]),
            row: function (path, i) {
                const it = getAt(path) || {};
                const layout = seq ? M.sequenceLayout(doc.targets[g] || {}) : [];
                return { kind: itemKind(it), addr: seq ? addrText(layout[i]) : '', target: it.target, type: it.type || '',
                         scale: it.scale, access: it.access || '', _sum: JSON.stringify(it) };
            },
            columns: cols,
            empty: 'No items yet.',
        });
        const actions = btn('add-item', 'plus', 'Target', ' data-p="' + P(['targets', g, 'items']) + '" data-d="target"')
            + (seq ? btn('add-item', 'plus', 'Bits', ' data-p="' + P(['targets', g, 'items']) + '" data-d="bits"')
                   + btn('add-item', 'plus', 'Const', ' data-p="' + P(['targets', g, 'items']) + '" data-d="const"')
                   + btn('add-item', 'plus', 'Skip', ' data-p="' + P(['targets', g, 'items']) + '" data-d="skip"') : '');
        return card('Items', seq ? 'address order' : '', actions, table, { flush: true, list: true });
    }
    function addrText(r) {
        if (!r) return '';
        if (r.slots == null) return r.address + ' …';
        return r.slots > 1 ? r.address + '–' + (r.address + r.slots - 1) : String(r.address);
    }

    // ── 타입·프레임·메시지 ──

    /** 목록 화면: 행을 누르면 그 항목으로 간다 */
    function overview(title, sub, actions, spec) {
        return card(title, sub, actions, tableSlot(Object.assign({ nav: true }, spec)), { flush: true, list: true });
    }

    function viewStructuresOverview() {
        return overview('Structures', 'groups of fields · a field uses one by name', btn('add-structure', 'plus', 'Structure'), {
            paths: () => keys(doc.structures).map(n => ['structures', n]),
            row: path => { const t = doc.structures[path[1]] || {}; return { name: path[1], fields: list(t.fields).length + ' fields', users: structureUsers(path[1]).length, doc: t.doc || '', _nav: { sec: 'structures', key: path[1] } }; },
            columns: [colView('Structure', 'name', d => esc(d.name)), colView('Fields', 'fields', d => esc(d.fields)), colView('Used by', 'users', d => esc(d.users), 'number'), colView('Description', 'doc', d => esc(d.doc))],
            empty: 'No structures. A structure is a group of fields that message fields use by name, e.g. a record repeated with count.',
        });
    }
    /** 이 구조체를 쓰는 메시지·프레임 필드: [{ label, nav }] */
    function structureUsers(n) {
        const out = [];
        list(doc.messages).forEach(function (m, i) {
            ['header', 'body', 'footer'].forEach(r => list(m && m[r]).forEach(f => { if (f && f.structure === n) out.push({ label: (m.name || '(unnamed)') + '.' + r + '.' + (f.name || '?'), nav: { sec: 'messages', key: i } }); }));
        });
        keys(doc.frames).forEach(function (fn) {
            ['header', 'footer'].forEach(r => list((doc.frames[fn] || {})[r]).forEach(f => { if (f && f.structure === n) out.push({ label: fn + '.' + r + '.' + (f.name || '?'), nav: { sec: 'frames', key: fn } }); }));
        });
        return out;
    }
    /** 구조체 화면: 위는 설정 카드(바로 고친다, 프레임과 같은 4열), 아래는 필드 표 */
    function viewStructure(n) {
        const t = doc.structures[n] || {}, path = ['structures', n];
        const users = structureUsers(n);
        const grid = '<div class="pe-grid pe-grid-4">'
            + cell('Name', renameInput(path, n))
            + cell('Description', input(path.concat(['doc']), t.doc), { span: 3 })
            + '<div class="pe-wide pe-note" title="A structure holds plain fields only (no structure inside a structure)">Used by '
            + (users.length ? users.map(u => '<a href="#" data-nav="' + esc(JSON.stringify(u.nav)) + '">' + esc(u.label) + '</a>').join(', ') : 'no field yet') + '</div>'
            + '</div>';
        return card('Structure', esc(n), delBtn(path, 'Remove this structure'), grid, { cls: 'pe-settings-card' })
            + fieldTable('Fields', path.concat(['fields']), 'plain fields only', true);
    }

    function viewFramesOverview() {
        return overview('Frames', 'header and footer shared by messages', btn('add-frame', 'plus', 'Frame'), {
            paths: () => keys(doc.frames).map(n => ['frames', n]),
            row: function (path) {
                const f = doc.frames[path[1]] || {};
                return { frame: path[1], parts: list(f.header).length + ' / ' + list(f.footer).length,
                         users: list(doc.messages).filter(m => m && m.frame === path[1]).length, _nav: { sec: 'frames', key: path[1] } };
            },
            columns: [colView('Frame', 'frame', d => esc(d.frame)), colView('Header / footer fields', 'parts', d => esc(d.parts)), colView('Used by', 'users', d => esc(d.users), 'number')],
            empty: 'No frames. A frame holds the header and footer several messages share.',
        });
    }
    /** 프레임 화면: 위는 설정 카드(바로 고친다, 한 줄), 아래는 머리·꼬리 필드 표 하나 */
    function viewFrame(n) {
        const f = doc.frames[n] || {}, path = ['frames', n];
        const users = list(doc.messages).map((m, i) => ({ m: m, i: i })).filter(x => x.m && x.m.frame === n);
        const grid = '<div class="pe-grid pe-grid-4">'
            + cell('Name', renameInput(path, n))
            + cell('Body size', input(path.concat(['bodySize']), f.bodySize, { v: 'numexpr', mono: true, ph: 'e.g. header.length' }), { hint: 'optional' })
            + cell('Description', input(path.concat(['doc']), f.doc), { span: 2 })
            + '<div class="pe-wide pe-note">Used by ' + (users.length ? users.map(x => '<a href="#" data-nav="' + esc(JSON.stringify({ sec: 'messages', key: x.i })) + '">' + esc(x.m.name || '(unnamed)') + '</a>').join(', ') : 'no message') + '</div>'
            + '</div>';
        return card('Frame', esc(n), delBtn(path, 'Remove this frame'), grid, { cls: 'pe-settings-card' })
            + regionTable(path, ['header', 'footer'], { fill: true });
    }

    function viewMessagesOverview() {
        return overview('Messages', 'received messages are tried in this order', btn('add-message', 'plus', 'Received', ' data-d="rx"') + btn('add-message', 'plus', 'Sent', ' data-d="tx"'), {
            paths: () => list(doc.messages).map((m, i) => ['messages', i]),
            row: function (path, i) {
                const m = doc.messages[i] || {};
                return { name: m.name || '(unnamed)', dir: m.direction, frame: m.frame || '', match: m.match || '', _nav: { sec: 'messages', key: i },
                         replies: m.direction === 'rx' ? list(m.respond).map(r => r && r.reply).filter(Boolean) : [] };
            },
            columns: [
                colNo(),
                colView('Message', 'name', d => badge(d.dir) + ' ' + esc(d.name)),
                colView('Frame', 'frame', d => esc(d.frame)),
                colView('Match', 'match', d => '<span class="pe-mono">' + esc(d.match) + '</span>'),
                colView('Replies', 'replies', d => d.replies.map(r => '<span class="pe-chip">' + esc(r) + '</span>').join('')),
            ],
            empty: 'No messages. Add a received (rx) message and the sent (tx) message that answers it.',
        });
    }

    /**
     * 메시지 화면: 위는 설정 카드(바로 고친다, 4열로 낮게), 아래는 표.
     * 받는 메시지는 필드 | 응답 규칙을 가운데 칸이 넓으면 나란히 둔다 (css .pe-split)
     */
    function viewMessage(i) {
        const m = doc.messages[i] || {}, path = ['messages', i];
        const rx = m.direction === 'rx';
        const framed = !!(m.frame && doc.frames[m.frame]);
        let body = messageSettings(path, m);
        if (framed) {
            const fr = doc.frames[m.frame];
            body += '<div class="pe-note pe-frame-line"><i class="fas fa-layer-group"></i> Header and footer come from frame '
                + '<a href="#" data-nav="' + esc(JSON.stringify({ sec: 'frames', key: m.frame })) + '">' + esc(m.frame) + '</a> — '
                + list(fr.header).map(f => esc(f.name || M.showConst(f.const))).join(' · ')
                + ' | <i>body</i> | ' + list(fr.footer).map(f => esc(f.name || M.showConst(f.const))).join(' · ') + '</div>';
        }
        const head = card(badge(m.direction) + ' ' + esc(m.name || '(unnamed)'), rx ? 'received' : 'sent', moveBtns(path) + delBtn(path, 'Remove this message'), body, { cls: 'pe-settings-card' });
        // 프레임을 쓰면 머리·꼬리는 프레임 것 — 본문만. 아니면 머리·본문·꼬리를 표 하나에
        const fields = framed ? fieldTable('Body', path.concat(['body']), '', true)
                              : regionTable(path, ['header', 'body', 'footer'], { addTo: 'body', fill: true });
        if (rx) return head + '<div class="pe-split">' + fields + rulesTable(i, m) + '</div>';
        return head + fields;
    }
    /**
     * 메시지 설정 (가운데 칸의 설정 카드). 4열로 낮게:
     *   받는 메시지  Name · Direction · Frame · Total size / Match(2) · Description(2)
     *   보내는 메시지 Name · Direction · Frame · Description / Set frame fields
     * Body transform 은 제목과 한 줄에
     */
    function messageSettings(path, m) {
        const frames = [['', '(none — own header and footer)']].concat(keys(doc.frames));
        const rx = m.direction === 'rx';
        let grid = '<div class="pe-grid pe-grid-4">'
            + cell('Name', input(path.concat(['name']), m.name, { v: 'keep' }))
            + cell('Direction', select(path.concat(['direction']), m.direction, [['rx', 'rx — the device receives'], ['tx', 'tx — the device sends']], { act: 'direction' }))
            + cell('Frame', select(path.concat(['frame']), m.frame, frames, { act: 'frame' }));
        if (rx) {
            grid += cell('Total size', input(path.concat(['size']), m.size, { v: 'num', ph: 'any' }), { hint: 'bytes, optional' })
                + cell('Match', input(path.concat(['match']), m.match, { mono: true, ph: 'e.g. header.function == 3' }), { span: 2, hint: 'this message only if true' })
                + cell('Description', input(path.concat(['doc']), m.doc), { span: 2 });
        } else {
            grid += cell('Description', input(path.concat(['doc']), m.doc));
        }
        grid += '</div>';
        if (!rx) grid += setEditor(path, m);
        grid += '<div class="pe-transform-line"><span class="pe-sub-title">Body transform</span>'
            + transformEditor(path.concat(['bodyTransform']))
            + '<span class="pe-hint">applied to the whole body after encoding, undone before reading — e.g. ascii-hex</span></div>';
        return grid;
    }

    /** tx 메시지가 프레임 필드 값을 바꾸는 표 */
    function setEditor(path, m) {
        const rows = keys(m.set).map(function (k) {
            const p = path.concat(['set', k]);
            return '<div class="pe-kv">' + objKeyInput(path.concat(['set']), k, 'header.field')
                + input(p, m.set[k], { mono: true, ph: 'expression' }) + delBtn(p) + '</div>';
        }).join('');
        return '<div class="pe-sub-title">Set frame fields <span class="pe-hint">e.g. header.function = req.header.function | 0x80</span></div>'
            + rows + '<div>' + btn('add-set', 'plus', 'Set', ' data-p="' + P(path.concat(['set'])) + '"', 'btn-outline-secondary') + '</div>';
    }
    function objKeyInput(objPath, k, ph) {
        return '<input type="text" class="form-control form-control-sm pe-mono pe-kv-key" data-objkey="' + P(objPath) + '" data-old="' + esc(k) + '" value="' + esc(k) + '" placeholder="' + esc(ph) + '" spellcheck="false" autocomplete="off">';
    }

    function rulesTable(i, m) {
        const replies = function () {
            const tx = list(doc.messages).filter(x => x && x.direction === 'tx' && x.name).map(x => x.name);
            return tx.map(n => [n, n]).concat([['none', 'none — no reply'], ['echo', 'echo — send back what came in']]);
        };
        const table = tableSlot({
            paths: () => list((doc.messages[i] || {}).respond).map((r, j) => ['messages', i, 'respond', j]),
            row: function (path) {
                const r = getAt(path) || {};
                return { when: r.when, reply: r.reply || '', _sum: JSON.stringify(r) };
            },
            columns: [
                colNo(),
                colInput('When', 'when', { mono: true, ph: 'always' }),
                colSelect('Reply', 'reply', replies, { label: v => v }),
                colView('Args · writes', '_sum', function (d) {
                    const r = getAt(d._path) || {}, chips = [];
                    keys(r.args).forEach(a => chips.push('<span class="pe-chip"><b>' + esc(a) + '</b>' + esc(r.args[a]) + '</span>'));
                    list(r.write).forEach(w => chips.push('<span class="pe-chip is-target"><b>write</b>' + esc(w.target || w.map || '?') + '</span>'));
                    if (r.delayMs) chips.push('<span class="pe-chip"><b>delay</b>' + esc(r.delayMs) + ' ms</span>');
                    return chips.join('');
                }),
                colDel('rule'),
            ],
            empty: 'No rules — the device never answers this message.',
        });
        return card('Respond', 'the first rule whose When is true runs', btn('add-rule', 'plus', 'Rule', ' data-p="' + P(['messages', i, 'respond']) + '"'), table, { flush: true, list: true });
    }

    // ── 필드 표 ──

    /** fill: 이 화면의 주 표 (남는 높이를 갖는다) */
    function fieldRow(path) {
        const f = getAt(path) || {};
        return { name: f.name, type: typeValue(f), _sum: JSON.stringify(f) };
    }
    function fieldTable(title, listPath, sub, fill) {
        const fields = list(getAt(listPath));
        const table = tableSlot({
            paths: () => list(getAt(listPath)).map((f, j) => listPath.concat([j])),
            row: fieldRow,
            columns: fieldColumns(listPath[0] === 'structures'),
            empty: 'No fields.',
        });
        const count = fields.length + (fields.length === 1 ? ' field' : ' fields') + (sub ? ' · ' + sub : '');
        return card(title, count, btn('add-field', 'plus', 'Field', ' data-p="' + P(listPath) + '"'), table, { flush: true, list: true, fill: fill });
    }
    /**
     * 여러 영역(header·body·footer)의 필드를 표 하나에 보낸 순서대로. 영역마다 구분 줄이 앞에 서고
     * 그 줄의 + 로 그 영역에 필드를 더한다. 빈 영역도 구분 줄 한 줄로 보인다 — 영역마다 표 카드를 두면
     * 빈 표도 카드·머리·쪽 넘김 높이를 다 먹어 표가 밀린다.
     * addTo: 카드 머리의 + Field 가 더할 영역 (없으면 카드 머리에 두지 않는다)
     */
    function regionTable(base, regions, opts) {
        opts = opts || {};
        const listOf = r => list(getAt(base.concat([r])));
        const table = tableSlot({
            paths: function () {
                const out = [];
                regions.forEach(function (r) {
                    out.push(base.concat([r, SEP]));
                    listOf(r).forEach((f, j) => out.push(base.concat([r, j])));
                });
                return out;
            },
            row: function (path) {
                if (path[path.length - 1] === SEP) {
                    const r = path[path.length - 2], n = listOf(r).length;
                    return { _sep: r, _i: '', _n: n };
                }
                return Object.assign(fieldRow(path), { _i: path[path.length - 1] });
            },
            sep: function (field, d) {
                const r = d._sep;
                if (field === 'name') return '<span class="pe-sep-label">' + esc(r) + '</span><span class="pe-sep-count">' + d._n + (d._n === 1 ? ' field' : ' fields') + '</span>';
                if (field === '_del') return iconBtn('add-field', 'plus', 'Add a field to the ' + r, ' data-p="' + P(base.concat([r])) + '"', 'btn-ghost-primary');
                return '';
            },
            columns: fieldColumns(false),
            empty: 'No fields.',
        });
        const sub = regions.map(r => r + ' ' + listOf(r).length).join(' · ');
        const actions = opts.addTo ? btn('add-field', 'plus', 'Field', ' data-p="' + P(base.concat([opts.addTo])) + '"') : '';
        return card('Fields', sub, actions, table, { flush: true, list: true, fill: opts.fill });
    }
    function fieldColumns(inStructure) {
        return [
                colNo(),
                colInput('Name', 'name', { v: 'keep', fmt: function (d) {
                    if (d.name) return null;
                    const f = getAt(d._path) || {};
                    // 타겟을 실은 필드는 이름이 없어도 된다 — 그 타겟 이름을 흐리게
                    if (f.target) return placeholderHtml('→ ' + f.target);
                    return placeholderHtml(f.const != null || f.type === 'PAD' ? '(unnamed)' : 'name');
                } }),
                colSelect('Type', 'type', () => [['', '(choose)']].concat(typeOptions(true, inStructure)), { ph: '(choose)', commit: (path, v) => setType(path, v),
                    fmt: d => { const f = getAt(d._path) || {}; return M.isNumberType(f.type) ? esc(M.numberText(f)) : null; } }),
                colView('Length', '_len', d => '<span class="pe-len">' + esc(M.lengthText(getAt(d._path) || {}, doc)) + '</span>'),
                colView('Value', '_val', d => valueChips(getAt(d._path) || {})),
                colDel('field'),
            ];
    }

    function valueChips(f) {
        return M.valueParts(f).map(p => '<span class="pe-chip is-' + p.k + '"><b>' + p.k + '</b>' + esc(p.t) + '</span>').join('');
    }

    function typeValue(f) {
        if (f.structure) return 'struct:' + f.structure;
        if (f.type) return f.type;
        return '';
    }
    /**
     * 필드 타입 선택지(묶음). short: 표 안 — 코드만 (속성 모달은 긴 글).
     * Structure 묶음은 정의한 구조체 — 고르면 "structure": 이름 으로 적는다. 구조체 안에서는 없다 (한 단계, 형식 6.10)
     */
    function typeOptions(short, inStructure) {
        const L = (code, long) => short ? code : long;
        return [
            // 숫자형은 형식만 — 크기·바이트 순서는 속성 (형식 6.1)
            { group: 'Number', options: [['UINT', short ? 'UINT' : 'UINT — unsigned integer'], ['INT', short ? 'INT' : 'INT — signed integer'], ['FLOAT', short ? 'FLOAT' : 'FLOAT — floating point']] },
            { group: 'Text and codes', options: [['BCD', L('BCD', 'BCD — packed decimal')], ['TEXT', L('TEXT', 'TEXT — characters')], ['TEXTNUM', L('TEXTNUM', 'TEXTNUM — decimal as text')], ['HEXNUM', L('HEXNUM', 'HEXNUM — hex as text')]] },
            { group: 'Raw', options: [['BYTES', L('BYTES', 'BYTES — raw bytes')], ['PAD', L('PAD', 'PAD — filler')], ['BITS', L('BITS', 'BITS — bit fields')]] },
            { group: 'Structure', options: inStructure ? [] : keys(doc.structures).map(t => ['struct:' + t, short ? t : t + ' — structure']) },
        ].filter(g => g.options.length);
    }
    function typeSelect(path, f, short) {
        const cur = typeValue(f);
        const html = (cur === '' ? '<option value="" selected>(choose)</option>' : '') + CtsSelect.optionsHtml(typeOptions(short, path[0] === 'structures'), cur).replace(cur === '' ? /^<option value="" selected><\/option>/ : /^$/, '');
        return '<select class="form-select form-select-sm" data-act="type" data-p="' + P(path) + '">' + html + '</select>';
    }

    // ── 시험·JSON ──

    function viewTests() {
        const blocks = list(doc.tests).map(function (t, i) {
            t = t || {};
            const path = ['tests', i];
            const noReply = t.expect == null;
            return '<div class="pk" data-row="' + P(path) + '">'
                + '<div class="pk-head"><span class="pk-tag">Test ' + i + '</span>'
                + '<div class="pe-head-name pe-grow">' + input(path.concat(['name']), t.name, { v: 'keep', ph: 'What this checks' }) + '</div>'
                + testBadge(i)
                + '<span class="pk-del">' + moveBtns(path) + delBtn(path, 'Remove this test') + '</span></div>'
                + testDetail(i)
                + '<div class="pe-block"><div class="pe-grid">'
                + cell('Send', input(path.concat(['send']), t.send, { v: 'hex', mono: true, ph: '01 03 00 00 00 0A C5 CD' }), { wide: true })
                + cell('Expect', '<div class="pe-expect">' + input(path.concat(['expect']), t.expect, { v: 'hex', mono: true, ph: noReply ? 'no reply' : 'bytes the device sends' })
                    + '<label class="form-check form-check-inline mb-0"><input class="form-check-input" type="checkbox" data-act="noreply" data-p="' + P(path) + '"' + (noReply ? ' checked' : '') + '>'
                    + '<span class="form-check-label">No reply</span></label></div>', { wide: true })
                + cell('Server Data before', textarea(path.concat(['data']), linesOf(t.data), { v: 'lines', mono: true, rows: rowsFor(t.data), ph: 'target = value' }), { hint: 'one per line' })
                + cell('Expected writes', textarea(path.concat(['expectWrites']), linesOf(t.expectWrites), { v: 'lines', mono: true, rows: rowsFor(t.expectWrites), ph: 'target = value' }))
                + cell('Parameters', textarea(path.concat(['params']), linesOf(t.params), { v: 'lines', mono: true, rows: 1, ph: 'unitId = 1 (defaults if empty)' }), { wide: true })
                + '</div></div></div>';
        }).join('');
        const body = blocks ? '<div class="pk-list">' + blocks + '</div>' : empty('No tests. A test sends bytes and checks the reply; all tests run whenever the document is saved.');
        const actions = btn('run-tests', 'play', 'Run tests', '', 'btn-outline-secondary') + btn('add-test', 'plus', 'Test');
        return card('Tests', 'run in order on one device · values and writes carry over · all run when you save', actions, body, { list: true });
    }

    /** 마지막 실행 결과 (서버의 엔진이 돌린 것). 고치면 지운다 */
    function testBadge(i) {
        const r = testResults && testResults[i];
        if (!r) return '';
        return '<span class="pe-test ' + (r.ok ? 'is-pass' : 'is-fail') + '">' + (r.ok ? 'Pass' : 'Fail') + '</span>';
    }
    function testDetail(i) {
        const r = testResults && testResults[i];
        if (!r || r.ok) return '';
        return '<div class="pe-test-detail"><div><b>Expected</b> <code>' + esc(r.expected || '') + '</code></div>'
            + '<div><b>Actual</b> <code>' + esc(r.actual || '') + '</code></div>'
            + (r.message ? '<div>' + esc(r.message) + '</div>' : '') + '</div>';
    }

    /** 저장하지 않고 서버 엔진으로 컴파일·시험만 돌린다 */
    function runTests() {
        fetch('/api/protocols/check', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ document: doc, id: defId }) })
            .then(r => r.json())
            .then(function (b) {
                testResults = {};
                (b.tests || []).forEach(t => { testResults[t.index] = t; });
                serverProblems = (b.problems || []).filter(p => p.level === 'error' && !/already named/.test(p.text))
                    .map(p => ({ level: 'error', at: p.at || [], message: p.text }));
                (b.tests || []).filter(t => !t.ok).forEach(t => serverProblems.push({ level: 'error', at: ['tests', t.index],
                    message: 'Test "' + t.name + '" failed' + (t.message ? ': ' + t.message : '.') }));
                validateNow();
                if (sel.sec === 'tests') renderMain();
                const tests = b.tests || [];
                const failed = tests.filter(t => !t.ok).length;
                if (!tests.length && serverProblems.length) flash('Fix the problems first.');
                else flash(tests.length ? (failed ? failed + ' of ' + tests.length + ' tests failed' : 'All ' + tests.length + ' tests pass') : 'No tests to run.');
            })
            .catch(e => alert('Running the tests failed: ' + e.message));
    }

    function rowsFor(obj) { return Math.min(8, Math.max(2, keys(obj).length)); }

    function viewSource() {
        const actions = btn('format-source', 'align-left', 'Format', '', 'btn-outline-secondary')
            + btn('copy-source', 'copy', 'Copy', '', 'btn-outline-secondary')
            + btn('apply-source', 'check', 'Apply');
        return card('JSON', 'edit directly, then Apply', actions,
            '<textarea id="pe-source" class="pe-source" spellcheck="false" wrap="off"></textarea><div class="pe-source-status" id="pe-source-status"></div>', { flush: true, cls: 'pe-grow' });
    }

    // ── 오른쪽: 속성 ──────────────────────────────────────────

    /** 속성 모달의 내용. 연 줄이 없어졌으면(모달의 × 로 지웠다) 고친 것을 둔 채 닫는다 */
    function renderInspector() {
        const box = $('pe-inspector-body');
        destroyTables(box);
        if (window.CtsSelect) CtsSelect.destroyAll(box);
        if (!sel.item || getAt(sel.item) == null) {
            box.innerHTML = '';
            if (props) closeProps(true);
            return;
        }
        drawInspector();
        mountTables();
        if (window.CtsSelect) CtsSelect.mountAll(box);
    }
    function drawInspector() {
        const box = $('pe-inspector-body'), title = $('pe-inspector-title'), actions = $('pe-inspector-actions');
        const path = sel.item;
        const target = path ? getAt(path) : null;
        const show = function (t, what, body) {
            title.innerHTML = t;
            // 순서가 있는 목록의 줄만 옮긴다 (파라미터·상수는 이름으로 찾는다)
            // 지우기는 휴지통 — 옆의 닫기(×)와 헷갈리지 않게
            actions.innerHTML = (typeof path[path.length - 1] === 'number' ? moveBtns(path) : '')
                + iconBtn('del', 'trash-alt', 'Remove this ' + what, ' data-p="' + P(path) + '"', 'btn-ghost-danger');
            box.innerHTML = body;
        };
        // 상수의 값은 수·글자라 아래의 객체 검사보다 먼저
        if (path && target != null && path[0] === 'consts' && path.length === 2) return show('Constant <span class="pk-sub">' + esc(path[1]) + '</span>', 'constant', constInspector(path));
        if (!path || target == null || typeof target !== 'object') {
            title.innerHTML = 'Properties';
            actions.innerHTML = '';
            box.innerHTML = '';
            return;
        }
        if (path[0] === 'params' && path.length === 2) return show('Parameter <span class="pk-sub">' + esc(path[1]) + '</span>', 'parameter', paramInspector(path, target));
        if (path[0] === 'messages' && path[2] === 'respond') return show('Respond rule <span class="pk-sub">#' + path[3] + '</span>', 'rule', ruleInspector(path, target));
        if (path[0] === 'targets' && path[2] === 'items') return show('Target item <span class="pk-sub">#' + path[3] + '</span>', 'item', itemInspector(path, target));
        if (typeof path[path.length - 1] === 'number') return show('Field <span class="pk-sub">' + esc(target.name || '#' + path[path.length - 1]) + '</span>', 'field', fieldInspector(path, target));
        title.innerHTML = 'Properties';
        actions.innerHTML = '';
        box.innerHTML = '';
    }

    /** 파라미터: 장비마다 정하는 값 — 종류와 범위만 (형식 11) */
    function paramInspector(path, p) {
        return section('Parameter', '<div class="pe-grid">'
            + cell('Name', renameInput(path, path[1]), { hint: 'used as $name' })
            + cell('Value', select(path.concat(['value']), p.value || 'integer', [['integer', 'integer'], ['float', 'float']]))
            + cell('Default', input(path.concat(['default']), p.default, { v: 'num', mono: true }))
            + cell('Min', input(path.concat(['min']), p.min, { v: 'num', mono: true, ph: 'none' }))
            + cell('Max', input(path.concat(['max']), p.max, { v: 'num', mono: true, ph: 'none' }))
            + cell('Description', input(path.concat(['doc']), p.doc), { wide: true }) + '</div>');
    }
    /** 상수: 비워도 상수는 남는다 (지우기는 ×) */
    function constInspector(path) {
        return section('Constant', '<div class="pe-grid">'
            + cell('Name', renameInput(path, path[1]), { hint: 'used as $name' })
            + cell('Value', input(path, getAt(path), { v: 'constval', mono: true, ph: 'e.g. 0x02' })) + '</div>');
    }

    function section(title, body) { return body ? '<div class="pe-sec"><div class="pe-sec-title">' + esc(title) + '</div>' + body + '</div>' : ''; }

    function fieldInspector(path, f) {
        const k = M.kindOf(f, doc);
        const attrs = M.KIND_ATTRS[k] || [];
        const on = a => attrs.indexOf(a) >= 0;
        const checks = [['', '(none)']].concat(keys(doc.checksums));
        let html = section('Field', '<div class="pe-grid">'
            + cell('Name', input(path.concat(['name']), f.name, { v: 'keep', ph: f.target ? 'optional — carries ' + f.target : '' }), { hint: f.target ? 'optional' : '' })
            + cell('Type', typeSelect(path, f))
            + cell('Description', input(path.concat(['doc']), f.doc), { wide: true }) + '</div>');

        let size = '';
        if (on('size') && k === 'numeric') size += cell('Size (bytes)', numberSizeSelect(path, f));
        else if (on('size')) size += cell(k === 'HEXNUM' ? 'Size (characters)' : 'Size (bytes)', input(path.concat(['size']), f.size, { v: 'numexpr', mono: true, ph: k === 'structure' ? 'from the fields' : 'number or expression' }));
        if (on('terminator')) size += cell('Terminator', input(path.concat(['terminator']), f.terminator, { v: 'esc', mono: true, ph: '\\r or ,' }));
        size += cell('Count (array)', input(path.concat(['count']), f.count, { v: 'numexpr', mono: true, ph: 'one' }));
        html += section('Length', '<div class="pe-grid">' + size + '</div>');

        let val = '';
        const container = k === 'BITS' || k === 'structure';
        if (container) {
            // 값은 안쪽 필드가 갖는다. 이 필드 자체에는 있는지 없는지만
            val = cell('When', input(path.concat(['when']), f.when, { mono: true, ph: 'always present' }), { wide: true, hint: 'field exists only if true' });
        } else if (k !== 'PAD') {
            val += cell('Const', input(path.concat(['const']), f.const, { v: 'const', mono: true, text: k === 'TEXT', ph: k === 'TEXT' ? 'text, e.g. #' : 'e.g. 0x02' }), { hint: 'sent as is, checked on receive' })
                + cell('Value', input(path.concat(['value']), f.value, { mono: true, ph: 'expression' }), { hint: 'when sending' })
                + cell('Target', input(path.concat(['target']), f.target, { list: 'pe-target-names', ph: 'Server Data key' }), { hint: 'read when sending' })
                + cell('Checksum', select(path.concat(['checksum']), f.checksum, checks))
                + cell('Expect', input(path.concat(['expect']), f.expect, { mono: true, ph: 'e.g. this >= 1' }), { wide: true, hint: 'on receive, else not this message' })
                + cell('When', input(path.concat(['when']), f.when, { mono: true, ph: 'always present' }), { wide: true, hint: 'field exists only if true' });
        }
        html += section('Value', val ? '<div class="pe-grid">' + val + '</div>' : '');

        html += section('Format', formatCells(path, f, k, allPinned(f) ? ['bitOrder'] : null));

        if (k === 'BITS') html += section('Bit fields', bitsEditor(path, f));
        if (k === 'structure') html += section('Structure', '<div class="pe-note">' + list((doc.structures[f.structure] || {}).fields).length + ' fields in '
            + '<a href="#" data-nav="' + esc(JSON.stringify({ sec: 'structures', key: f.structure })) + '">' + esc(f.structure) + '</a> — edit them there.</div>');
        if (on('transform')) html += section('Transform', transformEditor(path.concat(['transform'])));
        return html;
    }

    /** 타입별 형식 칸 (필드·타겟 항목 공통). k: M.kindOf 의 모양 */
    function formatCells(path, f, k, skip) {
        const attrs = (M.KIND_ATTRS[k] || []).filter(a => !skip || skip.indexOf(a) < 0);
        const on = a => attrs.indexOf(a) >= 0;
        let fmt = '';
        if (on('encoding')) fmt += cell('Encoding', select(path.concat(['encoding']), f.encoding, [['', 'document default (' + ((doc.defaults || {}).encoding || 'ascii') + ')']].concat(M.ENCODINGS)));
        if (on('decimals')) fmt += cell('Decimals', input(path.concat(['decimals']), f.decimals, { v: 'num', ph: '0' }));
        if (on('point')) fmt += cell('Decimal point', select(path.concat(['point']), f.point, [['', 'written (default)'], ['false', 'implied']], { v: 'bool' }));
        if (on('sign')) fmt += cell('Sign', select(path.concat(['sign']), f.sign, [['', 'auto — minus only'], ['always', 'always + or -'], ['none', 'none']]));
        if (on('pad')) fmt += cell('Pad character', input(path.concat(['pad']), f.pad, { v: 'esc', mono: true, ph: k === 'TEXTNUM' ? '0' : 'space' }));
        if (on('align')) fmt += cell('Align', select(path.concat(['align']), f.align, [['', k === 'TEXTNUM' ? 'right (default)' : 'left (default)'], ['left', 'left'], ['right', 'right']]));
        if (on('case')) fmt += cell('Letters', select(path.concat(['case']), f.case, [['', 'upper (default)'], ['lower', 'lower']]));
        if (on('byteOrder') && k === 'numeric') { if (f.size > 1) fmt += cell('Byte order', select(path.concat(['byteOrder']), f.byteOrder, byteOrderOptions(f.size))); }
        else if (on('byteOrder')) fmt += cell('Byte order', select(path.concat(['byteOrder']), f.byteOrder, [['', 'big (default)'], ['little', 'little']]));
        if (on('bitOrder')) fmt += cell('Bit order', select(path.concat(['bitOrder']), f.bitOrder, [['', 'lsb first (default)'], ['msb', 'msb first']]));
        if (on('fill')) fmt += cell('Fill byte', input(path.concat(['fill']), f.fill, { v: 'num', ph: '0' }));
        if (on('scale')) fmt += cell('Scale', input(path.concat(['scale']), f.scale, { v: 'num', ph: '1' }), { hint: 'value = raw × scale' });
        if (on('offset')) fmt += cell('Offset', input(path.concat(['offset']), f.offset, { v: 'num', ph: '0' }), { hint: 'value = raw × scale + offset' });
        if (on('onOverflow')) fmt += cell('On overflow', select(path.concat(['onOverflow']), f.onOverflow, [['', 'document default'], ['error', 'error — no reply'], ['clamp', 'clamp'], ['wrap', 'wrap']]));
        return fmt ? '<div class="pe-grid">' + fmt + '</div>' : '';
    }

    /**
     * BITS 의 비트 필드 표 (필드·타겟 항목 공통). 자리는 줄 순서가 아니라 Bit 칸이 정한다 (형식 6.7):
     * 값의 맨 아래 비트가 0. 줄은 자리 순으로 보인다. 어느 필드도 차지하지 않은 비트는 0 으로 보낸다.
     */
    /** 숫자형의 크기: 형식이 가질 수 있는 바이트 수만 */
    function numberSizeSelect(path, f) {
        return select(path.concat(['size']), f.size, M.numberSizes(f.type).map(n => [String(n), n + (n === 1 ? ' byte' : ' bytes') + ' · ' + (n * 8) + ' bits']), { v: 'num' });
    }
    /** 그 크기가 가질 수 있는 바이트 순서 (A 가 가장 높은 바이트) */
    const ORDER_TEXT = { 2: { big: 'A B', little: 'B A' }, 3: { big: 'A B C', little: 'C B A' },
        4: { big: 'A B C D', little: 'D C B A', 'word-swap': 'C D A B', 'byte-swap': 'B A D C' },
        8: { big: 'A B C D E F G H', little: 'H G F E D C B A', 'word-swap': 'G H E F C D A B', 'byte-swap': 'B A D C F E H G' } };
    function byteOrderOptions(size) {
        return M.byteOrdersFor(size).map(o => [o === 'big' ? '' : o, o + (o === 'big' ? ' (default)' : '') + ' — ' + ((ORDER_TEXT[size] || {})[o] || '')]);
    }

    function bitsEditor(path, f) {
        const fieldsPath = path.concat(['fields']);
        const total = (typeof f.size === 'number' ? f.size : 0) * 8;
        const layout = M.bitLayout(f);
        // 겹친 비트는 한 번만 센다 (겹침은 문제 목록이 알린다)
        const seen = {};
        layout.forEach(r => { for (let x = r.pos; x < r.pos + r.bits; x++) if (x >= 0 && x < total) seen[x] = true; });
        const usedBits = Object.keys(seen).length;
        const table = tableSlot({
            auto: true,
            // 자리 순으로 (같은 자리면 원래 순서)
            paths: function () {
                const ff = getAt(path) || {};
                const lay = M.bitLayout(ff);
                return list(ff.fields).map((b, j) => j).sort((x, y) => (lay[x].pos - lay[y].pos) || (x - y)).map(j => fieldsPath.concat([j]));
            },
            row: function (p2) {
                const b = getAt(p2) || {}, r = M.bitLayout(getAt(path) || {})[p2[p2.length - 1]] || { pos: 0, bits: 1 };
                return { bit: r.pos, bits: r.bits, name: b.name, target: b.target, const: b.const };
            },
            columns: [
                colInput('Bit', 'bit', { num: true, commit: (p2, v) => setBitField(path, p2, 'bit', v),
                    fmt: d => d.bits > 1 ? esc(d.bit + '–' + (d.bit + d.bits - 1)) : null }),
                colInput('Bits', 'bits', { num: true, commit: (p2, v) => setBitField(path, p2, 'bits', v) }),
                // 이름은 식에서 부를 때만 — 타겟이나 고정값이 있으면 비워 둔다 (옆 칸에 보인다)
                colInput('Name', 'name', { v: 'keep', fmt: d => d.name ? null : placeholderHtml(d.target || d.const != null ? '' : 'name') }),
                // 비트 필드는 타겟이나 고정값 중 하나다
                colInput('Target', 'target', { list: 'pe-target-names', ph: 'Server Data key', when: d => d.const == null || d.const === '' }),
                colInput('Const', 'const', { v: 'scalar', mono: true, num: true, ph: '0', when: d => !d.target }),
                colDel('bit field'),
            ],
            empty: 'No bit fields.',
        });
        return table
            + '<div class="pe-mini-foot">' + btn('add-bit', 'plus', 'Bit field', ' data-p="' + P(fieldsPath) + '"', 'btn-outline-secondary')
            + '<span class="pe-note">' + usedBits + ' of ' + total + ' bits used · bit 0 is the lowest · unused bits are 0</span></div>';
    }

    /** 비트 필드마다 지금 자리를 bit 로 적어 둔다 — 그 뒤로는 순서·다른 필드의 폭이 자리를 바꾸지 않는다 */
    function pinBits(bitsPath) {
        const f = getAt(bitsPath) || {};
        M.bitLayout(f).forEach(function (r, j) { f.fields[j].bit = r.pos; });
        delete f.bitOrder;   // 자리를 모두 적으면 bitOrder 는 뜻이 없다
    }
    /** Bit·Bits 칸을 고친다: 자리를 모두 적은 뒤 그 칸만 바꾸고, 자리 순으로 줄을 맞춘다 */
    function setBitField(bitsPath, fieldPath, key, v) {
        pinBits(bitsPath);
        const b = getAt(fieldPath);
        const n = Number(v);
        if (v === '' || v == null || isNaN(n)) { if (key === 'bits') b.bits = 1; }
        else b[key] = Math.max(key === 'bits' ? 1 : 0, Math.floor(n));
        const f = getAt(bitsPath);
        f.fields.sort((x, y) => x.bit - y.bit);
        rerender();
    }
    /** 비트 필드가 모두 자리를 적었는가 (그러면 bitOrder 는 뜻이 없다) */
    function allPinned(f) { return list(f && f.fields).length > 0 && list(f.fields).every(b => b && typeof b.bit === 'number'); }
    /** 비어 있는 가장 낮은 비트 */
    function freeBit(f) {
        const used = {};
        M.bitLayout(f).forEach(r => { for (let x = r.pos; x < r.pos + r.bits; x++) used[x] = true; });
        let x = 0;
        while (used[x]) x++;
        return x;
    }

    /** 변환 목록 편집 (필드의 transform, 메시지의 bodyTransform). listPath = 목록의 경로 */
    function transformEditor(listPath) {
        const rows = list(getAt(listPath)).map(function (t, j) {
            const p = listPath.concat([j]);
            return '<div class="pe-kv">' + select(p.concat(['op']), t.op, [['ascii-hex', 'ascii-hex'], ['add', 'add to each byte'], ['xor', 'xor each byte']])
                + (t.op === 'ascii-hex' ? select(p.concat(['case']), t.case, [['', 'upper'], ['lower', 'lower']]) : input(p.concat(['value']), t.value, { v: 'num', ph: 'value' }))
                + delBtn(p) + '</div>';
        }).join('');
        return rows + btn('add-transform', 'plus', 'Transform', ' data-p="' + P(listPath) + '"', 'btn-outline-secondary');
    }

    function ruleInspector(path, r) {
        const tx = list(doc.messages).filter(x => x && x.direction === 'tx' && x.name).map(x => x.name);
        const replies = tx.map(n => [n, n]).concat([['none', 'none — no reply'], ['echo', 'echo']]);
        let html = section('Rule', '<div class="pe-grid">'
            + cell('When', input(path.concat(['when']), r.when, { mono: true, ph: 'always' }), { wide: true })
            + cell('Reply', select(path.concat(['reply']), r.reply, replies))
            + cell('Delay', input(path.concat(['delayMs']), r.delayMs, { v: 'num', ph: '0' }), { hint: 'ms' })
            + cell('Name', input(path.concat(['name']), r.name, { ph: 'optional' }), { wide: true, hint: 'Error Simulations can target it' })
            + '</div>');
        const args = keys(r.args).map(a => '<div class="pe-kv">' + objKeyInput(path.concat(['args']), a, 'name')
            + input(path.concat(['args', a]), r.args[a], { v: 'scalar', mono: true, ph: 'value or expression' }) + delBtn(path.concat(['args', a])) + '</div>').join('');
        html += section('Args for the reply', args + btn('add-arg', 'plus', 'Arg', ' data-p="' + P(path.concat(['args'])) + '"', 'btn-outline-secondary')
            + '<div class="pe-note">Read in the reply as args.name</div>');
        const writes = list(r.write).map(function (w, j) {
            const p = path.concat(['write', j]);
            const isMap = w.map != null;
            return '<div class="pe-write">'
                + '<div class="pe-kv">' + select(null, isMap ? 'map' : 'target', [['target', 'to a target'], ['map', 'to a sequence']], { act: 'write-kind' }).replace('<select', '<select data-p="' + P(p) + '"')
                + delBtn(p) + '</div>'
                + (isMap
                    ? '<div class="pe-grid">' + cell('Sequence', select(p.concat(['map']), w.map, keys(doc.targets).filter(g => (doc.targets[g] || {}).kind === 'sequence')))
                        + cell('Address', input(p.concat(['address']), w.address, { mono: true, ph: 'body.start' }))
                        + cell('Bytes', input(p.concat(['bytes']), w.bytes, { mono: true, ph: 'body.values' }), { wide: true })
                        + cell('Repeat', input(p.concat(['count']), w.count, { mono: true, ph: 'once' }), { hint: 'i = 0 … n−1' }) + '</div>'
                    : '<div class="pe-grid">' + cell('Target', input(p.concat(['target']), w.target, { list: 'pe-target-names' }))
                        + cell('Value', input(p.concat(['value']), w.value, { mono: true, ph: 'body.setpoint' })) + '</div>')
                + '</div>';
        }).join('');
        html += section('Write to Server Data', writes + btn('add-write', 'plus', 'Write', ' data-p="' + P(path.concat(['write'])) + '"', 'btn-outline-secondary')
            + '<div class="pe-note">Runs before the reply, as a protocol write (locks and stop-on-control apply).</div>');
        return html;
    }

    function itemInspector(path, it) {
        const g = doc.targets[path[1]] || {};
        if (g.kind === 'named') {
            const v = inferredValue(it.target);
            return section('Target', '<div class="pe-grid">'
                + cell('Target', input(path.concat(['target']), it.target, { list: 'pe-target-names', ph: 'Server Data key' }), { wide: true })
                + cell('Value', select(path.concat(['value']), it.value, VALUE_OPTIONS), { hint: it.value ? '' : (v ? 'fields carry ' + v : 'no field carries it yet') })
                + cell('Access', select(path.concat(['access']), it.access, [['', 'read only'], ['rw', 'read and write']]))
                + cell('Description', input(path.concat(['doc']), it.doc), { wide: true }) + '</div>')
                + '<div class="pe-note">A named target is a Server Data value. Its representation (type, size, scale…) is on each message field that carries it, '
                + 'so the same value can be sent differently in different messages.</div>';
        }
        const kind = itemKind(it);
        let grid = cell('Item', select(path, kind === 'bits' ? 'target' : kind, [['target', 'target — a Server Data value'], ['const', 'const — fixed content'], ['skip', 'skip — unused slots']], { act: 'item-kind' }), { wide: true });
        if (kind === 'skip') {
            grid += cell('Slots', input(path.concat(['skip']), it.skip, { v: 'num' }));
        } else {
            if (kind === 'target') grid += cell('Target', input(path.concat(['target']), it.target, { list: 'pe-target-names', ph: 'Server Data key' }), { wide: true });
            else if (kind === 'const') grid += cell('Const', input(path.concat(['const']), it.const, { v: 'scalar', mono: true }), { wide: true });
            grid += cell('Type', select(path.concat(['type']), it.type, itemTypes(g.kind), { act: 'item-type' }));
            const k = it.type ? M.kindOf({ type: it.type }, doc) : null;
            if (k === 'numeric') grid += cell('Size', numberSizeSelect(path, it));
            else if (k && (M.KIND_ATTRS[k] || []).indexOf('size') >= 0) grid += cell('Size', input(path.concat(['size']), it.size, { v: 'num', ph: 'bytes' }), { hint: k === 'HEXNUM' ? 'characters' : 'bytes' });
            if (kind !== 'const') grid += cell('Access', select(path.concat(['access']), it.access, [['', 'read only'], ['rw', 'read and write']]));
        }
        if (g.kind === 'sequence') grid += cell('At address', input(path.concat(['at']), it.at, { v: 'num', ph: 'next' }));
        grid += cell('Description', input(path.concat(['doc']), it.doc), { wide: true });
        let html = section('Item', '<div class="pe-grid">' + grid + '</div>');
        if (kind !== 'skip' && it.type) {
            const k = M.kindOf({ type: it.type }, doc);
            // 순차 타겟은 크기가 정해져 있어야 하므로 끝 글자(terminator)를 두지 않는다
            if (g.kind !== 'sequence' && (k === 'TEXT' || k === 'TEXTNUM')) {
                html += section('Length', '<div class="pe-grid">' + cell('Terminator', input(path.concat(['terminator']), it.terminator, { v: 'esc', mono: true, ph: '\\r or ,' })) + '</div>');
            }
            html += section('Format', formatCells(path, it, k, ['size', 'fields'].concat(allPinned(it) ? ['bitOrder'] : [])));
            if (k === 'BITS') html += section('Bit fields', bitsEditor(path, it)
                + '<div class="pe-note mt-1">Each bit field with a target is its own Server Data value. The first row is the lowest bit.</div>');
        }
        return html;
    }

    // ── 문제 ──────────────────────────────────────────────────

    function scheduleValidate() {
        clearTimeout(validateTimer);
        validateTimer = setTimeout(validateNow, 150);
    }
    function validateNow() {
        problems = M.validate(doc).concat(serverProblems);
        const errors = problems.filter(p => p.level === 'error').length, warns = problems.length - errors;
        // 상단 바의 Problems 버튼: 글은 개수, 색과 아이콘은 가장 나쁜 것
        $('pe-problem-count').textContent = errors || warns
            ? (errors ? errors + ' error' + (errors === 1 ? '' : 's') : '') + (errors && warns ? ' · ' : '')
              + (warns ? warns + ' warning' + (warns === 1 ? '' : 's') : '')
            : 'No problems';
        const pbtn = $('pe-problems-btn');
        pbtn.classList.toggle('pe-problems-btn-error', errors > 0);
        pbtn.classList.toggle('pe-problems-btn-warn', !errors && warns > 0);
        pbtn.classList.toggle('is-clear', !errors && !warns);
        pbtn.title = errors || warns ? 'Problems in this document' : 'No problems in this document';
        $('pe-problems-icon').className = 'fas fa-' + (errors ? 'times-circle' : warns ? 'exclamation-triangle' : 'check-circle');
        $('pe-problems').innerHTML = problems.length
            ? problems.map(p => '<div class="pe-prob is-' + p.level + '" data-at="' + P(p.at) + '"><i class="fas fa-' + (p.level === 'error' ? 'times-circle' : 'exclamation-triangle') + '"></i><span>' + esc(p.message) + '</span></div>').join('')
            : empty('Everything checks out.');
        markProblems();
    }
    function worst(prefix) {
        let lv = null;
        for (const p of problems) {
            if (startsWith(p.at, prefix)) { if (p.level === 'error') return 'error'; lv = 'warn'; }
        }
        return lv;
    }
    function markProblems() {
        document.querySelectorAll('#pe-outline [data-at]').forEach(function (n) {
            // 합친 섹션의 항목은 여러 경로의 문제를 모은다
            const lv = (n.dataset.ats ? JSON.parse(n.dataset.ats) : [JSON.parse(n.dataset.at)]).map(worst)
                .reduce((a, b) => a === 'error' || b === 'error' ? 'error' : (a || b), null);
            n.classList.toggle('has-error', lv === 'error');
            n.classList.toggle('has-warn', lv === 'warn');
        });
        document.querySelectorAll('#pe-main [data-row]').forEach(function (n) {
            const lv = worst(JSON.parse(n.dataset.row));
            n.classList.toggle('is-bad', lv === 'error');
            n.classList.toggle('is-warn', lv === 'warn');
        });
    }
    /** 문제의 경로로 가서 그 줄을 고른다 */
    function goAt(at) {
        const sec = at[0];
        // 합친 페이지의 카드: 파라미터·상수의 줄은 속성 모달로, 체크섬·프레임 경계는 그 카드로
        if (PAGE_OF[sec]) {
            go(sec);
            const item = at.slice(0, 2);
            if ((sec === 'params' || sec === 'consts') && at.length >= 2 && getAt(item) != null) { openProps(item); return; }
            const row = document.querySelector('#pe-main [data-row="' + CSS.escape(JSON.stringify(item)) + '"]');
            if (row) row.scrollIntoView({ block: 'nearest' });
            return;
        }
        if (!SECTIONS.some(s => s.sec === sec)) { go('general'); return; }
        if (KEYED.indexOf(sec) < 0 || at.length < 2) {
            go(sec);
            const row = document.querySelector('#pe-main [data-row="' + CSS.escape(JSON.stringify(at.slice(0, 2))) + '"]');
            if (row) row.scrollIntoView({ block: 'nearest' });
            return;
        }
        const key = at[1];
        // 가장 깊은 "목록 안 줄" 까지. BITS 안쪽 줄은 그 BITS 필드를 고른다
        let item = null;
        for (let k = 2; k < at.length; k++) {
            if (typeof at[k] !== 'number') continue;
            const parent = getAt(at.slice(0, k - 1));
            if (at[k - 1] === 'fields' && parent && parent.type === 'BITS') break;
            item = at.slice(0, k + 1);
        }
        // 줄이 아닌 곳(메시지의 Match·프레임의 Body size 등)은 설정 카드의 그 칸으로
        go(sec, key, item ? { item: item } : { focus: at });
        if (item) {
            const row = document.querySelector('#pe-main [data-row="' + CSS.escape(JSON.stringify(item)) + '"]');
            if (row) row.scrollIntoView({ block: 'nearest' });
        }
    }

    // ── 고치기 ────────────────────────────────────────────────

    function readValue(el) {
        return parseRaw(el.dataset.v || 'text', el.value, JSON.parse(el.dataset.p));
    }
    /** 입력 글 → 값. kind 는 칸의 방식, path 는 값이 들어갈 자리 (const 의 해석에 쓴다) */
    function parseRaw(kind, raw, path) {
        switch (kind) {
            case 'keep':    return { set: true, v: raw };
            case 'num': {
                if (raw.trim() === '') return { set: false };
                const n = Number(raw);
                return { set: true, v: isNaN(n) ? raw : n };
            }
            case 'numexpr': if (raw.trim() === '') return { set: false }; return { set: true, v: /^-?\d+$/.test(raw.trim()) ? parseInt(raw, 10) : raw };
            case 'scalar':  return raw === '' ? { set: false } : { set: true, v: scalar(raw) };
            case 'constval': return { set: true, v: raw === '' ? '' : scalar(raw) };   // 상수는 비워도 남는다
            case 'esc':     return raw === '' ? { set: false } : { set: true, v: unescText(raw) };
            case 'bool':    return raw === '' ? { set: false } : { set: true, v: raw === 'true' };
            case 'csv':     return raw.trim() === '' ? { set: false } : { set: true, v: raw.split(',').map(s => s.trim()).filter(Boolean) };
            case 'lines': { const o = parseLines(raw); return keys(o).length ? { set: true, v: o } : { set: false }; }
            case 'hex':     return raw.trim() === '' ? { set: false } : { set: true, v: raw.trim().replace(/\s+/g, ' ').toUpperCase() };
            case 'const': {
                if (raw === '') return { set: false };
                const k = M.kindOf(getAt(path.slice(0, -1)), doc);
                if (k === 'TEXT') return { set: true, v: unescText(raw) };
                if (k === 'BYTES') return { set: true, v: raw };
                return { set: true, v: scalar(raw) };
            }
            default:        return raw === '' ? { set: false } : { set: true, v: raw };
        }
    }

    function write(el) {
        const path = JSON.parse(el.dataset.p);
        const r = readValue(el);
        if (r.set) setAt(path, r.v);
        else delAt(path);
        // 시험의 기대 바이트를 적으면 "응답 없음" 이 풀린다
        if (path[0] === 'tests' && path[2] === 'expect') {
            const t = doc.tests[path[1]];
            if (!r.set) t.expect = null;
            const box = document.querySelector('[data-act="noreply"][data-p="' + CSS.escape(JSON.stringify(path.slice(0, 2))) + '"]');
            if (box) box.checked = !r.set;
        }
        // 같은 값을 보이는 다른 입력칸(가운데 카드 ↔ 속성 모달)도 맞춘다
        document.querySelectorAll('[data-p="' + CSS.escape(el.dataset.p) + '"]').forEach(function (other) {
            if (other === el || other.tagName === 'SELECT' || other.dataset.v !== el.dataset.v) return;
            if (other.value !== el.value) other.value = el.value;
        });
        afterEdit(path, 'input');
    }

    /**
     * 값 하나를 고친 뒤. from: 'input'(입력칸) / 'table'(표의 칸)
     * 표의 행은 문서에서 다시 만들고, 표에서 고쳤으면 그 줄의 속성 모달도 다시 그린다.
     */
    function afterEdit(path, from) {
        if (path[path.length - 1] === 'name' || (path[0] === 'messages' && path.length === 3)) renderOutline();
        refreshTables();
        if (from === 'table' && sel.item && startsWith(path, sel.item)) renderInspector();
        if (sel.item && startsWith(path, sel.item) && path.length === sel.item.length + 1 && path[path.length - 1] === 'name') {
            const t = $('pe-inspector-title').querySelector('.pk-sub');
            if (t) t.textContent = (getAt(sel.item) || {}).name || '#' + sel.item[sel.item.length - 1];
        }
        if (sel.sec === 'messages' && sel.key != null && path[0] === 'messages' && path[2] === 'name') {
            const title = document.querySelector('#pe-main .pe-card .card-title');
            if (title) title.innerHTML = badge((doc.messages[sel.key] || {}).direction) + ' ' + esc(doc.messages[sel.key].name || '(unnamed)') + '<span class="pk-sub">' + ((doc.messages[sel.key] || {}).direction === 'rx' ? 'received' : 'sent') + '</span>';
        }
        if (path[0] === 'name') updateSubtitle();
        changed();
    }

    function changed() {
        serverProblems = [];
        testResults = null;
        scheduleValidate();
        updateSaveState();
        clearTimeout(saveTimer);
        saveTimer = setTimeout(function () {
            try {
                if (isDirty()) localStorage.setItem(draftKey(), JSON.stringify(doc));
                else localStorage.removeItem(draftKey());
            } catch (e) { /* 보관 못 해도 편집은 된다 */ }
        }, 400);
    }
    function draftKey() { return DRAFT_PREFIX + (defId == null ? 'new' : defId); }
    function isDirty() { return JSON.stringify(doc) !== savedJson; }
    function updateSubtitle() {
        $('pageSubtitle').textContent = (doc.name ? doc.name : '') + (defId == null ? ' · not saved yet' : '');
    }
    function updateSaveState() {
        const btn = document.querySelector('[data-act="save-doc"]');
        if (btn) btn.classList.toggle('is-dirty', isDirty());
    }

    /** 다시 그리고 지금 고른 것을 지킨다 */
    function rerender() {
        renderOutline(); renderMain(); renderInspector(); markProblems(); changed();
    }

    function setType(path, v) {
        const f = getAt(path);
        const before = M.kindOf(f, doc);
        // 구조체로 바꾸면 앞 타입의 크기를 버린다 — 구조체의 size 는 안쪽 필드 합을 검사하는 값이다
        if (v.indexOf('struct:') === 0) { delete f.type; delete f.fields; delete f.size; f.structure = v.slice(7); }
        else if (v === '') { delete f.type; delete f.structure; }
        else {
            delete f.structure;
            f.type = v;
            if (v === 'BITS') { if (before !== 'BITS') f.fields = []; if (typeof f.size !== 'number') f.size = 1; }
            if (M.isNumberType(v)) fitNumber(f);
        }
        const k = M.kindOf(f, doc);
        const keep = M.COMMON_ATTRS.concat(M.KIND_ATTRS[k] || []);
        Object.keys(f).forEach(a => { if (keep.indexOf(a) < 0) delete f[a]; });
        rerender();
    }

    /** 숫자형: 형식에 없는 크기는 기본 크기로(정수 2, 실수 4), 크기에 없는 바이트 순서는 지운다 */
    function fitNumber(f) {
        if (M.numberSizes(f.type).indexOf(f.size) < 0) f.size = f.type === 'FLOAT' ? 4 : 2;
        if (f.byteOrder && M.byteOrdersFor(f.size).indexOf(f.byteOrder) < 0) delete f.byteOrder;
    }

    function setAlgorithm(path, v) {
        const c = getAt(path.slice(0, -1));
        c.algorithm = v;
        const keep = ['algorithm', 'covers', 'over', 'onMismatch', 'doc'];
        if (v === 'CRC') keep.push('width', 'poly', 'init', 'refin', 'refout', 'xorout');
        if (v === 'SUM') keep.push('width', 'complement');
        if (v === 'EXPR') keep.push('init', 'step', 'final');
        Object.keys(c).forEach(a => { if (keep.indexOf(a) < 0) delete c[a]; });
        if (v === 'SUM' && c.width == null) c.width = 8;
        rerender();
    }

    function setDirection(path, v) {
        const m = getAt(path.slice(0, -1));
        m.direction = v;
        if (v === 'tx') { delete m.match; delete m.respond; delete m.size; }
        else { delete m.set; if (!Array.isArray(m.respond)) m.respond = []; }
        rerender();
    }

    function setFrame(path, v) {
        const m = getAt(path.slice(0, -1));
        if (v) {
            m.frame = v;
            if (Array.isArray(m.header) && !m.header.length) delete m.header;
            if (Array.isArray(m.footer) && !m.footer.length) delete m.footer;
        } else {
            delete m.frame;
            if (!Array.isArray(m.header)) m.header = [];
            if (!Array.isArray(m.footer)) m.footer = [];
        }
        // 키 순서를 형식 문서와 같게 둔다
        const order = ['name', 'direction', 'frame', 'doc', 'match', 'size', 'set', 'header', 'body', 'footer', 'respond'];
        const copy = {};
        order.forEach(k => { if (m[k] !== undefined) copy[k] = m[k]; });
        Object.keys(m).forEach(k => { if (copy[k] === undefined) copy[k] = m[k]; });
        Object.keys(m).forEach(k => delete m[k]);
        Object.assign(m, copy);
        rerender();
    }

    function setGroupKind(path, v) {
        const name = path[1];
        const old = doc.targets[name] || {};
        const fresh = M.newTargetGroup(v);
        if (v === 'sequence' && Array.isArray(old.items)) fresh.items = old.items;
        // 이름 타겟은 값만 갖는다: 표현에서 값을 읽어 두고 나머지(type·size·scale…)는 버린다
        if (v === 'named' && Array.isArray(old.items)) {
            fresh.items = old.items.filter(it => it && it.target).map(function (it) {
                const o = { target: it.target };
                const val = it.type ? M.valueOfField(Object.assign({}, it, { target: undefined }), doc) : null;
                if (val) o.value = val;
                if (it.access) o.access = it.access;
                if (it.doc) o.doc = it.doc;
                return o;
            });
        }
        if (old.doc) fresh.doc = old.doc;
        doc.targets[name] = fresh;
        rerender();
    }

    function setItemKind(path, v) {
        const it = getAt(path);
        const keepCommon = ['type', 'size', 'scale', 'offset', 'at', 'doc', 'encoding', 'pad', 'align', 'decimals', 'point', 'sign', 'case', 'byteOrder', 'onOverflow', 'terminator'];
        if (it.type === 'BITS' && v !== 'skip') { delete it.type; delete it.fields; delete it.bitOrder; if (v === 'const') { it.type = 'UINT'; it.size = 2; } }
        if (v === 'skip') { Object.keys(it).forEach(k => { if (k !== 'at') delete it[k]; }); it.skip = 1; }
        else if (v === 'const') { delete it.skip; delete it.target; delete it.access; if (it.const == null) it.const = 0; }
        else { delete it.skip; delete it.const; if (it.target == null) it.target = ''; }
        Object.keys(it).forEach(k => { if (['skip', 'const', 'target', 'access'].concat(keepCommon).indexOf(k) < 0) delete it[k]; });
        rerender();
    }

    /**
     * 타겟 항목의 타입을 바꾼다. 새 타입에 없는 형식 속성은 지운다.
     * BITS 로 바꾸면 항목의 타겟은 첫 비트 필드로 옮기고, 나머지 비트는 0 으로 채운다 (비트 합 = size × 8).
     */
    function setItemType(path, v) {
        const it = getAt(path);
        if (!it) return;
        const g = doc.targets[path[1]] || {};
        const was = it.type;
        // BITS 에서 나가면 첫 비트 타겟을 항목의 타겟으로 돌려준다
        const backTarget = was === 'BITS' && v !== 'BITS' ? (list(it.fields).find(b => b && b.target) || {}).target || '' : null;
        if (v) it.type = v; else delete it.type;
        if (M.isNumberType(v)) fitNumber(it);
        if (v === 'BITS' && was !== 'BITS') {
            const size = typeof it.size === 'number' ? it.size : (typeof g.slotSize === 'number' && g.slotSize > 0 ? g.slotSize : 1);
            it.size = size;
            // 남는 비트는 0 으로 나가므로 채움 필드가 필요 없다
            const first = { bit: 0, bits: 1, name: '', target: it.target || '' };
            it.fields = [first];
            delete it.target; delete it.const;
            if (!first.target) first.target = '';
        }
        const k = v ? M.kindOf({ type: v }, doc) : null;
        const keep = ['target', 'const', 'skip', 'access', 'at', 'doc', 'type'].concat(k ? (M.KIND_ATTRS[k] || []) : []);
        if (g.kind !== 'sequence') keep.push('terminator');
        Object.keys(it).forEach(a => { if (keep.indexOf(a) < 0) delete it[a]; });
        if (backTarget != null && it.target == null && it.const == null) it.target = backTarget;
        rerender();
    }

    function setWriteKind(path, v) {
        const w = getAt(path);
        Object.keys(w).forEach(k => delete w[k]);
        if (v === 'map') { w.map = keys(doc.targets).find(g => (doc.targets[g] || {}).kind === 'sequence') || ''; w.address = ''; w.bytes = ''; }
        else { w.target = ''; w.value = ''; }
        rerender();
    }

    /** 이름으로 찾는 묶음의 이름을 바꾸고, 그 이름을 가리키던 곳도 바꾼다 */
    function rename(path, to) {
        const sec = path[0], from = path[1];
        to = to.trim();
        if (!to || to === from) return false;
        const obj = doc[sec];
        if (Object.prototype.hasOwnProperty.call(obj, to)) { alert('"' + to + '" is already used.'); return false; }
        M.renameKey(obj, from, to);
        if (sec === 'frames') list(doc.messages).forEach(m => { if (m && m.frame === from) m.frame = to; });
        if (sec === 'checksums' || sec === 'structures') walkFields(function (f) {
            if (sec === 'checksums' && f.checksum === from) f.checksum = to;
            if (sec === 'structures' && f.structure === from) f.structure = to;
        });
        if (sel.sec === sec && sel.key === from) sel = { sec: sec, key: to, sub: null, item: null };
        return true;
    }
    function walkFields(fn) {
        function walk(fields) { list(fields).forEach(f => { if (!f) return; fn(f); if (Array.isArray(f.fields)) walk(f.fields); }); }
        keys(doc.frames).forEach(n => { walk((doc.frames[n] || {}).header); walk((doc.frames[n] || {}).footer); });
        keys(doc.structures).forEach(n => walk((doc.structures[n] || {}).fields));
        list(doc.messages).forEach(m => { if (m) { walk(m.header); walk(m.body); walk(m.footer); } });
    }

    function renameObjKey(objPath, oldKey, newKey) {
        newKey = newKey.trim();
        const obj = getAt(objPath);
        if (!obj || !newKey || newKey === oldKey) return false;
        if (Object.prototype.hasOwnProperty.call(obj, newKey)) { alert('"' + newKey + '" is already used.'); return false; }
        M.renameKey(obj, oldKey, newKey);
        return true;
    }

    function addTo(listPath, value) {
        let arr = getAt(listPath);
        if (!Array.isArray(arr)) { arr = []; setAt(listPath, arr); }
        arr.push(value);
        return listPath.concat([arr.length - 1]);
    }

    function act(name, el) {
        const path = el.dataset.p && el.dataset.p !== 'null' ? JSON.parse(el.dataset.p) : null;
        switch (name) {
            // 목록에 줄을 더하면 그 줄의 속성 모달을 연다. Cancel 이면 더하기 전으로 돌아간다
            case 'add-field': { const before = JSON.stringify(doc); const p = addTo(path, M.newField()); rerender(); openProps(p, before); return; }
            case 'add-message': {
                const frame = keys(doc.frames)[0] || null;
                const p = addTo(['messages'], M.newMessage(el.dataset.d, frame));
                go('messages', p[1], { focus: ['messages', p[1], 'name'] });
                changed();
                return;
            }
            case 'add-frame': { const n = uniqueName(doc.frames, 'frame'); doc.frames = doc.frames || {}; doc.frames[n] = M.newFrame(); go('frames', n); changed(); return; }
            case 'add-structure': { doc.structures = doc.structures || {}; const n = uniqueName(doc.structures, 'structure'); doc.structures[n] = { fields: [] }; go('structures', n); changed(); return; }
            case 'add-group': { const n = uniqueName(doc.targets, el.dataset.d); doc.targets = doc.targets || {}; doc.targets[n] = M.newTargetGroup(el.dataset.d); go('targets', n); changed(); return; }
            case 'add-checksum': { doc.checksums = doc.checksums || {}; doc.checksums[uniqueName(doc.checksums, 'checksum')] = M.newChecksum(); rerender(); return; }
            case 'add-param': {
                const before = JSON.stringify(doc);
                doc.params = doc.params || {};
                const n = uniqueName(doc.params, 'param');
                doc.params[n] = { value: 'integer', default: 0 };
                rerender(); openProps(['params', n], before);
                return;
            }
            case 'add-const': {
                const before = JSON.stringify(doc);
                doc.consts = doc.consts || {};
                const n = uniqueName(doc.consts, 'CONST');
                doc.consts[n] = 0;
                rerender(); openProps(['consts', n], before);
                return;
            }
            case 'add-test': { addTo(['tests'], M.newTest()); rerender(); return; }
            case 'add-rule': { const before = JSON.stringify(doc); const p = addTo(path, M.newRule()); rerender(); openProps(p, before); return; }
            case 'add-item': {
                const before = JSON.stringify(doc);
                const kind = el.dataset.d;
                const g = getAt(path.slice(0, -1)) || {};
                const slot = typeof g.slotSize === 'number' && g.slotSize > 0 ? g.slotSize : 1;
                const it = kind === 'skip' ? { skip: 1 }
                    : kind === 'const' ? { const: 0, type: 'UINT', size: 2 }
                    : kind === 'bits' ? { type: 'BITS', size: slot, fields: [{ bit: 0, bits: 1, name: '', target: '' }] }
                    : g.kind === 'sequence' ? { target: '', type: 'UINT', size: 2 } : { target: '' };
                if (it.type === undefined) delete it.type;
                const p = addTo(path, it);
                rerender(); openProps(p, before);
                return;
            }
            case 'add-set': { const o = getAt(path) || {}; setAt(path, o); o[uniqueName(o, 'header.field')] = ''; rerender(); return; }
            case 'add-arg': { const o = getAt(path) || {}; setAt(path, o); o[uniqueName(o, 'arg')] = ''; rerender(); return; }
            case 'add-write': { addTo(path, { target: '', value: '' }); rerender(); return; }
            case 'add-bit': {
                // 새 비트 필드는 비어 있는 가장 낮은 비트에. 기존 필드의 자리는 적어 둔다
                const owner = path.slice(0, -1);
                pinBits(owner);
                addTo(path, { bit: freeBit(getAt(owner)), bits: 1, name: '' });
                getAt(path).sort((x, y) => x.bit - y.bit);
                rerender();
                return;
            }
            case 'add-transform': { addTo(path, { op: 'ascii-hex' }); rerender(); return; }
            case 'props-ok': closeProps(true); return;
            case 'props-cancel': closeProps(false); return;
            case 'del': {
                const top = path.length === 2 && (KEYED.indexOf(path[0]) >= 0 || path[0] === 'checksums' || path[0] === 'tests');
                if (top && !confirm('Remove this ' + ({ messages: 'message', frames: 'frame', structures: 'structure', targets: 'target group', checksums: 'checksum', tests: 'test' }[path[0]]) + '?')) return;
                delAt(path);
                if (top && KEYED.indexOf(path[0]) >= 0) { go(path[0]); changed(); return; }
                if (sel.item && startsWith(sel.item, path)) sel.item = null;
                rerender(); updateHash();
                return;
            }
            case 'move': {
                const arr = getAt(path.slice(0, -1)), i = path[path.length - 1], j = i + parseInt(el.dataset.d, 10);
                if (!Array.isArray(arr) || j < 0 || j >= arr.length) return;
                const t = arr[i]; arr[i] = arr[j]; arr[j] = t;
                const moved = path.slice(0, -1).concat([j]);
                if (path[0] === 'messages' && path.length === 2) { go('messages', j); changed(); return; }
                if (sel.item && JSON.stringify(sel.item) === JSON.stringify(path)) sel.item = moved;
                rerender(); updateHash();
                return;
            }
            case 'noreply': {
                const t = getAt(path);
                if (el.checked) t.expect = null;
                else if (t.expect == null) t.expect = '';
                const inputNode = document.querySelector('[data-p="' + CSS.escape(JSON.stringify(path.concat(['expect']))) + '"]');
                if (inputNode) { inputNode.value = ''; inputNode.placeholder = el.checked ? 'no reply' : 'bytes the device sends'; if (!el.checked) inputNode.focus(); }
                changed();
                return;
            }
            case 'format-source': $('pe-source').value = pretty(doc) + '\n'; $('pe-source-status').textContent = ''; return;
            case 'copy-source': {
                const ta = $('pe-source');
                ta.select();
                try { navigator.clipboard.writeText(ta.value); } catch (e) { document.execCommand('copy'); }
                $('pe-source-status').textContent = 'Copied.';
                return;
            }
            case 'apply-source': {
                let parsed;
                try { parsed = JSON.parse($('pe-source').value); } catch (e) {
                    $('pe-source-status').innerHTML = '<span class="text-danger">Not valid JSON: ' + esc(e.message) + '</span>';
                    return;
                }
                if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) { $('pe-source-status').innerHTML = '<span class="text-danger">The document must be a JSON object.</span>'; return; }
                doc = parsed;
                updateSubtitle();
                rerender();
                $('pe-source-status').innerHTML = '<span class="text-success">Applied.</span>';
                return;
            }
            case 'save-doc': save(); return;
            case 'run-tests': runTests(); return;
            case 'cancel-doc':
                if (isDirty() && !confirm('Leave without saving? Your changes stay in this browser as a draft.')) return;
                leaving = true;
                location.href = '/protocols' + (defId == null ? '' : '?definition=' + defId);
                return;
            case 'download-doc': download(); return;
        }
    }

    function download() {
        validateNow();
        const errors = problems.filter(p => p.level === 'error').length;
        if (errors && !confirm('The document has ' + errors + ' error' + (errors === 1 ? '' : 's') + '. Download anyway?')) return;
        const blob = new Blob([pretty(doc) + '\n'], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = (doc.name || 'protocol') + '.json';
        document.body.appendChild(a);
        a.click();
        setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
    }

    /**
     * 서버에 저장한다. 오류가 있으면 보내지 않는다 (편집기가 막고, 서버는 컴파일러로 다시 거른다).
     * 서버가 거절하면 그 문제를 문제 목록에 함께 보인다.
     */
    function save() {
        if (saving) return;
        validateNow();
        const errors = problems.filter(p => p.level === 'error').length;
        if (errors) {
            flash('Fix the ' + errors + ' error' + (errors === 1 ? '' : 's') + ' first.', true);
            showProblems(true);
            return;
        }
        saving = true;
        const body = defId == null ? { document: doc } : { document: doc, version: defVersion };
        fetch(defId == null ? '/api/protocols' : '/api/protocols/' + defId, {
            method: defId == null ? 'POST' : 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        }).then(r => r.json().catch(() => ({})).then(b => ({ status: r.status, body: b }))).then(function (res) {
            saving = false;
            const b = res.body || {};
            if (res.status >= 200 && res.status < 300 && b.ok) {
                const wasNew = defId == null;
                try { localStorage.removeItem(draftKey()); } catch (e) { /* 없어도 된다 */ }
                defId = b.definition.id;
                defVersion = b.definition.version;
                savedJson = JSON.stringify(doc);
                if (wasNew) {
                    try { history.replaceState(null, '', '/protocols/edit/' + defId + location.hash); } catch (e) { /* 주소만 못 바꾼다 */ }
                }
                updateSubtitle();
                updateSaveState();
                flash('Saved.');
                return;
            }
            if (res.status === 400 && Array.isArray(b.problems)) {
                serverProblems = b.problems.map(p => ({ level: p.level === 'error' ? 'error' : 'warn', at: p.at || [], message: p.text }));
                (b.tests || []).filter(t => !t.ok).forEach(t => serverProblems.push({ level: 'error', at: ['tests', t.index],
                    message: 'Test "' + t.name + '" failed' + (t.message ? ': ' + t.message : '.') }));
                testResults = {};
                (b.tests || []).forEach(t => { testResults[t.index] = t; });
                if (sel.sec === 'tests') renderMain();
                validateNow();
                flash('The server did not save it.', true);
                showProblems(true);
                return;
            }
            alert(b.error || ('Saving failed (' + res.status + ').'));
        }).catch(function (e) {
            saving = false;
            alert('Saving failed: ' + e.message);
        });
    }
    /**
     * 상단 바의 문제 목록을 열고 닫는다. tabler.min.js 는 Bootstrap 을 window 에 내놓지 않으므로
     * data-bs-toggle 이 걸린 버튼을 눌러 바꾼다 (지금 상태와 다를 때만).
     */
    function showProblems(open) {
        const btn = $('pe-problems-btn');
        if ((btn.getAttribute('aria-expanded') === 'true') !== !!open) btn.click();
    }

    let flashTimer = null;
    function flash(text, bad) {
        const el = $('pe-flash');
        if (!el) return;
        el.classList.toggle('is-bad', !!bad);
        el.textContent = text;
        el.classList.add('is-on');
        clearTimeout(flashTimer);
        flashTimer = setTimeout(() => el.classList.remove('is-on'), 1800);
    }

    function load(d, hash) {
        doc = d;
        sel = { sec: 'general', key: null, sub: null, item: null };
        if (hash) readHash();
        updateSubtitle();
        renderOutline(); renderMain(); renderInspector();
        validateNow();
        updateHash();
        changed();
    }

    // ── 이벤트 ────────────────────────────────────────────────

    document.addEventListener('input', function (e) {
        const el = e.target;
        if (!el.closest || !el.closest('.pe-body, #pe-props')) return;
        if (el.dataset.p && !el.dataset.act && el.tagName !== 'SELECT' && el.dataset.v !== 'lines' && el.dataset.v !== 'csv') write(el);
    });
    document.addEventListener('change', function (e) {
        const el = e.target;
        if (!el.closest || !el.closest('.pe-body, #pe-props')) return;
        // 검색 목록(Tom Select)이 울린 change 는 그 처리가 끝난 뒤에 — 다시 그리면 목록을 걷기 때문에
        if (el.tomselect) { setTimeout(() => onChange(el), 0); return; }
        onChange(el);
    });
    function onChange(el) {
        if (!document.body.contains(el)) return;
        if (el.dataset.rename) {
            const from = JSON.parse(el.dataset.rename);
            // 모달로 연 것(파라미터·상수·프레임)의 이름을 바꾸면 모달도 새 이름을 따라간다 (rename 은 sel 을 새로 만든다)
            const opened = !!(sel.item && sel.item.length === 2 && sel.item[0] === from[0] && sel.item[1] === from[1]);
            if (!rename(from, el.value)) el.value = from[1];
            else if (opened) sel.item = [from[0], el.value.trim()];
            rerender(); updateHash();
            return;
        }
        if (el.dataset.objkey) {
            if (!renameObjKey(JSON.parse(el.dataset.objkey), el.dataset.old, el.value)) el.value = el.dataset.old;
            rerender();
            return;
        }
        const a = el.dataset.act;
        if (a === 'type') return setType(JSON.parse(el.dataset.p), el.value);
        if (a === 'algorithm') return setAlgorithm(JSON.parse(el.dataset.p), el.value);
        if (a === 'direction') return setDirection(JSON.parse(el.dataset.p), el.value);
        if (a === 'frame') return setFrame(JSON.parse(el.dataset.p), el.value);
        if (a === 'group-kind') return setGroupKind(JSON.parse(el.dataset.p), el.value);
        if (a === 'item-kind') return setItemKind(JSON.parse(el.dataset.p), el.value);
        if (a === 'item-type') return setItemType(JSON.parse(el.dataset.p).slice(0, -1), el.value);
        if (a === 'write-kind') return setWriteKind(JSON.parse(el.dataset.p), el.value);
        if (a === 'noreply') return act('noreply', el);
        if (el.dataset.p && (el.tagName === 'SELECT' || el.dataset.v === 'lines' || el.dataset.v === 'csv')) {
            write(el);
            // 고른 값에 따라 보이는 칸이 달라지는 것들
            const last = JSON.parse(el.dataset.p).slice(-1)[0];
            if (['op', 'kind', 'reply'].indexOf(last) >= 0 && sel.item) renderInspector();
            if (last === 'op' && JSON.parse(el.dataset.p)[2] === 'bodyTransform') renderMain();
            // 숫자형의 크기를 바꾸면 고를 수 있는 바이트 순서가 달라진다
            const owner = getAt(JSON.parse(el.dataset.p).slice(0, -1));
            if (last === 'size' && owner && M.isNumberType(owner.type)) { fitNumber(owner); refreshTables(); if (sel.item) renderInspector(); }
        }
    }
    document.addEventListener('click', function (e) {
        // 드롭다운 차례: 단추로 열고 닫는다 (바깥을 누르면 닫는 것은 아래 pointerdown)
        if (!WIDE.matches && e.target.closest('#pe-ol-btn')) { setOutlineOpen(!outlineOpen()); return; }
        const actEl = e.target.closest('[data-act]');
        if (actEl && actEl.tagName !== 'SELECT' && actEl.type !== 'checkbox') { e.preventDefault(); act(actEl.dataset.act, actEl); return; }
        // 접힌 문서 차례: 머리를 누르면 펴고 접는다. 차례에서 고르면 다시 접어 가운데에 자리를 돌려준다
        if (e.target.closest('#pe-ol-toggle')) { if (!WIDE.matches) setOutlineOpen(!document.querySelector('.pe-left').classList.contains('is-open')); return; }
        const nav = e.target.closest('[data-nav]');
        if (nav) {
            e.preventDefault();
            const n = JSON.parse(nav.dataset.nav);
            // go() 가 차례를 다시 그리면 nav 는 문서에서 빠지므로 먼저 본다
            const fromOutline = !!nav.closest('#pe-outline');
            go(n.sec, n.key == null ? null : n.key);
            if (fromOutline && !WIDE.matches) setOutlineOpen(false);
            return;
        }
        const prob = e.target.closest('.pe-prob');
        if (prob) { showProblems(false); goAt(JSON.parse(prob.dataset.at)); return; }
    });
    // 드롭다운 차례 바깥을 누르면 닫는다. 표(Tabulator)는 click 을 위로 올려 보내지 않으므로 처음 단계(capture)에서 본다
    document.addEventListener('pointerdown', function (e) {
        if (WIDE.matches || !outlineOpen()) return;
        if (!e.target.closest('.pe-left, #pe-ol-btn')) setOutlineOpen(false);
    }, true);
    document.addEventListener('keydown', function (e) {
        if ((e.key === 'Enter' || e.key === ' ') && e.target.id === 'pe-ol-toggle' && !WIDE.matches) {
            e.preventDefault();
            setOutlineOpen(!outlineOpen());
        }
        if (e.key === 'Escape' && !WIDE.matches && outlineOpen()) setOutlineOpen(false);
    });

    let leaving = false;
    window.addEventListener('beforeunload', function (e) {
        if (leaving || saving || !isDirty()) return;
        e.preventDefault();
        e.returnValue = '';
    });

    /**
     * 시작: 저장된 정의(/protocols/edit/{id}) / 예시(?example=) / 가져온 파일(?import=1) / 빈 문서.
     * 이 브라우저에 저장하지 않은 고침이 남아 있으면 이어서 쓸지 묻는다.
     */
    document.addEventListener('DOMContentLoaded', function () {
        // 세로·좁은 화면은 차례를 접은 채로 시작한다. 화면 모양이 바뀌어도 접어 둔다
        mountOutlineButton();
        setOutlineOpen(false);
        WIDE.addEventListener('change', function () { setOutlineOpen(false); });
        CtsModal.onHidden('#pe-props', afterPropsHidden, false);

        const params = new URLSearchParams(location.search);
        function readDraft() {
            try { return JSON.parse(localStorage.getItem(draftKey()) || 'null'); } catch (e) { return null; }
        }
        // 새 문서는 시작한 모습에서 바뀌었을 때만 남길 것이 있다 (손대지 않은 예시는 잃을 것이 없다)
        function startNew(d) {
            savedJson = JSON.stringify(d);
            load(d, true);
        }
        if (defId != null) {
            fetch('/api/protocols/' + defId).then(r => r.ok ? r.json() : Promise.reject(r.status)).then(function (def) {
                defVersion = def.version;
                savedJson = JSON.stringify(def.document);
                const draft = readDraft();
                if (draft && JSON.stringify(draft) !== savedJson
                    && confirm('This browser has unsaved changes to "' + def.name + '" from an earlier visit. Continue with them?')) {
                    load(draft, true);
                } else {
                    try { localStorage.removeItem(draftKey()); } catch (e) { /* 없어도 된다 */ }
                    load(def.document, true);
                }
            }).catch(function () {
                alert('Protocol definition ' + defId + ' could not be loaded.');
                leaving = true;
                location.href = '/protocols';
            });
            return;
        }
        if (PAGE.example) {
            fetchExample(PAGE.example).then(d => startNew(d || M.newDoc()));
            return;
        }
        if (params.get('import')) {
            let d = null;
            try { d = JSON.parse(sessionStorage.getItem(IMPORT_KEY) || 'null'); sessionStorage.removeItem(IMPORT_KEY); } catch (e) { d = null; }
            startNew(d && typeof d === 'object' ? d : M.newDoc());
            return;
        }
        const draft = readDraft();
        if (draft && typeof draft === 'object' && confirm('This browser has an unsaved new definition from an earlier visit. Continue with it?')) {
            startNew(draft);
        } else {
            try { localStorage.removeItem(draftKey()); } catch (e) { /* 없어도 된다 */ }
            startNew(M.newDoc());
        }
    });

    // 시험(CDP)에서 상태를 들여다보는 손잡이. 화면 동작에는 쓰지 않는다
    window.CtsProtocolEditor = { doc: () => doc, sel: () => sel, problems: () => problems, id: () => defId, dirty: () => isDirty() };

    function fetchExample(id) {
        return fetch('/protocol-examples/' + encodeURIComponent(id) + '.json')
            .then(r => { if (!r.ok) throw new Error(r.status); return r.json(); })
            .catch(function () { alert('Could not load the example "' + id + '".'); return null; });
    }
})();
