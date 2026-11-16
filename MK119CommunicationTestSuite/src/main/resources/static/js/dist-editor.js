/**
 * RANDOM 정책의 가중 분포 편집기.
 * <p>
 * 저장 형식은 서버 SimulationPolicy.distribution 과 같다 — { type: 'BINS', binCount, weights, sizes, mean?, std? }.
 * weights 는 상대값(서버가 합으로 나눈다), sizes 는 구간 폭의 상대 비율, mean·std 가 있으면 정규분포 모드다.
 * 화면에서는 모든 값을 확률(%)로 보여 주고 다룬다. 합은 늘 100 이다.
 * <p>
 * BINARY 는 0 과 1 두 구간만 둔다. 범위가 0~1 이고 폭이 같으면 서버의 정수 추출이 앞 구간에서 0, 뒤 구간에서 1 을 낸다.
 *
 *   const ed = DistEditor.mount(element);
 *   ed.setState(savedDistribution); ed.setRange(min, max); ed.setBinary(true|false);
 *   ed.validate();          // 오류 문구 또는 null
 *   ed.getDistribution();   // 저장할 객체
 */
(function (global) {
    const MIN_SEG = 2, MAX_SEG = 30, DEFAULT_SEG = 4;
    const H = 230, PAD = { l: 16, r: 16, t: 28, b: 30 };
    const SVGNS = 'http://www.w3.org/2000/svg';

    function clampInt(v, lo, hi, fallback) {
        const n = parseInt(v, 10);
        if (isNaN(n)) return fallback;
        return Math.max(lo, Math.min(hi, n));
    }

    function isNum(v) {
        return typeof v === 'number' && isFinite(v);
    }

    /** 표시용 숫자. 정수는 그대로, 아니면 크기에 맞춰 자른다. */
    function fmt(v) {
        if (!isNum(v)) return '';
        if (Number.isInteger(v)) return String(v);
        const a = Math.abs(v);
        return String(parseFloat(v.toFixed(a >= 100 ? 1 : a >= 1 ? 2 : 4)));
    }

    function fmtPct(p) {
        return String(parseFloat(p.toFixed(1))) + '%';
    }

    function round(v, digits) {
        const f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    function erf(x) {
        const sign = x < 0 ? -1 : 1;
        x = Math.abs(x);
        const t = 1 / (1 + 0.3275911 * x);
        const y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return sign * y;
    }

    function normalCdf(x, mu, sigma) {
        return 0.5 * (1 + erf((x - mu) / (sigma * Math.SQRT2)));
    }

    /** 상대 weight 를 합 100 인 확률로. 모두 0 이거나 길이가 맞지 않으면 균등. */
    function toProbs(weights, n) {
        if (!Array.isArray(weights) || weights.length !== n) return new Array(n).fill(100 / n);
        const w = weights.map(x => (isNum(Number(x)) && Number(x) > 0) ? Number(x) : 0);
        const sum = w.reduce((a, b) => a + b, 0);
        return sum > 0 ? w.map(x => x / sum * 100) : new Array(n).fill(100 / n);
    }

    /** sizes(상대 폭)를 0~1 의 경계 위치 n+1 개로. */
    function toFractions(sizes, n) {
        let s = (Array.isArray(sizes) && sizes.length === n) ? sizes.map(x => Math.max(0, Number(x) || 0)) : null;
        let total = s ? s.reduce((a, b) => a + b, 0) : 0;
        if (!(total > 0)) { s = new Array(n).fill(1); total = n; }
        const f = [0];
        let acc = 0;
        for (let i = 0; i < n - 1; i++) { acc += s[i]; f.push(acc / total); }
        f.push(1);
        return f;
    }

    function evenFractions(n) {
        const f = [];
        for (let i = 0; i <= n; i++) f.push(i / n);
        return f;
    }

    function el(tag, attrs, text) {
        const e = document.createElementNS(SVGNS, tag);
        for (const k in attrs) e.setAttribute(k, attrs[k]);
        if (text !== undefined) e.textContent = text;
        return e;
    }

    const TEMPLATE =
        '<div class="de-toolbar">' +
        '  <div class="btn-group btn-group-sm de-mode" role="group" aria-label="Distribution shape">' +
        '    <button type="button" class="btn btn-outline-primary" data-mode="custom">Custom</button>' +
        '    <button type="button" class="btn btn-outline-primary" data-mode="normal">Normal</button>' +
        '  </div>' +
        '  <div class="de-count">' +
        '    <span class="de-caption">Segments</span>' +
        '    <div class="input-group input-group-sm">' +
        '      <div class="input-group-prepend"><button type="button" class="btn btn-outline-secondary" data-act="dec" title="Fewer segments">&minus;</button></div>' +
        '      <input type="number" class="form-control text-center" data-ref="count" min="' + MIN_SEG + '" max="' + MAX_SEG + '" step="1" aria-label="Segments">' +
        '      <div class="input-group-append"><button type="button" class="btn btn-outline-secondary" data-act="inc" title="More segments">+</button></div>' +
        '    </div>' +
        '  </div>' +
        '  <div class="de-actions">' +
        '    <button type="button" class="btn btn-sm btn-outline-secondary" data-act="equalize" title="Give the selected segments the same probability">Equalize</button>' +
        '    <button type="button" class="btn btn-sm btn-outline-secondary" data-act="even-widths" title="Give the selected segments the same width">Even widths</button>' +
        '    <button type="button" class="btn btn-sm btn-outline-secondary" data-act="even-probs" title="Give every segment the same probability">Even probabilities</button>' +
        '  </div>' +
        '</div>' +
        '<div class="de-readout" data-ref="readout"></div>' +
        '<div class="de-chart" data-ref="chart"></div>' +
        '<div class="de-normal" data-ref="normal">' +
        '  <label>&mu; (mean) <input type="number" class="form-control form-control-sm" data-ref="mean" step="any"></label>' +
        '  <label>&sigma; (std. dev.) <input type="number" class="form-control form-control-sm" data-ref="std" step="any" min="0"></label>' +
        '</div>' +
        '<div class="de-table-wrap" data-ref="tableWrap">' +
        '  <table class="de-table">' +
        '    <thead><tr><th>#</th><th>From</th><th>To</th><th class="text-right">Probability</th></tr></thead>' +
        '    <tbody data-ref="tbody"></tbody>' +
        '  </table>' +
        '</div>' +
        '<div class="de-binary" data-ref="binary">' +
        '  <div class="de-binary-caption">Each run picks 0 or 1 with these probabilities. Drag the slider or type a percentage.</div>' +
        '  <div class="de-split">' +
        '    <div class="de-split-part de-split-0" data-ref="split0"><span class="de-split-value">0</span><span class="de-split-pct" data-ref="split0pct"></span></div>' +
        '    <div class="de-split-part de-split-1" data-ref="split1"><span class="de-split-value">1</span><span class="de-split-pct" data-ref="split1pct"></span></div>' +
        '  </div>' +
        '  <input type="range" class="custom-range de-split-range" min="0" max="100" step="1" data-ref="binRange" aria-label="Probability of 0">' +
        '  <div class="de-binary-inputs">' +
        '    <label>P(0) <input type="number" class="form-control form-control-sm" data-ref="p0" min="0" max="100" step="0.1"> %</label>' +
        '    <label>P(1) <input type="number" class="form-control form-control-sm" data-ref="p1" min="0" max="100" step="0.1"> %</label>' +
        '  </div>' +
        '</div>';

    class DistEditor {
        constructor(root) {
            this.root = root;
            root.classList.add('de');
            root.innerHTML = TEMPLATE;
            this.ref = {};
            root.querySelectorAll('[data-ref]').forEach(e => { this.ref[e.getAttribute('data-ref')] = e; });

            this.svg = el('svg', { class: 'de-svg', height: H });
            this.ref.chart.appendChild(this.svg);

            this.binary = false;
            this.bin = [50, 50];
            this.range = null;
            this.hover = null;
            this.drag = null;
            this.tableSig = null;
            this.setState(null);
            this.bind();

            // 숨어 있다 보이거나 창 크기가 바뀌면 다시 그린다 (폭을 픽셀로 쓰기 때문)
            this.observer = new ResizeObserver(() => this.render());
            this.observer.observe(this.ref.chart);
        }

        static mount(element) {
            if (!element.__distEditor) element.__distEditor = new DistEditor(element);
            return element.__distEditor;
        }

        destroy() {
            this.observer.disconnect();
            this.root.innerHTML = '';
            delete this.root.__distEditor;
        }

        // ── 상태 ─────────────────────────────────────────────

        /** 저장된 분포로 채운다. null 이면 균등 4 구간. */
        setState(d) {
            d = d || {};
            this.n = clampInt(d.binCount, MIN_SEG, MAX_SEG, DEFAULT_SEG);
            this.fr = toFractions(d.sizes, this.n);
            this.probs = toProbs(d.weights, this.n);
            this.selected = [];
            this.hover = null;
            if (isNum(d.mean) && isNum(d.std) && d.std > 0) {
                this.mode = 'normal';
                this.mean = d.mean;
                this.std = d.std;
                this.fr = evenFractions(this.n);
            } else {
                this.mode = 'custom';
                this.mean = this.std = null;
            }
            // BINARY 로 저장된 것(2 구간, 같은 폭)이면 그 확률을 0·1 에 쓴다
            this.bin = (this.n === 2) ? [this.probs[0], this.probs[1]] : [50, 50];
            this.tableSig = null;
            this.render();
        }

        /** 정책의 Range. 숫자가 아니거나 min >= max 면 값 대신 구간 번호로 축을 그린다. */
        setRange(min, max) {
            const r = (isNum(min) && isNum(max) && max > min) ? { min: min, max: max } : null;
            const same = (r === null && this.range === null)
                || (r && this.range && r.min === this.range.min && r.max === this.range.max);
            if (same) return;
            this.range = r;
            if (this.mode === 'normal') this.fitNormalToAxis();
            this.tableSig = null;
            this.render();
        }

        setBinary(on) {
            on = !!on;
            if (on === this.binary) return;
            this.binary = on;
            this.render();
        }

        axis() {
            return this.range ? { min: this.range.min, max: this.range.max } : { min: 0, max: this.n };
        }

        bounds() {
            const a = this.axis(), span = a.max - a.min;
            return this.fr.map(f => a.min + f * span);
        }

        /** 정규분포 모드의 μ·σ 가 축 밖이면 축 가운데·폭의 1/6 로 다시 잡는다. */
        fitNormalToAxis() {
            const a = this.axis(), span = a.max - a.min;
            if (!isNum(this.mean) || this.mean < a.min || this.mean > a.max) this.mean = (a.min + a.max) / 2;
            if (!isNum(this.std) || this.std <= 0 || this.std > span * 2) this.std = span / 6;
        }

        normalProbs() {
            const b = this.bounds();
            const a = this.axis(), span = a.max - a.min;
            const sigma = this.std > 0 ? this.std : span / 100;
            const w = [];
            for (let i = 0; i < this.n; i++) {
                const m = normalCdf(b[i + 1], this.mean, sigma) - normalCdf(b[i], this.mean, sigma);
                w.push(m > 0 ? m : 0);
            }
            return toProbs(w, this.n);
        }

        setMode(mode) {
            if (mode === this.mode) return;
            this.hover = null;
            if (mode === 'normal') {
                this.mode = 'normal';
                this.fr = evenFractions(this.n);
                this.selected = [];
                this.fitNormalToAxis();
            } else {
                // 정규분포가 만든 확률을 그대로 이어받아 손으로 고칠 수 있게 한다
                this.probs = this.normalProbs();
                this.mode = 'custom';
            }
            this.tableSig = null;
            this.render();
        }

        setCount(n) {
            n = clampInt(n, MIN_SEG, MAX_SEG, this.n);
            if (n === this.n) { this.render(); return; }
            this.n = n;
            this.hover = null;
            this.fr = evenFractions(n);
            this.probs = new Array(n).fill(100 / n);
            this.selected = [];
            this.tableSig = null;
            this.render();
        }

        /** 구간 i 의 확률을 p 로 두고, 나머지는 지금 비율대로 남은 몫을 나눈다. */
        setProb(i, p) {
            p = Math.max(0, Math.min(100, p));
            const rest = 100 - p;
            let others = 0;
            for (let j = 0; j < this.n; j++) if (j !== i) others += this.probs[j];
            for (let j = 0; j < this.n; j++) {
                if (j === i) continue;
                this.probs[j] = others > 0 ? this.probs[j] / others * rest : rest / (this.n - 1);
            }
            this.probs[i] = p;
        }

        /** 경계 i(1..n-1)를 축 값 v 로. 이웃 경계를 넘지 않는다. */
        setBoundary(i, v) {
            const a = this.axis(), span = a.max - a.min;
            const gap = 0.002;
            let f = (v - a.min) / span;
            f = Math.max(this.fr[i - 1] + gap, Math.min(this.fr[i + 1] - gap, f));
            this.fr[i] = f;
        }

        equalizeSelected() {
            const sel = this.selected;
            if (sel.length < 2) return;
            const each = sel.reduce((s, i) => s + this.probs[i], 0) / sel.length;
            sel.forEach(i => { this.probs[i] = each; });
            this.render();
        }

        /** 선택한 구간들의 폭을 합친 만큼을 똑같이 나눈다. 선택하지 않은 구간의 폭은 그대로다. */
        evenWidthsSelected() {
            const sel = this.selected;
            if (sel.length < 2) return;
            const widths = [];
            for (let i = 0; i < this.n; i++) widths.push(this.fr[i + 1] - this.fr[i]);
            const each = sel.reduce((s, i) => s + widths[i], 0) / sel.length;
            sel.forEach(i => { widths[i] = each; });
            for (let i = 0; i < this.n - 1; i++) this.fr[i + 1] = this.fr[i] + widths[i];
            this.fr[this.n] = 1;
            this.tableSig = null;
            this.render(true);
        }

        toggleSelect(i) {
            const k = this.selected.indexOf(i);
            if (k >= 0) this.selected.splice(k, 1); else this.selected.push(i);
            this.render();
        }

        currentProbs() {
            return this.mode === 'normal' ? this.normalProbs() : this.probs;
        }

        setBinaryP0(p0) {
            p0 = Math.max(0, Math.min(100, p0));
            this.bin = [p0, 100 - p0];
            this.render();
        }

        // ── 저장 ─────────────────────────────────────────────

        validate() {
            if (this.binary) return null;
            if (this.mode === 'normal' && !(this.std > 0)) return 'σ (std. dev.) must be greater than 0.';
            const sum = this.currentProbs().reduce((a, b) => a + b, 0);
            return sum > 0 ? null : 'At least one segment needs a probability above 0%.';
        }

        getDistribution() {
            if (this.binary) {
                return { type: 'BINS', binCount: 2, weights: [round(this.bin[0], 4), round(this.bin[1], 4)], sizes: [1, 1] };
            }
            const sizes = [];
            for (let i = 0; i < this.n; i++) sizes.push(round((this.fr[i + 1] - this.fr[i]) * this.n, 6));
            const out = {
                type: 'BINS',
                binCount: this.n,
                weights: this.currentProbs().map(p => round(p, 4)),
                sizes: sizes
            };
            if (this.mode === 'normal') {
                out.mean = this.mean;
                out.std = this.std;
            }
            return out;
        }

        // ── 이벤트 ───────────────────────────────────────────

        bind() {
            const root = this.root, ref = this.ref;
            root.addEventListener('click', e => {
                const m = e.target.closest('[data-mode]');
                if (m) { this.setMode(m.getAttribute('data-mode')); return; }
                const a = e.target.closest('[data-act]');
                if (!a) return;
                const act = a.getAttribute('data-act');
                if (act === 'dec') this.setCount(this.n - 1);
                else if (act === 'inc') this.setCount(this.n + 1);
                else if (act === 'equalize') this.equalizeSelected();
                else if (act === 'even-widths') this.evenWidthsSelected();
                else if (act === 'even-probs') { this.probs = new Array(this.n).fill(100 / this.n); this.render(); }
            });
            ref.count.addEventListener('change', () => this.setCount(ref.count.value));
            ref.mean.addEventListener('change', () => {
                const a = this.axis(), v = parseFloat(ref.mean.value);
                if (isNum(v)) this.mean = Math.max(a.min, Math.min(a.max, v));
                this.render();
            });
            ref.std.addEventListener('change', () => {
                const v = parseFloat(ref.std.value);
                if (isNum(v) && v > 0) this.std = v;
                this.render();
            });
            ref.tbody.addEventListener('change', e => {
                const input = e.target;
                const i = parseInt(input.getAttribute('data-i'), 10);
                const v = parseFloat(input.value);
                if (isNum(v)) {
                    if (input.getAttribute('data-field') === 'prob') this.setProb(i, v);
                    else if (input.getAttribute('data-field') === 'to') this.setBoundary(i + 1, v);
                }
                this.render(true);
            });
            ref.binRange.addEventListener('input', () => this.setBinaryP0(parseFloat(ref.binRange.value)));
            ref.p0.addEventListener('change', () => { const v = parseFloat(ref.p0.value); if (isNum(v)) this.setBinaryP0(v); else this.render(); });
            ref.p1.addEventListener('change', () => { const v = parseFloat(ref.p1.value); if (isNum(v)) this.setBinaryP0(100 - v); else this.render(); });

            const svg = this.svg;
            svg.addEventListener('pointerdown', e => {
                const t = e.target.closest('[data-role]');
                if (!t) return;
                const role = t.getAttribute('data-role');
                const i = parseInt(t.getAttribute('data-i'), 10);
                if (role === 'bar') { this.toggleSelect(i); return; }
                if (role === 'seg') return;   // 정규분포 모드의 막대는 μ·σ 가 정한다
                e.preventDefault();
                svg.setPointerCapture(e.pointerId);
                this.drag = { role: role, i: i, yMax: this.yMax };
                this.dragTo(e);
            });
            svg.addEventListener('pointermove', e => {
                if (this.drag) { this.dragTo(e); return; }
                const t = e.target.closest('[data-i]');
                const h = t ? parseInt(t.getAttribute('data-i'), 10) : null;
                const role = t ? t.getAttribute('data-role') : null;
                const key = h === null ? null : role + ':' + h;
                if (key !== this.hover) { this.hover = key; this.render(); }
            });
            const end = () => {
                if (!this.drag) return;
                this.drag = null;
                this.render(true);
            };
            svg.addEventListener('pointerup', end);
            svg.addEventListener('pointercancel', end);
            svg.addEventListener('pointerleave', () => {
                if (!this.drag && this.hover !== null) { this.hover = null; this.render(); }
            });
        }

        dragTo(e) {
            // 그림 좌표(CSS px)로. 큰 화면 배율에서 마우스 좌표는 눈에 보이는 px 다 (js/cts-zoom.js)
            const p = CtsZoom.point(e, this.svg);
            const x = p.x, y = p.y;
            const g = this.geom;
            const d = this.drag;
            if (d.role === 'weight') {
                this.setProb(d.i, (g.base - y) / (g.base - g.top) * d.yMax);
            } else if (d.role === 'bnd') {
                this.setBoundary(d.i, g.toVal(x));
            } else if (d.role === 'mu') {
                const a = this.axis();
                this.mean = Math.max(a.min, Math.min(a.max, g.toVal(x)));
            } else if (d.role === 'sigma') {
                const a = this.axis(), span = a.max - a.min;
                this.std = Math.max(span / 200, Math.min(span * 2, Math.abs(g.toVal(x) - this.mean)));
            }
            this.render();
        }

        // ── 그리기 ───────────────────────────────────────────

        /** @param force 표의 입력칸을 다시 채운다(입력 중인 칸도) */
        render(force) {
            const ref = this.ref, root = this.root;
            root.classList.toggle('is-binary', this.binary);
            root.classList.toggle('is-normal', !this.binary && this.mode === 'normal');
            if (this.binary) { this.renderBinary(); return; }

            root.querySelectorAll('[data-mode]').forEach(b => {
                const on = b.getAttribute('data-mode') === this.mode;
                b.classList.toggle('active', on);
                b.setAttribute('aria-pressed', on ? 'true' : 'false');
            });
            if (document.activeElement !== ref.count) ref.count.value = this.n;
            const many = this.selected.length >= 2;
            const eq = root.querySelector('[data-act="equalize"]');
            eq.disabled = !many;
            eq.textContent = many ? 'Equalize ' + this.selected.length + ' selected' : 'Equalize';
            const ew = root.querySelector('[data-act="even-widths"]');
            ew.disabled = !many;
            ew.textContent = many ? 'Even widths of ' + this.selected.length : 'Even widths';
            if (this.mode === 'normal') {
                if (document.activeElement !== ref.mean) ref.mean.value = fmt(this.mean);
                if (document.activeElement !== ref.std) ref.std.value = fmt(this.std);
            }
            this.renderChart();
            this.renderTable(force);
            this.renderReadout();
        }

        renderReadout() {
            const probs = this.currentProbs(), b = this.bounds();
            let text;
            const active = this.drag ? this.drag.role + ':' + this.drag.i : this.hover;
            if (active) {
                const role = active.split(':')[0], i = parseInt(active.split(':')[1], 10);
                if (role === 'bnd') {
                    text = 'Boundary ' + fmt(b[i]) + '  ·  Segment ' + i + ' ' + fmtPct(probs[i - 1])
                        + '  |  Segment ' + (i + 1) + ' ' + fmtPct(probs[i]);
                } else if (role === 'mu' || role === 'sigma') {
                    text = 'μ ' + fmt(this.mean) + '  ·  σ ' + fmt(this.std);
                } else {
                    text = 'Segment ' + (i + 1) + '  ·  ' + fmt(b[i]) + ' – ' + fmt(b[i + 1]) + '  ·  ' + fmtPct(probs[i]);
                }
                this.ref.readout.classList.add('is-active');
            } else {
                text = this.mode === 'normal'
                    ? 'Drag μ to move the center and σ to change the spread, or type them below.'
                    : 'Drag the top of a bar to set its probability. Drag the lines between bars to resize segments. Click bars to select them.';
                if (!this.range) text += ' Enter the range to see values on the axis.';
                this.ref.readout.classList.remove('is-active');
            }
            this.ref.readout.textContent = text;
        }

        renderChart() {
            const svg = this.svg;
            const W = this.ref.chart.clientWidth;
            if (!W) return;   // 숨어 있다. 보이면 ResizeObserver 가 다시 부른다
            svg.setAttribute('width', W);
            svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
            while (svg.firstChild) svg.removeChild(svg.firstChild);

            const normal = this.mode === 'normal';
            const probs = this.currentProbs();
            const b = this.bounds();
            const a = this.axis(), span = a.max - a.min;
            const left = PAD.l, right = W - PAD.r, top = PAD.t, base = H - PAD.b;
            const maxP = Math.max.apply(null, probs);
            // 드래그 중에는 축을 고정해 막대가 손을 따라오게 한다
            const yMax = this.drag && this.drag.role === 'weight' ? this.drag.yMax : Math.min(100, Math.max(10, maxP * 1.25));
            this.yMax = yMax;
            const px = v => left + (v - a.min) / span * (right - left);
            const py = p => base - Math.min(p, yMax) / yMax * (base - top);
            this.geom = { top: top, base: base, toVal: x => a.min + (x - left) / (right - left) * span };

            const active = this.drag ? this.drag.role + ':' + this.drag.i : this.hover;
            const activeSeg = active && /^(bar|weight):/.test(active) ? parseInt(active.split(':')[1], 10) : null;

            svg.appendChild(el('line', { class: 'de-guide', x1: left, x2: right, y1: top, y2: top }));

            // 확률 라벨은 곡선·경계선 위에 오도록 맨 나중에 붙인다
            const labels = [];
            for (let i = 0; i < this.n; i++) {
                const x0 = px(b[i]), x1 = px(b[i + 1]);
                const w = Math.max(1, x1 - x0 - 2);
                const y = py(probs[i]);
                const cls = 'de-bar' + (this.selected.indexOf(i) >= 0 ? ' is-sel' : '') + (activeSeg === i ? ' is-hover' : '');
                svg.appendChild(el('rect', {
                    class: cls, 'data-role': normal ? 'seg' : 'bar', 'data-i': i,
                    x: x0 + 1, y: y, width: w, height: Math.max(0, base - y), rx: 3
                }));
                if (x1 - x0 >= 30) {
                    labels.push(el('text', { class: 'de-pct', x: (x0 + x1) / 2, y: Math.max(top - 6, y - 6), 'text-anchor': 'middle' }, fmtPct(probs[i])));
                }
                if (!normal) {
                    const gw = Math.max(6, Math.min(28, w - 8));
                    svg.appendChild(el('rect', { class: 'de-grip-pill', x: (x0 + x1) / 2 - gw / 2, y: y - 2, width: gw, height: 4, rx: 2 }));
                    svg.appendChild(el('rect', { class: 'de-hit de-hit-weight', 'data-role': 'weight', 'data-i': i, x: x0 + 1, y: y - 7, width: w, height: 14 }));
                }
            }

            svg.appendChild(el('line', { class: 'de-base', x1: left, x2: right, y1: base, y2: base }));

            if (!normal) {
                for (let i = 1; i < this.n; i++) {
                    const x = px(b[i]);
                    const on = active === 'bnd:' + i;
                    svg.appendChild(el('line', { class: 'de-bnd' + (on ? ' is-hover' : ''), x1: x, x2: x, y1: top, y2: base }));
                    svg.appendChild(el('rect', { class: 'de-bnd-knob' + (on ? ' is-hover' : ''), x: x - 4, y: base - 7, width: 8, height: 14, rx: 3 }));
                    svg.appendChild(el('rect', { class: 'de-hit de-hit-bnd', 'data-role': 'bnd', 'data-i': i, x: x - 6, y: top, width: 12, height: base - top + 8 }));
                }
            } else {
                this.drawNormal(svg, px, py, a, span, top, base, active);
            }
            labels.forEach(l => svg.appendChild(l));

            // 축 눈금: 양 끝은 늘, 안쪽 경계는 자리가 날 때만
            let lastX = -Infinity;
            for (let i = 0; i <= this.n; i++) {
                const x = px(b[i]);
                const edge = i === 0 || i === this.n;
                if (!edge && (x - lastX < 38 || right - x < 38)) continue;
                const label = this.range ? fmt(b[i]) : String(i);
                const anchor = i === 0 ? 'start' : i === this.n ? 'end' : 'middle';
                svg.appendChild(el('text', { class: 'de-axis', x: x, y: base + 18, 'text-anchor': anchor }, label));
                lastX = x;
            }
        }

        drawNormal(svg, px, py, a, span, top, base, active) {
            const sigma = this.std > 0 ? this.std : span / 100;
            // 곡선은 구간 확률과 같은 눈금으로 그린다: 밀도 × 평균 구간 폭
            const binW = span / this.n;
            let d = '';
            const steps = 120;
            for (let k = 0; k <= steps; k++) {
                const v = a.min + span * k / steps;
                const dens = Math.exp(-0.5 * Math.pow((v - this.mean) / sigma, 2)) / (sigma * Math.sqrt(2 * Math.PI));
                d += (k ? ' L ' : 'M ') + px(v).toFixed(1) + ' ' + py(dens * binW * 100).toFixed(1);
            }
            svg.appendChild(el('path', { class: 'de-curve', d: d }));

            const clampX = v => Math.max(px(a.min), Math.min(px(a.max), px(v)));
            const xm = clampX(this.mean), xs = clampX(this.mean + sigma), xs2 = clampX(this.mean - sigma);
            svg.appendChild(el('rect', { class: 'de-sigma-band', x: xs2, y: top, width: Math.max(0, xs - xs2), height: base - top }));
            svg.appendChild(el('line', { class: 'de-sigma', x1: xs2, x2: xs2, y1: top, y2: base }));
            svg.appendChild(el('line', { class: 'de-sigma' + (active === 'sigma:0' ? ' is-hover' : ''), x1: xs, x2: xs, y1: top, y2: base }));
            svg.appendChild(el('line', { class: 'de-mu' + (active === 'mu:0' ? ' is-hover' : ''), x1: xm, x2: xm, y1: top, y2: base }));
            const tag = (x, cls, label) => {
                svg.appendChild(el('rect', { class: cls, x: x - 9, y: top - 22, width: 18, height: 16, rx: 8 }));
                svg.appendChild(el('text', { class: 'de-tag-text', x: x, y: top - 10, 'text-anchor': 'middle' }, label));
            };
            tag(xm, 'de-tag de-tag-mu', 'μ');
            tag(xs, 'de-tag de-tag-sigma', 'σ');
            svg.appendChild(el('rect', { class: 'de-hit de-hit-bnd', 'data-role': 'sigma', 'data-i': 0, x: xs - 7, y: top - 24, width: 14, height: base - top + 24 }));
            svg.appendChild(el('rect', { class: 'de-hit de-hit-bnd', 'data-role': 'mu', 'data-i': 0, x: xm - 7, y: top - 24, width: 14, height: base - top + 24 }));
        }

        renderTable(force) {
            const ref = this.ref;
            const normal = this.mode === 'normal';
            const probs = this.currentProbs(), b = this.bounds();
            const sig = this.n + ':' + this.mode + ':' + (this.range ? 'r' : '-');
            if (sig !== this.tableSig) {
                let html = '';
                for (let i = 0; i < this.n; i++) {
                    const last = i === this.n - 1;
                    const toCell = (normal || last || !this.range)
                        ? '<span data-i="' + i + '" data-field="to-text"></span>'
                        : '<input type="number" step="any" class="form-control form-control-sm" data-i="' + i + '" data-field="to" aria-label="Segment ' + (i + 1) + ' upper bound">';
                    const probCell = normal
                        ? '<span data-i="' + i + '" data-field="prob-text"></span>'
                        : '<div class="de-prob"><input type="number" min="0" max="100" step="0.1" class="form-control form-control-sm" data-i="' + i + '" data-field="prob" aria-label="Segment ' + (i + 1) + ' probability"><span>%</span></div>';
                    html += '<tr data-row="' + i + '"><td class="text-muted">' + (i + 1) + '</td>'
                        + '<td><span data-i="' + i + '" data-field="from"></span></td>'
                        + '<td>' + toCell + '</td><td class="text-right">' + probCell + '</td></tr>';
                }
                ref.tbody.innerHTML = html;
                this.tableSig = sig;
                force = true;
            }
            const active = this.drag ? this.drag.role + ':' + this.drag.i : this.hover;
            const activeSeg = active && /^(bar|weight|seg):/.test(active) ? parseInt(active.split(':')[1], 10) : null;
            ref.tbody.querySelectorAll('tr').forEach(tr => {
                const i = parseInt(tr.getAttribute('data-row'), 10);
                tr.classList.toggle('is-sel', this.selected.indexOf(i) >= 0);
                tr.classList.toggle('is-hover', activeSeg === i);
            });
            const setVal = (field, i, v) => {
                const e = ref.tbody.querySelector('[data-field="' + field + '"][data-i="' + i + '"]');
                if (!e) return;
                if (e.tagName === 'INPUT') {
                    if (force || document.activeElement !== e) e.value = v;
                } else {
                    e.textContent = v;
                }
            };
            for (let i = 0; i < this.n; i++) {
                setVal('from', i, this.range ? fmt(b[i]) : '—');
                setVal('to', i, fmt(b[i + 1]));
                setVal('to-text', i, this.range ? fmt(b[i + 1]) : '—');
                setVal('prob', i, String(round(probs[i], 1)));
                setVal('prob-text', i, fmtPct(probs[i]));
            }
        }

        renderBinary() {
            const ref = this.ref;
            const p0 = this.bin[0], p1 = this.bin[1];
            ref.split0.style.flexBasis = p0 + '%';
            ref.split1.style.flexBasis = p1 + '%';
            ref.split0.classList.toggle('is-empty', p0 < 0.5);
            ref.split1.classList.toggle('is-empty', p1 < 0.5);
            ref.split0pct.textContent = fmtPct(p0);
            ref.split1pct.textContent = fmtPct(p1);
            if (document.activeElement !== ref.binRange) ref.binRange.value = Math.round(p0);
            if (document.activeElement !== ref.p0) ref.p0.value = round(p0, 1);
            if (document.activeElement !== ref.p1) ref.p1.value = round(p1, 1);
        }
    }

    global.DistEditor = DistEditor;
})(window);
