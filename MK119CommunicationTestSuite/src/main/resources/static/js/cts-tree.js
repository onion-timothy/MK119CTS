/**
 * 좌측 "Groups and devices" 트리. devices / simulations / errors 가 같은 것을 쓴다.
 *
 * jsTree 를 걷어내고 직접 그린다. 쓰는 기능은 지연 로딩·검색·우클릭 메뉴 셋뿐인데,
 * 그 셋 때문에 테마가 따라붙어 Tabler 카드 안에서 혼자 다른 시대처럼 보였다.
 *
 *     const tree = CtsTree.create(document.getElementById('tree-container'), {
 *         url: '/api/device/devices/treeData',
 *         expandAll: true,                 // 다 펼친 채로 시작
 *         initialSelect: 'd_2012',         // 다 불러온 뒤 이걸 고른다 (없으면 넘어감)
 *         expandOnSelect: true,            // 고르면 펼친다
 *         selectable: node => ...,         // 고를 줄로 바꿔치기 (null 이면 못 고름)
 *         filter: row => ...,              // 서버가 준 줄 중 보일 것만 (false 면 버림)
 *         onSelect: node => ...,
 *         contextMenu: node => [{label, action}],
 *     });
 *
 * 노드 id 는 서버가 주는 'g_1' / 'd_2012' 를 그대로 쓴다 — 화면 쪽이 split('_')[1] 로
 * 숫자를 꺼내 쓰고 있어서 바꾸지 않았다.
 */
(function (global) {
    'use strict';

    // 크기는 전부 CSS 가 쥔다 (css/cts-tree.css 의 --ct-*). 여기서는 깊이만 넘긴다 —
    // 줄의 왼쪽 여백도, 들여쓰기 안내선의 자리도 그 깊이로 CSS 가 계산한다.

    /**
     * Tabler Icons (MIT) 의 path. 24 격자에 2px 둥근 선 — Tabler 가 제 화면을 그리는 바로 그 결이다.
     *
     * 5900개짜리 아이콘 폰트를 들이는 대신 쓰는 네 개만 적어 둔다. FontAwesome 로는 이 결이 안 나온다:
     * 채움(fas)은 트리에서 혼자 굵고, 외곽선(far)은 FA5 Free 에 151개뿐이라 server 가 아예 없다.
     */
    const ICONS = {
        'folder': [
            'M5 4h4l3 3h7a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2',
        ],
        'folder-open': [
            'M5 19l2.757 -7.351a1 1 0 0 1 .936 -.649h12.307a1 1 0 0 1 .986 1.164l-.996 5.211a2 2 0'
            + ' 0 1 -1.964 1.625h-14.026a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2h4l3 3h7a2 2 0 0 1 2 2v2',
        ],
        'server': [
            'M3 4m0 3a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v2a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3z',
            'M3 12m0 3a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v2a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3z',
            'M7 8l0 .01',
            'M7 16l0 .01',
        ],
        'chevron-right': [
            'M9 6l6 6l-6 6',
        ],
    };

    function ti(name) {
        const paths = ICONS[name];
        if (!paths) return '';
        return '<svg class="ct-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
            + ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
            + paths.map(d => '<path d="' + d + '"/>').join('') + '</svg>';
    }

    /** 그룹은 펼친 모양이 따로 있다. 16px 에서 차이가 크진 않지만 손해도 없다 */
    function iconFor(n) {
        if (n.type === 'group') return ti(n.open ? 'folder-open' : 'folder');
        if (n.type === 'device') return ti('server');
        return '';
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, m =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[m]));
    }

    /** 'g_12' → 12. 최상위(null)는 0 — 서버가 그렇게 받는다 */
    function num(id) {
        if (id == null) return 0;
        const n = parseInt(String(id).split('_')[1], 10);
        return isNaN(n) ? 0 : n;
    }

    /** 그룹 먼저, 그다음 이름순. 숫자가 섞인 이름은 숫자로 읽는다 (dev2 < dev10) */
    function order(a, b) {
        if (a.type !== b.type) return a.type === 'group' ? -1 : 1;
        return String(a.text).localeCompare(String(b.text), undefined, { numeric: true });
    }

    class Tree {
        constructor(el, opts) {
            this.el = el;
            this.opts = opts || {};
            this.nodes = new Map();
            this.roots = [];
            this.selectedId = null;
            this.query = '';
            this.visible = null;        // 검색 중일 때만 Set, 평소엔 null
            this.openBeforeSearch = null;
            this.rowIds = [];           // 지금 화면에 보이는 차례 — 키보드 이동이 쓴다
            this.bulk = false;          // 여러 번 열 때 그리기를 한 번으로 모은다

            el.classList.add('cts-tree');
            el.setAttribute('role', 'tree');
            el.ctsTree = this;   // 화면 쪽 변수를 안 거치고도 집어낼 수 있게
            el.addEventListener('click', e => this.onClick(e));
            el.addEventListener('keydown', e => this.onKey(e));
            el.addEventListener('contextmenu', e => this.onContext(e));

            this.render();
            this.ready = this.start();
        }

        // ── 불러오기 ────────────────────────────────────────────

        start() {
            this.setBusy(true);
            return this.load(null)
                .then(() => this.opts.expandAll ? this.openAll() : null)
                .then(() => {
                    this.setBusy(false);
                    this.render();
                    if (this.opts.initialSelect && this.nodes.has(this.opts.initialSelect)) {
                        this.select(this.opts.initialSelect);
                    }
                    if (typeof this.opts.onReady === 'function') this.opts.onReady(this);
                })
                .catch(err => {
                    this.setBusy(false);
                    this.failed = String(err && err.message || err);
                    this.render();
                });
        }

        setBusy(on) {
            this.busy = on;
            if (!on) return;
            // .cts-empty 는 세로로 쌓으므로, 돌아가는 표시와 글자는 한 덩어리로 묶어 한 줄에 둔다
            this.el.innerHTML = '<div class="cts-empty"><span><span class="ct-spin"></span> Loading...</span></div>';
        }

        /** parentId 의 자식을 서버에서 받는다. 최상위는 null. */
        load(parentId) {
            return fetch(this.opts.url + '?id=' + num(parentId), { credentials: 'same-origin' })
                .then(r => {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.json();
                })
                .then(rows => this.absorb(parentId, rows || []));
        }

        absorb(parentId, rows) {
            const kids = [];
            // 그룹만 고르는 화면처럼 일부 줄만 보여 줄 때. 걸러서 비면 아래에서 접기 표시를 지운다
            if (typeof this.opts.filter === 'function') rows = rows.filter(this.opts.filter);
            rows.forEach(r => {
                const node = {
                    id: r.id,
                    text: r.text,
                    type: r.type,
                    parent: parentId,
                    protocol: r.protocol || '',
                    count: (r.count === undefined || r.count === null) ? null : r.count,
                    hasChildren: !!r.children,
                    childIds: null,
                    open: false,
                    loading: false,
                };
                const old = this.nodes.get(node.id);
                if (old) { node.childIds = old.childIds; node.open = old.open; }
                this.nodes.set(node.id, node);
                kids.push(node.id);
            });
            kids.sort((a, b) => order(this.nodes.get(a), this.nodes.get(b)));

            if (parentId === null) {
                this.roots = kids;
            } else {
                const p = this.nodes.get(parentId);
                if (p) {
                    p.childIds = kids;
                    // children:true 라 해 놓고 비어 있는 그룹이 있다. 받아 보고 나서 접기 표시를 지운다
                    p.hasChildren = kids.length > 0;
                }
            }
        }

        // ── 펼치고 접기 ─────────────────────────────────────────

        open(id) {
            const n = this.nodes.get(id);
            if (!n || !n.hasChildren) return Promise.resolve();
            if (n.childIds) { n.open = true; this.render(); return Promise.resolve(); }
            n.open = true;
            n.loading = true;
            this.render();
            return this.load(id).then(() => {
                n.loading = false;
                this.render();
            }, () => {
                n.loading = false;
                n.open = false;
                this.render();
            });
        }

        close(id) {
            const n = this.nodes.get(id);
            if (!n) return;
            n.open = false;
            this.render();
        }

        toggle(id) {
            const n = this.nodes.get(id);
            if (!n) return;
            if (n.open) this.close(id); else this.open(id);
        }

        /** 끝까지 펼친다. 한 번에 한 겹씩 받아 오므로 다 받을 때까지 되돌아온다. */
        openAll() {
            const step = () => {
                const todo = [];
                this.nodes.forEach(n => { if (n.hasChildren && (!n.open || !n.childIds)) todo.push(n.id); });
                if (todo.length === 0) return Promise.resolve();
                return Promise.all(todo.map(id => this.open(id))).then(step);
            };
            this.bulk = true;
            return step().then(() => { this.bulk = false; this.render(); },
                               e => { this.bulk = false; this.render(); throw e; });
        }

        /** 펼치지는 않고 내용만 다 받아 둔다. 검색이 아직 안 펼친 가지까지 보려면 필요하다. */
        loadAll() {
            const step = () => {
                const todo = [];
                this.nodes.forEach(n => { if (n.hasChildren && !n.childIds) todo.push(n.id); });
                if (todo.length === 0) return Promise.resolve();
                return Promise.all(todo.map(id => this.load(id))).then(step);
            };
            return step();
        }

        /** id 가 보이도록 조상을 모두 펼친다. 무언가 바뀌었으면 true — 부르는 쪽이 다시 그린다. */
        reveal(id) {
            const chain = [];
            let p = (this.nodes.get(id) || {}).parent;
            while (p) {
                chain.push(p);
                p = (this.nodes.get(p) || {}).parent;
            }
            let changed = false;
            chain.reverse().forEach(pid => {
                const n = this.nodes.get(pid);
                if (!n) return;
                if (!n.open) { n.open = true; changed = true; }
                // 아직 자식을 안 받아 온 가지면 펼쳐 봐야 빈칸이다. 받아 오면 open() 이 다시 그린다
                if (!n.childIds) { this.open(pid); changed = true; }
            });
            return changed;
        }

        // ── 고르기 ──────────────────────────────────────────────

        /**
         * opts.selectable 이 있으면 먼저 그걸 거친다 — 장비를 눌렀을 때 그 그룹이 골라지는
         * devices 화면처럼, 누른 줄과 골라지는 줄이 다른 경우가 있다.
         */
        select(id, options) {
            options = options || {};
            if (!this.nodes.has(id)) return;
            if (typeof this.opts.selectable === 'function') {
                const mapped = this.opts.selectable(this.nodeOf(id));
                if (!mapped || !this.nodes.has(mapped)) return;
                id = mapped;
            }
            const same = this.selectedId === id;
            this.selectedId = id;
            // 접힌 가지 안을 골랐으면 통째로 다시 그려야 한다. 아니면 칠만 바꾼다
            if (this.reveal(id)) this.render(); else this.paint();
            this.scrollIntoView(id);

            const n = this.nodes.get(id);
            if (this.opts.expandOnSelect && n.hasChildren && !n.open) this.open(id);
            if (!options.silent && !(same && options.onlyIfChanged)) this.fire(n);
        }

        fire(n) {
            if (typeof this.opts.onSelect === 'function') this.opts.onSelect(this.nodeOf(n.id));
        }

        selected() { return this.selectedId; }

        nodeOf(id) {
            const n = this.nodes.get(id);
            if (!n) return null;
            return {
                id: n.id, text: n.text, type: n.type, parent: n.parent,
                protocol: n.protocol, count: n.count,
            };
        }

        textOf(id) {
            const n = this.nodes.get(id);
            return n ? n.text : '';
        }

        // ── 검색 ────────────────────────────────────────────────

        /**
         * 이름에 q 가 들어간 줄과, 그 조상·자손만 남긴다 (jsTree 의 show_only_matches 와 같은 규칙).
         * 아직 안 펼친 가지도 찾아야 해서 먼저 전부 받아 둔다.
         */
        search(q) {
            this.query = String(q || '').trim();
            if (!this.query) {
                this.visible = null;
                if (this.openBeforeSearch) {
                    // 검색하느라 펼친 것은 되돌린다
                    this.nodes.forEach(n => { n.open = this.openBeforeSearch.has(n.id); });
                    this.openBeforeSearch = null;
                }
                this.render();
                return Promise.resolve();
            }
            if (!this.openBeforeSearch) {
                this.openBeforeSearch = new Set();
                this.nodes.forEach(n => { if (n.open) this.openBeforeSearch.add(n.id); });
            }
            return this.loadAll().then(() => {
                const needle = this.query.toLowerCase();
                const vis = new Set();
                const down = id => {
                    vis.add(id);
                    ((this.nodes.get(id) || {}).childIds || []).forEach(down);
                };
                this.nodes.forEach(n => {
                    if (String(n.text).toLowerCase().indexOf(needle) < 0) return;
                    down(n.id);
                    let p = n.parent;
                    while (p) {
                        const pn = this.nodes.get(p);
                        if (!pn) break;
                        vis.add(p);
                        pn.open = true;
                        p = pn.parent;
                    }
                    const self = this.nodes.get(n.id);
                    if (self.hasChildren) self.open = true;
                });
                this.visible = vis;
                this.render();
            });
        }

        // ── 그리기 ──────────────────────────────────────────────

        render() {
            if (this.bulk || this.busy) return;
            if (this.failed) {
                this.el.innerHTML = '<div class="cts-empty is-bad">Could not load the tree ('
                    + esc(this.failed) + ').</div>';
                return;
            }
            const out = [];
            this.rowIds = [];
            this.roots.forEach(id => this.emit(out, id, 0));
            if (out.length === 0) {
                out.push('<div class="cts-empty">' + (this.query ? 'Nothing matches.' : 'No groups yet.') + '</div>');
            }
            this.el.innerHTML = out.join('');
        }

        emit(out, id, depth) {
            const n = this.nodes.get(id);
            if (!n) return;
            if (this.visible && !this.visible.has(id)) return;
            this.rowIds.push(id);
            out.push(this.rowHtml(n, depth));
            if (!n.open || !n.childIds) return;
            const kids = [];
            n.childIds.forEach(k => this.emit(kids, k, depth + 1));
            // 들여쓰기 안내선은 부모의 접기 화살표 아래로 내려간다 — --d 가 그 자리를 잡는다
            if (kids.length) out.push('<div class="ct-kids" style="--d:' + depth + '">' + kids.join('') + '</div>');
        }

        rowHtml(n, depth) {
            const sel = n.id === this.selectedId;
            let caret = '<span class="ct-caret"></span>';
            if (n.loading) {
                caret = '<span class="ct-caret"><span class="ct-spin"></span></span>';
            } else if (n.hasChildren) {
                caret = '<span class="ct-caret is-toggle' + (n.open ? ' is-open' : '') + '">'
                    + ti('chevron-right') + '</span>';
            }
            let meta = '';
            if (n.type === 'group') {
                if (n.count !== null) meta = '<span class="ct-count">' + esc(n.count) + '</span>';
            } else if (n.protocol) {
                meta = '<span class="ct-badge">' + esc(n.protocol) + '</span>';
            }
            return '<button type="button" class="ct-row' + (sel ? ' is-sel' : '') + '"'
                + ' role="treeitem" data-id="' + esc(n.id) + '"'
                + ' tabindex="' + (sel ? '0' : '-1') + '"'
                + (n.hasChildren ? ' aria-expanded="' + (n.open ? 'true' : 'false') + '"' : '')
                + (sel ? ' aria-selected="true"' : '')
                + ' style="--d:' + depth + '">'
                + caret
                + '<span class="ct-ico">' + iconFor(n) + '</span>'
                + '<span class="ct-name">' + esc(n.text) + '</span>'
                + meta
                + '</button>';
        }

        /** 고른 줄만 바꿔 칠한다. 줄이 많을 때 통째로 다시 그리지 않으려는 것 */
        paint() {
            this.el.querySelectorAll('.ct-row').forEach(row => {
                const on = row.dataset.id === this.selectedId;
                row.classList.toggle('is-sel', on);
                row.tabIndex = on ? 0 : -1;
                if (on) row.setAttribute('aria-selected', 'true');
                else row.removeAttribute('aria-selected');
            });
        }

        scrollIntoView(id) {
            const row = this.el.querySelector('.ct-row[data-id="' + id + '"]');
            if (row && row.scrollIntoView) row.scrollIntoView({ block: 'nearest' });
        }

        // ── 입력 ────────────────────────────────────────────────

        onClick(e) {
            const row = e.target.closest('.ct-row');
            if (!row) return;
            const id = row.dataset.id;
            // 화살표만 누르면 펼치기만 한다 — 고르는 것과 펼치는 것을 나눠 둔다
            if (e.target.closest('.ct-caret.is-toggle')) { this.toggle(id); return; }
            this.select(id);
        }

        onKey(e) {
            const row = e.target.closest && e.target.closest('.ct-row');
            if (!row) return;
            const id = row.dataset.id;
            const n = this.nodes.get(id);
            const i = this.rowIds.indexOf(id);
            const go = j => {
                const next = this.rowIds[j];
                if (next === undefined) return;
                const el = this.el.querySelector('.ct-row[data-id="' + next + '"]');
                if (el) el.focus();
            };
            switch (e.key) {
                case 'ArrowDown': e.preventDefault(); go(i + 1); break;
                case 'ArrowUp': e.preventDefault(); go(i - 1); break;
                case 'Home': e.preventDefault(); go(0); break;
                case 'End': e.preventDefault(); go(this.rowIds.length - 1); break;
                case 'ArrowRight':
                    e.preventDefault();
                    if (n && n.hasChildren && !n.open) this.open(id).then(() => this.refocus(id));
                    else go(i + 1);
                    break;
                case 'ArrowLeft':
                    e.preventDefault();
                    if (n && n.hasChildren && n.open) { this.close(id); this.refocus(id); }
                    else if (n && n.parent) this.refocus(n.parent);
                    break;
                default: break;
            }
        }

        refocus(id) {
            const el = this.el.querySelector('.ct-row[data-id="' + id + '"]');
            if (el) el.focus();
        }

        onContext(e) {
            if (typeof this.opts.contextMenu !== 'function') return;
            const row = e.target.closest('.ct-row');
            if (!row) return;
            e.preventDefault();
            const clicked = this.nodeOf(row.dataset.id);
            this.select(row.dataset.id);
            const items = this.opts.contextMenu(clicked) || [];
            if (items.length) this.showMenu(e.clientX, e.clientY, items);
        }

        showMenu(x, y, items) {
            this.hideMenu();
            const menu = document.createElement('div');
            menu.className = 'cts-tree-menu';
            menu.innerHTML = items.map((it, i) =>
                '<button type="button" class="ctm-item" data-i="' + i + '">' + esc(it.label) + '</button>').join('');
            document.body.appendChild(menu);
            // 화면 밖으로 넘어가면 커서 반대쪽으로 붙인다. 여기까지는 눈에 보이는 px 이고,
            // 메뉴에 걸린 배율로 나눠 CSS px 로 놓는다 (js/cts-zoom.js)
            const r = menu.getBoundingClientRect(), z = CtsZoom.of(menu);
            menu.style.left = Math.min(x, window.innerWidth - r.width - 8) / z + 'px';
            menu.style.top = Math.min(y, window.innerHeight - r.height - 8) / z + 'px';

            menu.addEventListener('click', e => {
                const b = e.target.closest('.ctm-item');
                if (!b) return;
                const it = items[parseInt(b.dataset.i, 10)];
                this.hideMenu();
                if (it && typeof it.action === 'function') it.action();
            });
            this.menu = menu;
            this.menuAway = ev => {
                if (ev.type === 'keydown' && ev.key !== 'Escape') return;
                if (ev.type === 'mousedown' && menu.contains(ev.target)) return;
                this.hideMenu();
            };
            setTimeout(() => {
                document.addEventListener('mousedown', this.menuAway, true);
                document.addEventListener('keydown', this.menuAway, true);
                window.addEventListener('resize', this.menuAway, true);
            }, 0);
        }

        hideMenu() {
            if (!this.menu) return;
            document.removeEventListener('mousedown', this.menuAway, true);
            document.removeEventListener('keydown', this.menuAway, true);
            window.removeEventListener('resize', this.menuAway, true);
            this.menu.remove();
            this.menu = null;
        }
    }

    global.CtsTree = {
        create: (el, opts) => new Tree(el, opts),
    };
})(window);
