/**
 * 열리는 목록 하나 — Tom Select(동작) + Tabler vendors(모양). 계획: docs/SELECTOR_OVERHAUL_PLAN.md
 *
 * 규칙
 *   - 선택지가 SEARCH_OVER(5)개를 넘으면 드롭다운 맨 위에 검색칸을 둔다(dropdown_input). 그 밖에는 검색 없음.
 *     목록을 갈아 끼우면(refresh) 다시 센다. 컨트롤은 늘 한 줄이다.
 *   - 드롭다운은 잘리지 않게 body 에 붙인다. 포커스를 가두는 그릇(.modal, [data-ts-parent]) 안이면 그 그릇에.
 *   - 고르면 원래 <select> 에 input·change 가 울린다(거품) — 기존 change 처리기는 그대로 돈다.
 *
 * 자산(화면 head): plugins/tom-select/css/tom-select.bootstrap5.min.css → tabler-vendors.min.css → css/cts-select.css
 * 스크립트: plugins/tom-select/js/tom-select.complete.min.js → 이 파일. fragments/tabler.html 의 select-assets / select-scripts.
 *
 *     CtsSelect.mount(el)            붙인다. data-native 가 붙은 select 는 건너뛴다
 *     CtsSelect.mountAll(root)       root 안의 select.form-select 를 모두 — HTML 을 새로 그린 뒤
 *     CtsSelect.destroyAll(root)     다시 그리기 전에. 드롭다운이 body 에 있어 안 부르면 고아로 남는다
 *     CtsSelect.refresh(el)          <option> 을 갈아 끼운 뒤 (검색칸 기준도 다시 센다)
 *     CtsSelect.val(el[, v])         값 읽기·쓰기 (쓸 때 change 를 울리지 않는다)
 *     CtsSelect.cellEditor(fn)       Tabulator 칸 편집기. fn(cell) → 선택지(아래 options 형식)
 *
 * options 형식: ['a', 'b'] / [['값', '보이는 글'], …] / [{ group: '묶음', options: [...] }, …]
 */
(function (global) {
    'use strict';

    /** 선택지가 이 수를 넘으면 검색칸을 둔다 */
    const SEARCH_OVER = 5;

    function el(target) {
        return typeof target === 'string' ? document.querySelector(target) : (target || null);
    }
    function instance(target) {
        const node = el(target);
        return node ? node.tomselect || null : null;
    }
    function optionCount(node) {
        return node.querySelectorAll('option').length;
    }
    function parentOf(node) {
        // 포커스를 가두지 않는 모달(data-bs-focus="false", 예: 패킷 편집기의 속성 모달)은 body 에 붙여도 검색칸을 쓸 수 있다
        const box = node.closest && node.closest('.modal:not([data-bs-focus="false"]), [data-ts-parent]');
        // 'body' 는 글자로 준다 — Tom Select 는 dropdownParent 가 글자 'body' 일 때만 칸 아래로 자리를 잡는다
        return box || 'body';
    }

    function settings(node, extra) {
        const search = optionCount(node) > SEARCH_OVER;
        const opts = {
            maxOptions: null,
            // '' 도 고를 수 있는 값이다 ("(none)", "document default" 같은 기본값 자리)
            allowEmptyOption: true,
            dropdownParent: parentOf(node),
            plugins: search ? ['dropdown_input'] : [],
            searchField: ['text', 'value'],
            render: {
                no_results: function () { return '<div class="no-results">No matches</div>'; },
            },
            onDropdownOpen: function (dropdown) {
                // 드롭다운은 칸보다 좁지 않게, 글이 길면 그만큼 (최대폭은 css/cts-select.css)
                dropdown.style.minWidth = this.wrapper.getBoundingClientRect().width / zoomOf() + 'px';
            },
        };
        // 검색이 없으면 컨트롤에 글을 칠 칸도 없다 (↑↓·Enter 는 그대로 된다)
        if (!search) opts.controlInput = null;
        return Object.assign(opts, extra || {});
    }

    /**
     * 큰 화면 배율(css/cts-tabler.css 0절): 화면(.page)은 zoom 이 걸려 있고 body 에 붙은 드롭다운은 아니다.
     * 드롭다운에도 같은 배율을 걸고, Tom Select 가 눈에 보이는 px 로 잡은 자리를 배율로 나눈다
     * (zoom 이 걸린 요소의 top·left 에도 배율이 곱해지므로).
     */
    function zoomOf() {
        const z = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cts-zoom'));
        return z > 0 ? z : 1;
    }
    function fitZoom(ts) {
        const place = ts.positionDropdown;
        if (typeof place !== 'function') return;
        function adjust(self) {
            if (self.dropdown.parentNode !== document.body) return;
            const z = zoomOf(), st = self.dropdown.style;
            st.zoom = z === 1 ? '' : String(z);
            // 아래 자리가 모자라고 위가 더 넓으면 칸 위로 연다
            const ctl = self.control.getBoundingClientRect();
            const h = self.dropdown.getBoundingClientRect().height;
            let top = ctl.bottom + window.scrollY;
            if (ctl.bottom + h > window.innerHeight - 4 && ctl.top > window.innerHeight - ctl.bottom) top = ctl.top + window.scrollY - h;
            st.top = (top / z) + 'px';
            st.left = ((ctl.left + window.scrollX) / z) + 'px';
        }
        ts.positionDropdown = function () {
            place.apply(this, arguments);
            adjust(this);
        };
        // 목록은 연 뒤에 채워지고 검색하면 줄어든다 — 높이가 바뀔 때마다 자리를 다시 잡는다
        if (global.ResizeObserver) new ResizeObserver(function () { if (ts.isOpen) adjust(ts); }).observe(ts.dropdown);
    }

    function mount(target, extra) {
        const node = el(target);
        if (!node || node.tagName !== 'SELECT' || node.hasAttribute('data-native')) return null;
        if (node.tomselect) return node.tomselect;
        if (!global.TomSelect) return null;
        const ts = new global.TomSelect(node, settings(node, extra));
        fitZoom(ts);
        ts.wrapper.classList.add('cts-ts');
        ts.dropdown.classList.add('cts-ts-dropdown');
        // 원래 select 의 크기(form-select-sm)를 컨트롤이 이어받는다
        if (node.classList.contains('form-select-sm')) ts.wrapper.classList.add('cts-ts-sm');
        return ts;
    }

    function mountAll(root) {
        root = el(root) || document;
        root.querySelectorAll('select.form-select').forEach(function (s) { mount(s); });
    }

    function destroy(target) {
        const ts = instance(target);
        if (ts) ts.destroy();
    }
    function destroyAll(root) {
        root = el(root) || document;
        root.querySelectorAll('select.tomselected, select.ts-hidden-accessible').forEach(function (s) {
            if (s.tomselect) s.tomselect.destroy();
        });
    }

    /** <option> 을 갈아 끼운 뒤. 검색칸을 둘지 다시 정하려고 새로 붙인다 */
    function refresh(target) {
        const node = el(target);
        const ts = instance(node);
        if (!ts) return;
        const v = node.value;
        ts.destroy();
        node.value = v;
        mount(node);
    }

    function val(target, value) {
        const ts = instance(target);
        const node = el(target);
        if (arguments.length < 2) return ts ? ts.getValue() : (node ? node.value : null);
        if (ts) ts.setValue(value, true);
        else if (node) node.value = value;
    }

    // ── 선택지 HTML ──

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, m =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', '\'': '&#39;' }[m]));
    }
    /** 선택지 목록 → <option>/<optgroup> HTML. 목록에 없는 지금 값은 맨 앞에 남긴다 */
    function optionsHtml(options, current) {
        const cur = current == null ? '' : String(current);
        let found = false;
        function one(o) {
            const pair = Array.isArray(o) ? o : [o, o];
            const v = String(pair[0]);
            if (v === cur) found = true;
            return '<option value="' + esc(v) + '"' + (v === cur ? ' selected' : '') + '>' + esc(pair[1]) + '</option>';
        }
        const body = (options || []).map(function (o) {
            if (o && !Array.isArray(o) && typeof o === 'object' && o.group != null) {
                return '<optgroup label="' + esc(o.group) + '">' + (o.options || []).map(one).join('') + '</optgroup>';
            }
            return one(o);
        }).join('');
        return (found ? '' : '<option value="' + esc(cur) + '" selected>' + esc(cur) + '</option>') + body;
    }

    /**
     * Tabulator 칸 편집기. 칸을 누르면 바로 열리고, 바로 치면 검색한다(검색칸이 있을 때).
     * 고르면 success, 바뀐 것 없이 닫히면(Esc·바깥 클릭·같은 값) cancel.
     */
    function cellEditor(optionsOf) {
        return function (cell, onRendered, success, cancel) {
            const node = document.createElement('select');
            node.className = 'form-select form-select-sm';
            const before = cell.getValue() == null ? '' : String(cell.getValue());
            node.innerHTML = optionsHtml(optionsOf(cell), before);
            let done = false, ts = null;
            function finish(ok, v) {
                if (done) return;
                done = true;
                // 드롭다운은 body 에 있다. Tabulator 가 칸을 비우기 전에 걷는다
                if (ts) { try { ts.destroy(); } catch (e) { /* 이미 걷혔다 */ } }
                if (ok && v !== before) success(v); else cancel();
            }
            onRendered(function () {
                ts = mount(node, {
                    dropdownParent: 'body',
                    onChange: function (v) { finish(true, v); },
                    onDropdownClose: function () { setTimeout(function () { finish(false); }, 0); },
                });
                if (!ts) { node.focus(); return; }
                ts.wrapper.classList.add('cts-ts-cell');
                ts.focus();
                ts.open();
            });
            // Tom Select 를 못 붙인 경우(스크립트 없음)에도 고를 수는 있게
            node.addEventListener('change', function () { if (!ts) finish(true, node.value); });
            return node;
        };
    }

    global.CtsSelect = {
        SEARCH_OVER: SEARCH_OVER,
        mount: mount, mountAll: mountAll, destroy: destroy, destroyAll: destroyAll,
        refresh: refresh, val: val, instance: instance, cellEditor: cellEditor, optionsHtml: optionsHtml,
    };
})(window);
