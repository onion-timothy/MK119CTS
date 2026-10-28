/*
 * Test Plan 타이밍 다이어그램. 리스트의 Timeline(읽기 전용)과 편집기가 함께 쓴다.
 *
 *  - Action 은 실행 시각(at, 시작부터 센 초)에 실행된다. 시각 순으로, 같은 시각이면 목록 순서로 #번호를 단다.
 *  - 실패하면 Plan 을 멈추는(stop) Action 중 가장 이른 것의 시각에 세로선을 긋는다. 그 뒤는 실패에 따라 실행되지 않을 수 있다.
 *  - End(실행 시간)는 마지막 lock 이 풀리는 시각이다. lock 이 없으면 마지막 실행 시각. 서버의 TestPlanTiming 과 같은 규칙.
 *  - Lane 하나 = 저장 칸 하나 (Modbus 레지스터/비트, XGT byte, 그 밖은 target). 칸은 서버가 계산해 준다(cells).
 *  - Action 하나 = 블록 하나. 차지하는 칸의 Lane 들을 세로로 묶는다.
 *  - 블록은 값이 유지되는 영역이다: 그 시각부터 같은 칸에 다음 쓰기가 들어오는 시각까지.
 *    다시 쓰지 않는 칸은 End 뒤 오른쪽 끝까지 이어 그린다 — Plan 이 끝나도 값과 lock 은 남는다.
 *    lock 구간은 진하게, lock 이 끝난 뒤는 옅게 그린다(시뮬레이션이 덮을 수 있다).
 *
 * 모델: { items: [{ uid, deviceId, deviceName, protocol, target, value, dataType, lockSeconds, at, stop, cells }] }
 * place() 가 실행 순서(order)와 model.end 를 채운다.
 */
(function (global) {
    'use strict';

    var NS = 'http://www.w3.org/2000/svg';
    var LANE_H = 26, GROUP_H = 22, AXIS_H = 26, PAD_R = 24;
    var TICK_STEPS = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200];
    var PALETTE = ['#2f80ed', '#27ae60', '#9b51e0', '#f2994a', '#219ebc', '#eb5757', '#6d8b00', '#c2185b'];

    /**
     * 실행 순서를 정한다: 시각 순, 같은 시각이면 목록 순서. 각 item 의 order(0부터)와
     * model.sorted(실행 순서의 목록), model.end(실행 시간)를 채운다.
     * 실행 시간 = 마지막 lock 이 풀리는 시각. 같은 칸에 나중 쓰기가 들어오면 앞 lock 은 그때 바뀌거나 풀리므로,
     * 칸마다 마지막으로 쓴 Action 의 at + lock 만 보면 된다(앞 lock 은 늦어도 마지막 실행 시각에 끝난다).
     */
    function place(model) {
        var tagged = model.items.map(function (it, i) { return { it: it, i: i }; });
        tagged.sort(function (a, b) { return ((a.it.at || 0) - (b.it.at || 0)) || (a.i - b.i); });
        var end = 0, lockEnds = {};
        model.sorted = tagged.map(function (t, k) {
            var it = t.it;
            it.order = k;
            end = Math.max(end, it.at || 0);
            (it.cells || []).forEach(function (c) { lockEnds[cellKey(it.deviceId, c)] = (it.at || 0) + (it.lockSeconds || 0); });
            return it;
        });
        Object.keys(lockEnds).forEach(function (k) { end = Math.max(end, lockEnds[k]); });
        model.end = end;
        return model;
    }

    /** 서버의 Action 목록 → 다이어그램 모델. */
    function fromActions(actions) {
        var items = (actions || []).map(function (a, i) {
            return {
                uid: 'a' + i, deviceId: a.deviceId, deviceName: a.deviceName, protocol: a.protocol,
                target: a.target, value: a.value, dataType: a.dataType, lockSeconds: a.lockSeconds || 0,
                at: a.atSeconds || 0, stop: !!a.stopOnFailure, cells: a.cells || [a.target]
            };
        });
        return place({ items: items });
    }

    /** 다이어그램 모델 → 서버의 Action 목록. 저장할 때 쓴다. 서버가 시각 순으로 다시 늘어놓는다. */
    function toActions(model) {
        return place(model).sorted.map(function (it) {
            return {
                deviceId: it.deviceId, target: it.target, value: it.value,
                dataType: it.dataType || null, lockSeconds: it.lockSeconds || 0,
                atSeconds: Math.max(0, it.at || 0), stopOnFailure: !!it.stop
            };
        });
    }

    /** 칸 이름을 주소 순으로 비교한다. 3_2 < 3_10, %DB9 < %DB10. */
    function cellCompare(a, b) {
        var ra = /^(.*?)(\d+)$/.exec(a), rb = /^(.*?)(\d+)$/.exec(b);
        if (ra && rb && ra[1] === rb[1]) return parseInt(ra[2], 10) - parseInt(rb[2], 10);
        return a < b ? -1 : a > b ? 1 : 0;
    }

    function cellKey(deviceId, cell) { return deviceId + ':' + cell; }

    /**
     * Lane 과 블록의 배치를 계산한다.
     * span 은 그림의 오른쪽 끝이다: End 에 조금 더한다(End 뒤에도 값이 남음을 보이려고).
     */
    function layout(model) {
        place(model);
        var items = model.sorted;
        var devices = [], byDevice = {};
        items.forEach(function (it) {
            var d = byDevice[it.deviceId];
            if (!d) {
                d = byDevice[it.deviceId] = { deviceId: it.deviceId, name: it.deviceName || ('#' + it.deviceId), cells: {} };
                devices.push(d);
            }
            (it.cells || []).forEach(function (c) { d.cells[c] = true; });
        });
        var span = model.end + Math.max(2, Math.ceil(model.end * 0.1));
        devices.sort(function (a, b) { return a.name < b.name ? -1 : a.name > b.name ? 1 : 0; });
        var lanes = [], laneIndex = {}, y = 0, groups = [];
        devices.forEach(function (d) {
            groups.push({ name: d.name, y: y });
            y += GROUP_H;
            Object.keys(d.cells).sort(cellCompare).forEach(function (c) {
                laneIndex[cellKey(d.deviceId, c)] = lanes.length;
                lanes.push({ deviceId: d.deviceId, deviceName: d.name, cell: c, y: y });
                y += LANE_H;
            });
        });
        // 칸마다 다음 쓰기 시각을 찾아 값 유지 영역을 정한다. 다시 쓰지 않으면 오른쪽 끝까지
        var blocks = items.map(function (it, i) {
            var segs = (it.cells || []).map(function (c) {
                var key = cellKey(it.deviceId, c), until = span, cutBy = null;
                for (var j = i + 1; j < items.length; j++) {
                    var o = items[j];
                    if (o.deviceId === it.deviceId && (o.cells || []).indexOf(c) >= 0) {
                        until = o.at; cutBy = j; break;
                    }
                }
                return { lane: laneIndex[key], cell: c, from: it.at, until: Math.max(it.at, until), cutBy: cutBy };
            });
            return { item: it, index: i, segs: segs };
        });
        return { items: items, lanes: lanes, groups: groups, blocks: blocks, height: y, end: model.end, span: span };
    }

    /** 같은 시각에 같은 칸을 쓰는 Action 쌍 (A15). @return {laterUid: earlierUid} */
    function conflicts(model) {
        var seen = {}, out = {};
        place(model).items.forEach(function (it) {
            (it.cells || []).forEach(function (c) {
                var k = it.at + '|' + cellKey(it.deviceId, c);
                if (seen[k] && seen[k] !== it.uid) out[it.uid] = seen[k];
                else seen[k] = it.uid;
            });
        });
        return out;
    }

    function fmtTime(s) {
        if (s < 60) return s + 's';
        var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
        var mm = (h ? (m < 10 ? '0' : '') : '') + m, ss = (sec < 10 ? '0' : '') + sec;
        return h ? h + ':' + mm + ':' + ss : mm + ':' + ss;
    }

    function fmtValue(v) {
        if (v === null || v === undefined || v === '') return '';
        var n = Number(v);
        if (!isFinite(n)) return String(v);
        return Math.abs(n) >= 1e6 || (n !== 0 && Math.abs(n) < 1e-3) ? n.toExponential(2) : String(+n.toFixed(4));
    }

    function el(name, attrs, parent) {
        var e = document.createElementNS(NS, name);
        for (var k in attrs) if (attrs.hasOwnProperty(k)) e.setAttribute(k, attrs[k]);
        if (parent) parent.appendChild(e);
        return e;
    }

    function colorOf(it) {
        var s = it.deviceId + '|' + it.target, h = 0;
        for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
        return PALETTE[h % PALETTE.length];
    }

    /**
     * 그린다.
     * opts: { editable, selected, errors: {uid: true}, playhead: seconds|null,
     *         onSelect(uid), onMove(uid, at), onLock(uid, lockSeconds), onAdd(lane, at) }
     * onMove 는 블록을 끈 시각을, onAdd 는 더블클릭한 Lane 과 그 자리의 시각을 넘긴다.
     */
    function Timeline(container, opts) {
        this.container = container;
        this.opts = opts || {};
        this.pxPerSec = null;
        container.classList.add('tpt');
        container.innerHTML =
            '<div class="tpt-labels"><div class="tpt-labels-inner"></div></div>' +
            '<div class="tpt-scroll"><svg class="tpt-svg" xmlns="' + NS + '"></svg></div>' +
            '<div class="tpt-empty">No actions yet.</div>';
        this.labels = container.querySelector('.tpt-labels-inner');
        this.scroll = container.querySelector('.tpt-scroll');
        this.svg = container.querySelector('.tpt-svg');
        this.empty = container.querySelector('.tpt-empty');
        var self = this;
        this.scroll.addEventListener('scroll', function () {
            self.labels.style.transform = 'translateY(' + (-self.scroll.scrollTop) + 'px)';
        });
        // 그린 뒤에 그림 영역의 폭이 바뀌면(옆 패널·사이드바·스크롤바·창 크기) 그 폭으로 다시 그린다.
        // 처음 그릴 때의 폭이 최종 폭이 아니면 Fit 을 눌러야 맞던 문제
        if (global.ResizeObserver) {
            var seenWidth = null;
            new ResizeObserver(function () {
                var w = self.scroll.clientWidth;
                if (w === seenWidth) return;
                seenWidth = w;
                if (self.model) global.requestAnimationFrame(function () { self.render(); });
            }).observe(this.scroll);
        }
        // 더블클릭으로 Action 을 넣는다. 브라우저의 dblclick 은 쓰지 않는다 — 첫 클릭이 블록을 고르며 그림을
        // 다시 그리면 두 번째 클릭이 다른 요소에 떨어져 dblclick 이 생기지 않는다. 그래서 그림 전체에서
        // "짧은 시간 안에 같은 자리를 두 번 누름" 을 직접 본다. Lane 은 y 로, 시각은 x 로 찾는다.
        var lastDown = null;
        this.svg.addEventListener('mousedown', function (ev) {
            if (ev.button !== 0 || !self.opts.editable || !self.opts.onAdd || !self.layout) return;
            var now = Date.now();
            if (lastDown && now - lastDown.t < 400 && Math.abs(ev.clientX - lastDown.x) < 6 && Math.abs(ev.clientY - lastDown.y) < 6) {
                lastDown = null;
                var y = ev.clientY - self.svg.getBoundingClientRect().top - AXIS_H;
                var ln = self.layout.lanes.find(function (l) { return y >= l.y && y < l.y + LANE_H; });
                if (ln) { ev.stopPropagation(); ev.preventDefault(); self.opts.onAdd(ln, self.secAt(ev.clientX)); }
                return;
            }
            lastDown = { t: now, x: ev.clientX, y: ev.clientY };
        }, true);
    }

    Timeline.prototype.set = function (model, opts) {
        this.model = model;
        if (opts) for (var k in opts) if (opts.hasOwnProperty(k)) this.opts[k] = opts[k];
        this.render();
    };

    Timeline.prototype.fit = function () {
        this.pxPerSec = null;
        this.render();
    };

    Timeline.prototype.zoom = function (factor) {
        this.pxPerSec = Math.min(200, Math.max(0.01, (this.pxPerSec || this.fitScale()) * factor));
        this.render();
    };

    Timeline.prototype.fitScale = function () {
        var w = Math.max(200, this.scroll.clientWidth - PAD_R - 8);
        var span = this.layout ? this.layout.span : Math.max(2, (this.model && this.model.end) || 0);
        return Math.min(120, w / Math.max(1, span));
    };

    Timeline.prototype.x = function (sec) { return 8 + sec * this.scale; };

    Timeline.prototype.secAt = function (clientX) {
        var r = this.svg.getBoundingClientRect();
        return Math.max(0, Math.round((clientX - r.left - 8) / this.scale));
    };

    Timeline.prototype.render = function () {
        var m = this.model || { items: [], end: 0 }, o = this.opts, self = this;
        var L = layout(m);
        this.layout = L;
        this.scale = this.pxPerSec || this.fitScale();
        var span = L.span;
        var width = this.x(span) + PAD_R;
        var height = AXIS_H + L.height + 8;
        var svg = this.svg, clientWidth = this.scroll.clientWidth;
        while (svg.firstChild) svg.removeChild(svg.firstChild);
        svg.setAttribute('width', Math.max(width, clientWidth - 2));
        svg.setAttribute('height', height);
        // 높이가 바뀌어 세로 스크롤바가 생기거나 없어지면 쓸 수 있는 폭이 달라진다(스크롤바가 늘 보이는 설정).
        // 그 폭으로 한 번 더 그린다 — 처음 그릴 때 Fit 이 스크롤바 폭만큼 넘치거나 모자랐다
        if (this.scroll.clientWidth !== clientWidth && !this.refitting) {
            this.refitting = true;
            try { this.render(); } finally { this.refitting = false; }
            return;
        }
        this.empty.style.display = L.items.length ? 'none' : '';

        // 왼쪽 Lane 이름
        var lab = '';
        L.groups.forEach(function (g) {
            lab += '<div class="tpt-group" style="top:' + (AXIS_H + g.y) + 'px">' + esc(g.name) + '</div>';
        });
        L.lanes.forEach(function (ln) {
            lab += '<div class="tpt-lane-label" style="top:' + (AXIS_H + ln.y) + 'px">' + esc(ln.cell) + '</div>';
        });
        this.labels.innerHTML = lab;
        this.labels.style.height = height + 'px';

        // 눈금
        var step = TICK_STEPS[TICK_STEPS.length - 1];
        for (var i = 0; i < TICK_STEPS.length; i++) {
            if (TICK_STEPS[i] * this.scale >= 56) { step = TICK_STEPS[i]; break; }
        }
        var axis = el('g', { 'class': 'tpt-axis' }, svg);
        for (var t = 0; t <= span; t += step) {
            var xx = this.x(t);
            el('line', { x1: xx, x2: xx, y1: AXIS_H - 6, y2: height, 'class': 'tpt-grid' }, axis);
            var tx = el('text', { x: xx + 3, y: AXIS_H - 10, 'class': 'tpt-tick' }, axis);
            tx.textContent = fmtTime(t);
        }

        // Lane 배경
        var lanesG = el('g', {}, svg);
        L.groups.forEach(function (g) {
            el('rect', { x: 0, y: AXIS_H + g.y, width: width, height: GROUP_H, 'class': 'tpt-group-bg' }, lanesG);
        });
        L.lanes.forEach(function (ln, idx) {
            el('rect', { x: 0, y: AXIS_H + ln.y, width: width, height: LANE_H,
                'class': 'tpt-lane' + (idx % 2 ? ' odd' : '') }, lanesG);
        });

        // End 뒤: Plan 은 끝났지만 값과 lock 은 남는 구간
        var ex = this.x(L.end);
        el('rect', { x: ex, y: AXIS_H, width: Math.max(0, width - ex), height: L.height, 'class': 'tpt-after' }, svg);

        // 블록
        var conf = conflicts(m);
        L.blocks.forEach(function (b) { self.drawBlock(b, conf); });

        // 실패하면 멈추는 첫 Action. 여기서부터는 실패에 따라 실행되지 않을 수 있다
        var firstStop = L.items.filter(function (it) { return it.stop; })[0];
        if (firstStop) {
            var sx = this.x(firstStop.at);
            var stopG = el('g', { 'class': 'tpt-stop' }, svg);
            el('line', { x1: sx, x2: sx, y1: AXIS_H - 6, y2: height }, stopG);
            var st = el('text', { x: sx + 4, y: AXIS_H + 12 }, stopG);
            st.textContent = 'Stops here if #' + (firstStop.order + 1) + ' fails';
        }

        // End 표시
        var endG = el('g', { 'class': 'tpt-end' }, svg);
        el('line', { x1: ex, x2: ex, y1: 0, y2: height }, endG);
        // 눈금 숫자와 겹치지 않게 아래쪽에 단다. End = 마지막 lock 이 풀리는 시각
        var et = el('text', { x: ex + 4, y: height - 4 }, endG);
        et.textContent = 'End ' + fmtTime(L.end);

        // 실행 위치
        if (o.playhead !== null && o.playhead !== undefined) {
            var px = this.x(Math.min(o.playhead, span));
            el('line', { x1: px, x2: px, y1: AXIS_H - 6, y2: height, 'class': 'tpt-playhead' }, svg);
        }
    };

    Timeline.prototype.drawBlock = function (b, conf) {
        var o = this.opts, self = this, it = b.item, svg = this.svg;
        var color = colorOf(it), selected = o.selected === it.uid;
        var bad = (o.errors && o.errors[it.uid]) || conf[it.uid];
        var g = el('g', { 'class': 'tpt-block' + (selected ? ' selected' : '') + (bad ? ' bad' : '') }, svg);
        var lock = it.lockSeconds || 0;
        var top = Infinity, bottom = -Infinity;
        b.segs.forEach(function (s) {
            if (s.lane === undefined) return;
            var ln = self.layout.lanes[s.lane], y = AXIS_H + ln.y + 3, h = LANE_H - 6;
            top = Math.min(top, y); bottom = Math.max(bottom, y + h);
            var x0 = self.x(s.from), x1 = Math.max(self.x(s.until), x0 + 3);
            var lockEnd = Math.min(s.from + lock, s.until), xl = self.x(lockEnd);
            var hold = el('rect', { x: x0, y: y, width: x1 - x0, height: h, rx: 2, fill: color, 'class': 'tpt-hold' }, g);
            hold.addEventListener('click', function (ev) { ev.stopPropagation(); if (o.onSelect) o.onSelect(it.uid); });
            if (lock > 0 && xl > x0) el('rect', { x: x0, y: y, width: xl - x0, height: h, rx: 2, fill: color, 'class': 'tpt-lock' }, g);
            if (s.cutBy !== null) el('line', { x1: x1, x2: x1, y1: y - 2, y2: y + h + 2, 'class': 'tpt-cut' }, g);
            if (lock > 0 && s.from + lock > s.until) {
                // 다음 쓰기가 lock 을 앞당겨 끊었다
                el('line', { x1: x1 + 1, x2: self.x(s.from + lock), y1: y + h / 2, y2: y + h / 2, 'class': 'tpt-lock-cut' }, g);
            }
        });
        if (top === Infinity) return;
        var x0 = this.x(it.at);
        el('line', { x1: x0, x2: x0, y1: top - 1, y2: bottom + 1, stroke: color, 'class': 'tpt-start' }, g);
        var label = el('text', { x: x0 + 4, y: top + 14, 'class': 'tpt-label' }, g);
        label.textContent = '#' + (b.index + 1) + ' ' + fmtValue(it.value) + (it.stop ? ' ■' : '');
        var title = el('title', {}, g);
        title.textContent = '#' + (b.index + 1) + '  ' + (it.deviceName || '') + ' ' + it.target + ' = ' + it.value +
            (it.dataType ? ' (' + it.dataType + ')' : '') + '\nAt ' + fmtTime(it.at) + ', lock ' + lock + 's' +
            (it.stop ? ', stops the plan if it fails' : '') + (conf[it.uid] ? '\nSame time and cell as another action.' : '');
        // 잡는 영역과 lock 손잡이는 겹치지 않게 나눈다. lock 이 0 이면 손잡이가 시작점에 겹쳐 블록을 잡을 수 없었다
        var hx = Math.max(this.x(it.at + lock), x0 + 12);
        var hit = el('rect', { x: x0 - 3, y: top - 2, width: hx - x0, height: bottom - top + 4, 'class': 'tpt-hit' }, g);
        hit.addEventListener('click', function (ev) { ev.stopPropagation(); if (o.onSelect) o.onSelect(it.uid); });
        if (!o.editable) return;

        // 블록을 끌면 그 Action 의 실행 시각이 바뀐다 (1초 단위)
        this.dragX(hit, function (dx) {
            var v = Math.max(0, Math.round(it.at + dx / self.scale));
            g.setAttribute('transform', 'translate(' + ((v - it.at) * self.scale) + ',0)');
            return v;
        }, function (v) {
            if (v === null || v === it.at) { if (o.onSelect) o.onSelect(it.uid); return; }
            if (o.onMove) o.onMove(it.uid, v);
        });
        // lock 끝을 끌어 lockSeconds 를 바꾼다
        var handle = el('rect', { x: hx - 3, y: top, width: 8, height: bottom - top, 'class': 'tpt-lock-handle' }, g);
        var title2 = el('title', {}, handle);
        title2.textContent = 'Drag to change the lock (' + lock + ' s)';
        this.dragX(handle, function (dx) {
            var v = Math.max(0, Math.round(lock + dx / self.scale));
            handle.setAttribute('x', Math.max(self.x(it.at + v), x0 + 12) - 3);
            return v;
        }, function (v) { if (v !== null && v !== lock && o.onLock) o.onLock(it.uid, v); });
    };

    /** 가로로 끈다. move(dx) 가 돌려준 값을 끝날 때 done(value) 로 넘긴다. 움직이지 않았으면 done(null). */
    Timeline.prototype.dragX = function (target, move, done) {
        target.addEventListener('mousedown', function (ev) {
            if (ev.button !== 0) return;
            ev.preventDefault(); ev.stopPropagation();
            var x0 = ev.clientX, last = null, moved = false;
            function mm(e) {
                if (Math.abs(e.clientX - x0) > 2) moved = true;
                if (moved) last = move(e.clientX - x0);
            }
            function mu() {
                document.removeEventListener('mousemove', mm);
                document.removeEventListener('mouseup', mu);
                done(moved ? last : null);
            }
            document.addEventListener('mousemove', mm);
            document.addEventListener('mouseup', mu);
        });
    };

    function esc(s) {
        return String(s === null || s === undefined ? '' : s).replace(/[&<>"]/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
        });
    }

    global.TestPlanTimeline = {
        create: function (container, opts) { return new Timeline(container, opts); },
        fromActions: fromActions,
        toActions: toActions,
        place: place,
        conflicts: conflicts,
        fmtTime: fmtTime
    };
})(window);
