/**
 * 한 장비에 이미 등록된 Simulation target 이 어디를 차지했는지 보여준다.
 * 값은 싣지 않는다 — 어느 자리가 잡혀 있고 어디가 비었는지만 본다.
 *
 * 주소가 촘촘한 프로토콜(Modbus·XGT·OPC)은 세 층으로 그린다. 장비 하나에
 * Simulation 이 수천 개 붙어도 칸을 하나씩 세는 건 읽히지 않기 때문이다.
 *   1) 요약 띠  — 영역 전체를 64칸으로 압축한 막대. 어디가 빽빽한지 한눈에 본다
 *   2) 구간     — 연속으로 잡힌 덩어리와 그 사이 빈 구간. 칸 7435개가 덩어리 82개로 준다
 *   3) 주변 격자 — 지금 입력 중인 주소 둘레만 칸으로. 내 자리가 비었는지 바로 본다
 * 칸에는 이름을 적지 않는다. 칸이 좁아 어차피 안 읽힌다 — 이름은 마우스를 올리면 뜬다.
 *
 * 주소가 듬성한 프로토콜(BACnet·SNMP 등)은 Server Data 의 Raw 처럼 목록으로 그린다.
 *
 * target 을 조각으로 나누는 일은 CtsTargetAid 의 프로토콜 스키마가 한다.
 *
 *   const map = CtsRegMap.mount(el, {
 *       device,                    // 장비 (protocol, communicationSettings)
 *       selfId,                    // 편집 중인 Simulation id (새로 만들면 없음)
 *       currentType: () => 'UI16', // 지금 고른 data type (레지스터 폭 계산에 쓴다)
 *   });
 *   map.setSiblings(list);   // 같은 장비의 Simulation [{id, name, target, policies}]
 *   map.setCurrent(text);    // 지금 편집 중인 target
 */
(function (global) {
    const BUCKETS = 64;      // 요약 띠의 칸 수
    const MAX_RUNS = 300;    // 구간 목록이 길어지면 여기까지만 그린다
    const XGT_WIDTH = { B: 1, W: 2, D: 4 };
    const MODBUS_REGIONS = {
        '1': 'Coil Status (FC01)',
        '2': 'Input Status (FC02)',
        '3': 'Holding Register (FC03)',
        '4': 'Input Register (FC04)',
    };

    function typeBytes(dt) {
        const aid = global.CtsTargetAid;
        const n = aid && typeof aid.bytesOf === 'function' ? aid.bytesOf(dt) : 0;
        return n || 2;
    }

    function firstType(sim) {
        return sim && sim.policies && sim.policies.length ? sim.policies[0].data_type : null;
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, m =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[m]));
    }

    function int(v, fallback) {
        const n = parseInt(v, 10);
        return isNaN(n) ? fallback : n;
    }

    /**
     * 프로토콜마다 '어느 영역의 몇 번 자리를 몇 칸 차지하는가'.
     * grid 가 아닌 프로토콜은 여기에 없고 목록으로 떨어진다.
     */
    const GRIDS = {
        MODBUS: {
            region: v => v.fc,
            title: (fc) => MODBUS_REGIONS[fc] || ('FC' + fc),
            pos: v => Number(v.address),
            // 홀딩·인풋 레지스터는 타입 폭만큼 여러 칸을 먹는다(CtsTargetAid.MODBUS.next 와 같은 셈)
            span: (v, dt) => (v.fc === '3' || v.fc === '4') ? Math.max(1, typeBytes(dt) / 2) : 1,
            total: cs => int(cs.registers, 65536),
            units: 'addresses',
        },
        XGT_PLC: {
            // 같은 블록이면 크기가 달라도 바이트가 겹친다. 그래서 영역은 블록, 자리는 바이트다
            region: v => v.block,
            title: (blk, lbl) => lbl('block', blk),
            pos: v => Number(v.address) * XGT_WIDTH[v.size],
            span: v => XGT_WIDTH[v.size],
            total: cs => int(cs.registers, 65536),
            units: 'bytes',
        },
        OPC_AGENT: {
            region: () => '*',
            title: () => 'Tag list',
            pos: v => Number(v.index),
            span: () => 1,
            total: cs => int(cs.registers, 65536),
            units: 'indexes',
        },
    };

    /** 주소는 target 을 쓸 때와 같은 10진수로 적는다. 눈으로 옮겨 적을 수 있어야 한다 */
    function addr(p) {
        return String(p);
    }

    class RegMap {
        constructor(el, opts) {
            this.el = el;
            this.opts = opts || {};
            this.siblings = [];
            this.current = '';
            this.sibCache = null;
            this.render();
        }

        setSiblings(list) {
            this.siblings = Array.isArray(list) ? list : [];
            this.sibCache = null;
            this.render();
        }

        setCurrent(text) {
            this.current = String(text || '').trim();
            this.render();
        }

        protocol() {
            return this.opts.device && this.opts.device.protocol;
        }

        scheme() {
            const aid = global.CtsTargetAid;
            return aid && typeof aid.scheme === 'function' ? aid.scheme(this.protocol()) : null;
        }

        ctx() {
            return {
                device: this.opts.device, siblings: this.siblings,
                selfId: this.opts.selfId, policyIds: () => [],
            };
        }

        settings() {
            return (this.opts.device && this.opts.device.communicationSettings) || {};
        }

        /** 조각 값을 사람이 읽는 이름으로. 스키마의 선택지 글을 그대로 쓴다. */
        labeller(scheme) {
            let parts = [];
            try { parts = scheme.parts({}, this.ctx()) || []; } catch (e) { parts = []; }
            return function (key, value) {
                const p = parts.filter(x => x.key === key)[0];
                const o = p && p.options ? p.options.filter(x => String(x.v) === String(value))[0] : null;
                return o ? o.t : String(value);
            };
        }

        currentType() {
            try { return this.opts.currentType ? this.opts.currentType() : null; } catch (e) { return null; }
        }

        makeEntry(scheme, grid, sim, text, self) {
            let v = null;
            try { v = scheme.parse(text, this.ctx()); } catch (e) { v = null; }
            if (!v) return null;
            const e = { sim: sim, target: text, self: !!self, v: v, type: self ? this.currentType() : firstType(sim) };
            if (grid) {
                e.pos = grid.pos(v);
                if (!isFinite(e.pos)) return null;
                e.span = Math.max(1, grid.span(v, e.type) || 1);
                e.region = String(grid.region(v));
            }
            return e;
        }

        /**
         * 형제 + 지금 편집 중인 것을 나눈 목록.
         * 형제는 한 번만 파싱해 둔다 — target 을 한 글자 칠 때마다 수천 개를 다시 뜯을 일이 아니다.
         */
        entries(scheme, grid) {
            if (!this.sibCache) {
                const out = [];
                let bad = 0;
                this.siblings.filter(s => s.id !== this.opts.selfId).forEach(s => {
                    const text = String(s.target || '').trim();
                    const e = this.makeEntry(scheme, grid, s, text, false);
                    if (e) out.push(e); else if (text) bad++;
                });
                this.sibCache = { list: out, unparsed: bad };
            }
            const list = this.sibCache.list.slice();
            let unparsed = this.sibCache.unparsed;
            if (this.current) {
                const e = this.makeEntry(scheme, grid, { name: 'This simulation' }, this.current, true);
                if (e) list.push(e); else unparsed++;
            }
            return { list: list, unparsed: unparsed };
        }

        render() {
            if (!this.el) return;
            const scheme = this.scheme();
            if (!scheme) { this.el.innerHTML = this.emptyHtml(); return; }
            const grid = GRIDS[this.protocol()] || null;
            const parsed = this.entries(scheme, grid);
            const list = parsed.list;

            let html = '<div class="rm-legend">'
                + '<span class="rm-item"><i class="rm-sw taken"></i>registered</span>'
                + '<span class="rm-item"><i class="rm-sw self"></i>this simulation</span>'
                + '<span class="rm-item"><i class="rm-sw clash"></i>same address</span>'
                + '<span class="rm-item"><i class="rm-sw free"></i>free</span>'
                + '</div>';

            if (!list.length) { this.el.innerHTML = html + this.emptyHtml(); return; }

            // 지금 쓰고 있는 자리가 비었는지가 제일 궁금한 것이다. 맨 위에서 말로 답한다
            html += this.verdictHtml(list, grid);
            html += grid ? this.gridHtml(list, grid) : this.listHtml(list);
            if (parsed.unparsed) {
                html += '<div class="rm-note">' + parsed.unparsed + ' target(s) do not match '
                    + esc(scheme.format) + ', so they are not placed.</div>';
            }
            this.el.innerHTML = html;
        }

        emptyHtml() {
            return '<div class="rm-empty">Nothing registered on this device yet.</div>';
        }

        /**
         * 지금 쓰는 target 이 비었는지 한 줄로 답한다.
         * <p>
         * 격자의 빨간 칸만으로는 알아채기 어렵다. 더구나 겹침의 원인이 같은 target 이 아니라
         * 남의 32비트 타입이 뒷주소까지 먹은 것일 때는 target 입력칸의 중복 경고(CtsTargetAid)도
         * 울리지 않는다 — 그쪽은 target 글자가 같은지만 보기 때문이다. 그래서 여기서 말로 적는다.
         */
        verdictHtml(list, grid) {
            const self = list.filter(e => e.self)[0];
            if (!self) return '';

            const hits = grid
                ? list.filter(e => !e.self && e.region === self.region
                    && e.pos < self.pos + self.span && self.pos < e.pos + e.span)
                : list.filter(e => !e.self && e.target === self.target);

            const span = grid && self.span > 1
                ? ' This type takes ' + self.span + ' ' + grid.units
                    + ' (' + addr(self.pos) + '–' + addr(self.pos + self.span - 1) + ').'
                : '';

            if (!hits.length) {
                return '<div class="rm-verdict is-free"><b>' + esc(self.target) + '</b> is free.'
                    + span + '</div>';
            }

            // 왜 겹치는지는 적지 않는다. 겹친다는 사실만 또렷하면 된다 — 누구와인지는 거들 뿐이다
            const says = hits.slice(0, 2).map(e => '“' + esc(e.sim.name) + '”').join(', ');

            return '<div class="rm-verdict is-clash"><b>' + esc(self.target) + '</b> is already used by '
                + says + (hits.length > 2 ? ' and ' + (hits.length - 2) + ' more' : '') + '.</div>';
        }

        /** 주변 격자 한 줄에 몇 칸을 넣을지. 주소가 10진수라 10의 배수로 끊는다. */
        cols() {
            const w = this.el.clientWidth || 420;
            if (w >= 860) return 40;
            if (w >= 440) return 20;
            return 10;
        }

        // ── 주소를 쓰는 프로토콜: 요약 띠 + 구간 + 주변 격자 ────────────
        gridHtml(list, grid) {
            const lbl = this.labeller(this.scheme());
            const total = grid.total(this.settings());
            const regions = {};
            list.forEach(e => { (regions[e.region] = regions[e.region] || []).push(e); });

            // 지금 쓰는 자리가 든 영역을 맨 앞으로. 나머지 순서는 그대로 둔다(정렬이 안정적이다)
            const selfRegion = (list.filter(e => e.self)[0] || {}).region;
            const keys = Object.keys(regions).sort();
            if (selfRegion != null) keys.sort((a, b) => (b === selfRegion) - (a === selfRegion));

            return keys.map(key => this.regionHtml(key, regions[key], grid, lbl, total)).join('');
        }

        regionHtml(key, items, grid, lbl, total) {
            // 자리 하나하나에 주인을 적어 둔다. 둘 이상이면 겹친 것이다
            const owners = {};
            items.forEach(e => {
                for (let i = 0; i < e.span; i++) {
                    const p = e.pos + i;
                    (owners[p] = owners[p] || []).push(e);
                }
            });
            const taken = Object.keys(owners).map(Number).sort((a, b) => a - b);
            const clashes = taken.filter(p => owners[p].length > 1).length;
            const self = items.filter(e => e.self)[0];
            const runs = this.runsOf(taken);

            return '<div class="rm-panel">'
                + '<div class="rm-panel-head">'
                +   '<span class="rm-panel-title">' + esc(grid.title(key, lbl)) + '</span>'
                +   '<span class="rm-panel-meta">'
                +     total + ' ' + grid.units + ' · ' + taken.length + ' used'
                +     (clashes ? ' · <b class="rm-clash">' + clashes + ' clashing</b>' : '')
                +   '</span>'
                + '</div>'
                + '<div class="rm-panel-body">'
                // 내 자리 둘레가 구간 목록보다 먼저다. 목록은 길어서 그 아래로 밀리면 안 보인다
                +   this.mapHtml(owners, taken, total, self)
                +   (self ? this.nearHtml(self, owners, total) : '')
                +   this.runsHtml(runs, owners, total)
                + '</div>'
                + '</div>';
        }

        /** 잡힌 자리를 이어지는 덩어리로 묶는다. [{from, to}] */
        runsOf(taken) {
            const runs = [];
            let from = taken[0], prev = taken[0];
            for (let i = 1; i < taken.length; i++) {
                if (taken[i] === prev + 1) { prev = taken[i]; continue; }
                runs.push({ from: from, to: prev });
                from = prev = taken[i];
            }
            runs.push({ from: from, to: prev });
            return runs;
        }

        // ── 1층: 영역 전체를 압축한 띠 ────────────────────────────────
        mapHtml(owners, taken, total, self) {
            const size = Math.max(1, Math.ceil(total / BUCKETS));
            const n = Math.ceil(total / size);
            const used = new Array(n).fill(0);
            const clash = new Array(n).fill(false);
            taken.forEach(p => {
                const b = Math.floor(p / size);
                if (b >= n) return;
                used[b]++;
                if (owners[p].length > 1) clash[b] = true;
            });

            let bars = '';
            for (let b = 0; b < n; b++) {
                const span = Math.min(size, total - b * size);
                const ratio = span ? used[b] / span : 0;
                // 한 칸이라도 잡혔으면 눈에 보이게 바닥을 깔아 준다
                const h = used[b] ? Math.max(12, Math.round(ratio * 100)) : 0;
                const tip = addr(b * size) + '–' + addr(b * size + span - 1)
                    + ' · ' + used[b] + ' of ' + span + ' used';
                bars += '<span class="rm-bucket' + (clash[b] ? ' is-clash' : '') + '"'
                    + ' title="' + esc(tip) + '"><i style="height:' + h + '%"></i></span>';
            }

            const cursor = self && total > 0
                ? '<span class="rm-cursor" style="left:' + ((self.pos + 0.5) / total * 100) + '%"></span>'
                : '';
            return '<div class="rm-map">' + bars + cursor + '</div>'
                + '<div class="rm-scale"><span>' + addr(0) + '</span>'
                + '<span>' + addr(Math.max(0, total - 1)) + '</span></div>';
        }

        // ── 2층: 이어지는 구간과 그 사이의 빈 구간 ────────────────────
        runsHtml(runs, owners, total) {
            const shown = runs.slice(0, MAX_RUNS);
            let rows = '', prev = -1;
            shown.forEach(r => {
                if (r.from - prev > 1) rows += this.freeRow(prev + 1, r.from - 1);
                rows += this.usedRow(r, owners);
                prev = r.to;
            });
            if (shown.length === runs.length && total - 1 > prev) {
                rows += this.freeRow(prev + 1, total - 1);
            }
            const more = runs.length > shown.length
                ? '<div class="rm-more">⋯ ' + (runs.length - shown.length) + ' more ranges ⋯</div>' : '';
            return '<div class="rm-runs">' + rows + more + '</div>';
        }

        usedRow(r, owners) {
            const names = [];
            const seen = {};
            let clash = false, self = false;
            for (let p = r.from; p <= r.to; p++) {
                const own = owners[p];
                if (!own) continue;
                if (own.length > 1) clash = true;
                own.forEach(e => {
                    if (e.self) self = true;
                    if (!seen[e.sim.name]) { seen[e.sim.name] = 1; names.push(e.sim.name); }
                });
            }
            const n = r.to - r.from + 1;
            // 주인이 하나뿐인 구간은 이름을 그대로 보여 준다. 적은 장비에서는 이게 제일 읽힌다
            const who = names.length === 1 ? esc(names[0]) : names.length + ' simulations';
            const tip = names.slice(0, 12).join('\n') + (names.length > 12 ? '\n… ' + (names.length - 12) + ' more' : '');
            return '<div class="rm-run' + (clash ? ' is-clash' : (self ? ' is-self' : ''))
                + '" title="' + esc(tip) + '">'
                + '<span class="rm-run-range">' + addr(r.from) + '–' + addr(r.to) + '</span>'
                + '<span class="rm-run-n">' + n + '</span>'
                + '<span class="rm-run-who">' + who + '</span>'
                + '</div>';
        }

        freeRow(from, to) {
            return '<div class="rm-run is-free">'
                + '<span class="rm-run-range">' + addr(from) + '–' + addr(to) + '</span>'
                + '<span class="rm-run-n">' + (to - from + 1) + '</span>'
                + '<span class="rm-run-who">free</span>'
                + '</div>';
        }

        // ── 3층: 지금 입력 중인 주소 둘레만 칸으로 ────────────────────
        nearHtml(self, owners, total) {
            const cols = this.cols();
            const row = Math.floor(self.pos / cols);
            const last = Math.max(0, Math.ceil(total / cols) - 1);
            const from = Math.max(0, row - 1), to = Math.min(last, row + 1);
            let body = '';
            for (let r = from; r <= to; r++) body += this.rowHtml(r, cols, owners, total);
            return '<div class="rm-near">'
                + '<div class="rm-near-head">near ' + addr(self.pos) + '</div>'
                + body + '</div>';
        }

        rowHtml(row, cols, owners, total) {
            const base = row * cols;
            let cells = '';
            for (let c = 0; c < cols; c++) {
                const p = base + c;
                if (p >= total) break;
                const own = owners[p];
                // 한 줄이 10의 배수라 끝자리만 적어도 줄 머리 주소에 그대로 이어 읽힌다
                const idx = String(p % 10);
                if (!own) { cells += '<span class="rm-cell">' + idx + '</span>'; continue; }
                const self = own.some(e => e.self);
                const cls = 'rm-cell is-taken' + (own.length > 1 ? ' is-clash' : (self ? ' is-self' : ''));
                const tip = own.map(e => e.sim.name + ' · ' + e.target + (e.type ? ' · ' + e.type : '')).join('\n');
                cells += '<span class="' + cls + '" title="' + esc(tip) + '">' + idx + '</span>';
            }
            return '<div class="rm-row">'
                + '<span class="rm-row-addr">' + addr(base) + '</span>'
                + '<div class="rm-row-cells" style="--cols:' + cols + '">' + cells + '</div>'
                + '</div>';
        }

        // ── 주소가 듬성한 프로토콜: Server Data 의 Raw 와 같은 목록 ────
        listHtml(list) {
            const rows = list.slice().sort((a, b) => a.target.localeCompare(b.target)).map(e => {
                const dup = list.some(o => o !== e && o.target === e.target);
                const cls = dup ? ' class="is-clash"' : (e.self ? ' class="is-self"' : '');
                return '<tr' + cls + '>'
                    + '<td class="rm-t">' + esc(e.target) + '</td>'
                    + '<td>' + esc(e.sim.name) + '</td>'
                    + '<td class="rm-type">' + esc(e.type || '') + '</td>'
                    + '</tr>';
            }).join('');

            return '<div class="rm-panel">'
                + '<div class="rm-panel-head">'
                +   '<span class="rm-panel-title">' + esc(this.listTitle()) + '</span>'
                +   '<span class="rm-panel-meta">registered ' + list.filter(e => !e.self).length + '</span>'
                + '</div>'
                + '<table class="rm-table">'
                +   '<thead><tr><th>Target</th><th>Simulation</th><th>Type</th></tr></thead>'
                +   '<tbody>' + rows + '</tbody>'
                + '</table>'
                + '</div>';
        }

        listTitle() {
            const p = this.protocol();
            return p === 'BACNET' ? 'BACnet Objects'
                : p === 'SNMP' ? 'OIDs'
                : p === 'ETHERNET_IP' ? 'Tags'
                : 'Registered targets';
        }
    }

    global.CtsRegMap = { mount: (el, opts) => new RegMap(el, opts) };
})(window);
