/* 공용 Tabulator 유틸.
 *
 * 열 폭 대원칙 (enhanceResize) :
 *   1) 열 폭의 합은 늘 표 폭과 같다. 한 열을 조정하면 나머지 열이 그 안에서 균등하게 받는다.
 *   2) 어떤 열도 머리글 글자가 잘리는 폭 아래로는 줄지 않는다. 사용자의 변경이 다른 열의
 *      머리글을 침범하면 그만큼 거부한다(클램프).
 *   3) 우선순위는 없다. 사용자가 방금 끈 열의 요청이 최우선이고, 나머지는 모두 같은 자격이다.
 *
 * 이 로직은 layout:'fitData' 를 전제로 한다. fitColumns 는 setWidth() 뒤에 자체 재배치를
 * 돌려 여기서 계산한 폭을 덮어쓰므로 쓸 수 없다(= 합이 깨지고 머리글이 잘리는 원인).
 *
 * 머리글 최소 폭은 '그려진 글자' 를 재서 구한다. 제목 길이로 어림잡으면 글꼴·굵기·
 * text-transform 에 따라 틀리고, 틀린 값이 Tabulator 의 minWidth 와 어긋나면
 * setWidth 가 거절되어 합이 깨진다.
 */
(function (global) {
    'use strict';

    /** Select All 체크박스 머리 + 각 행 체크박스. selectableRows: true 테이블에만 쓴다. */
    function selectionColumn(overrides) {
        return Object.assign({
            formatter: 'rowSelection',
            titleFormatter: 'rowSelection',
            hozAlign: 'center',
            headerHozAlign: 'center',
            headerSort: false,
            width: 50,
            resizable: false
        }, overrides || {});
    }

    /**
     * 머리글 글자가 처음부터 잘리지 않도록 초기 width 를 넉넉히 둔다.
     * 최소 폭은 enhanceResize 가 실제 글자를 재서 정하므로 여기서 minWidth 는 건드리지 않는다
     * (어림값을 minWidth 로 박으면 실측보다 넓어 공간을 낭비한다).
     */
    function withHeaderMinWidth(cols) {
        return cols.map(function (c) {
            if (!c || !c.title) return c;
            var needed = Math.max(80, c.title.length * 9 + 40);
            if (c.width !== undefined && c.width < needed) c.width = needed;
            return c;
        });
    }

    /**
     * 글자의 '잘리지 않은' 폭. 화면 밖 자를 같은 글꼴로 맞춰 재서 구한다.
     * 이미 잘린 글자(text-overflow: ellipsis)를 Range 로 재면 잘린 뒤 폭이 나와,
     * 좁아진 열이 그 폭을 자기 최소 폭으로 믿고 영영 잘린 채로 굳는다.
     */
    var gauge = null;
    function textWidth(el) {
        var text = el.textContent || '';
        if (!text.trim()) return 0;
        if (!gauge) {
            gauge = document.createElement('span');
            gauge.style.cssText = 'position:absolute;left:-9999px;top:-9999px;' +
                                  'white-space:pre;visibility:hidden;padding:0;border:0';
            document.body.appendChild(gauge);
        }
        var cs = getComputedStyle(el);
        gauge.style.fontFamily = cs.fontFamily;
        gauge.style.fontSize = cs.fontSize;
        gauge.style.fontWeight = cs.fontWeight;
        gauge.style.fontStyle = cs.fontStyle;
        gauge.style.letterSpacing = cs.letterSpacing;
        gauge.style.textTransform = cs.textTransform;
        gauge.textContent = text;
        return gauge.getBoundingClientRect().width;
    }

    /** 머리글 글자가 잘리지 않는 최소 폭. 작성자가 적은 minWidth 가 더 크면 그것을 지킨다. */
    function measureHeaderMin(col) {
        var floor = col.getDefinition().minWidth || 0;
        var el = col.getElement();
        var title = el && el.querySelector('.tabulator-col-title');
        var box = el && el.querySelector('.tabulator-col-content');
        if (!title || !box) return Math.max(floor, 40);

        var text = textWidth(title);
        var cs = getComputedStyle(box);
        var pad = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
        var sorter = el.querySelector('.tabulator-col-sorter');
        var arrow = sorter ? (sorter.offsetWidth || 0) : 0;

        // 글자가 없는 머리(체크박스만 있는 열 등)는 바닥값만 지킨다
        var need = text > 0 ? Math.ceil(text + pad + arrow) + 2 : 24;
        return Math.max(floor, need);
    }

    function enhanceResize(table) {
        var laying = false;
        var pending = false;
        // 열은 ColumnComponent 로 식별한다. field 는 비어 있거나(체크박스 열) 서로 겹칠 수 있다
        var want = new Map();    // col -> 사용자가 끈 폭
        var mins = new Map();    // col -> 머리글 최소 폭. layout() 한 번 동안만 쓴다
        var learned = new Map(); // col -> 실제로 잘려 본 적이 있어 올려 둔 최소 폭

        // 글자 재기는 강제 리플로를 일으키므로 레이아웃 한 패스에 열마다 한 번만 잰다.
        // 실측(measureHeaderMin)은 테마의 여백·화살표 자리를 몇 px 빗나갈 수 있어,
        // 정말 잘린 것을 보고 배운 값(learned)이 있으면 그쪽을 쓴다.
        function minOf(col) {
            if (!mins.has(col)) {
                mins.set(col, Math.max(measureHeaderMin(col), learned.get(col) || 0));
            }
            return mins.get(col);
        }

        /**
         * 실제로 `...` 로 잘린 머리글을 찾아 그 폭을 배운다. 브라우저가 알려 주는
         * scrollWidth/clientWidth 차이라 테마·글꼴에 상관없이 맞는다.
         * @return 새로 배운 열이 있으면 true
         */
        function learnTruncated() {
            var grew = false;
            table.getColumns().forEach(function (c) {
                // 고정 열은 폭을 바꾸지 않으므로 배워도 쓸 데가 없다 — 헛도는 패스를 막는다
                if (!c.isVisible() || isFixed(c)) return;
                var el = c.getElement();
                var t = el && el.querySelector('.tabulator-col-title');
                if (!t) return;
                var over = t.scrollWidth - t.clientWidth;
                if (over > 1) {
                    learned.set(c, Math.ceil(c.getWidth() + over) + 2);
                    grew = true;
                }
            });
            return grew;
        }

        function isFixed(col) { return col.getDefinition().resizable === false; }

        /** 쓸 수 있는 폭. clientWidth 는 세로 스크롤바를 뺀 값. 1px 은 subpixel 여유. */
        function avail() {
            var holder = table.element.querySelector('.tabulator-tableholder');
            return holder ? holder.clientWidth - 1 : 0;
        }

        /**
         * budget 을 cols 에 균등 분배한다. 균등 몫이 머리글 최소 폭을 밑도는 열은 최소 폭에
         * 고정하고 남은 열끼리 다시 나눈다. budget 이 음수면 모두 최소 폭이 된다(가로 스크롤).
         */
        function spread(cols, budget) {
            var out = new Map();
            var active = cols.slice();
            var left = budget;
            while (active.length) {
                var share = left / active.length;
                var pinned = active.filter(function (c) { return share < minOf(c); });
                if (!pinned.length) {
                    active.forEach(function (c) { out.set(c, share); });
                    break;
                }
                pinned.forEach(function (c) { out.set(c, minOf(c)); left -= minOf(c); });
                active = active.filter(function (c) { return pinned.indexOf(c) < 0; });
            }
            return out;
        }

        /** @param dragged 방금 사용자가 끈 열(없으면 null). 그 요청이 최우선이다. */
        function layout(dragged) {
            try { doLayout(dragged); }
            catch (e) { laying = false; console.warn('CtsTable: 열 폭 계산 실패', e); }
        }

        function doLayout(dragged, pass) {
            if (laying || !table) return;
            var width = avail();
            if (width <= 0) return;                    // 아직 안 보이는 표(모달 등)
            var cols = table.getColumns().filter(function (c) { return c.isVisible(); });
            if (!cols.length) return;
            mins.clear();                              // 글꼴·제목이 바뀌었을 수 있다

            var fixedTotal = 0;
            var flex = [];
            cols.forEach(function (c) {
                if (isFixed(c)) fixedTotal += c.getWidth();
                else flex.push(c);
            });
            if (!flex.length) return;

            if (dragged) want.set(dragged, dragged.getWidth());

            // 정의의 width 는 '지정된 폭' 으로 쓰지 않는다. Tabulator 가 스스로 계산한 폭을
            // 정의에 되써서, 그걸 작성자의 뜻으로 오인하면 열이 그 폭에 굳는다(머리글이
            // 잘린 폭에도 굳는다). 사용자가 직접 끈 열만 '지정된 폭' 이다 — 원칙 3.
            var sized = flex.filter(function (c) { return want.has(c); });
            var free = flex.filter(function (c) { return !want.has(c); });
            var plan = new Map();

            // 1) 지정된 폭. 단 자기 머리글은 지킨다
            sized.forEach(function (c) { plan.set(c, Math.max(minOf(c), want.get(c))); });

            var sizedTotal = sized.reduce(function (s, c) { return s + plan.get(c); }, 0);
            var freeMin = free.reduce(function (s, c) { return s + minOf(c); }, 0);
            var budget = width - fixedTotal - sizedTotal;

            // 2) 지정된 폭이 나머지 열의 머리글을 침범하면 그만큼 거부한다.
            //    큰 열부터 깎고, 방금 끈 열은 맨 뒤로 미뤄 사용자의 지금 요청을 가장 오래 지킨다
            if (budget < freeMin) {
                var shortage = freeMin - budget;
                var order = sized.slice().sort(function (a, b) {
                    if (a === dragged) return 1;
                    if (b === dragged) return -1;
                    return plan.get(b) - plan.get(a);
                });
                for (var i = 0; i < order.length && shortage > 0.5; i++) {
                    var c = order[i];
                    var give = Math.min(shortage, plan.get(c) - minOf(c));
                    if (give > 0) { plan.set(c, plan.get(c) - give); shortage -= give; }
                }
                sizedTotal = sized.reduce(function (s, c) { return s + plan.get(c); }, 0);
                budget = width - fixedTotal - sizedTotal;
            }

            // 3) 남은 폭은 지정되지 않은 열이 균등하게 받는다
            if (free.length) {
                spread(free, budget).forEach(function (w, c) { plan.set(c, w); });
            } else if (budget > 0.5) {
                // 유연 열이 모두 지정된 폭이다. 합을 표 폭에 맞추려면 남는 폭을 나눠 줘야 한다.
                // 방금 끈 열은 사용자가 고른 폭을 지킨다
                var take = sized.filter(function (c) { return c !== dragged; });
                if (!take.length) take = sized;
                var add = budget / take.length;
                take.forEach(function (c) { plan.set(c, plan.get(c) + add); });
            }

            quantize(plan, width - fixedTotal);

            laying = true;
            try {
                plan.forEach(function (w, c) {
                    if (c.getWidth() !== w) c.setWidth(w);
                });
            } finally {
                laying = false;
            }

            // 예측이 몇 px 빗나가 머리글이 잘렸으면, 잘린 만큼을 배우고 한 번 더 맞춘다.
            // learned 는 커지기만 하므로 되풀이는 곧 멈춘다(최대 두 번 더).
            if ((pass || 0) < 2 && learnTruncated()) doLayout(dragged, (pass || 0) + 1);
        }

        /**
         * 폭을 정수로 맞춘다. 소수 폭을 그대로 주면 열마다 브라우저의 반올림이 달라져 합이
         * 표 폭을 넘고 가로 스크롤이 생긴다. 내림한 뒤 남는 몇 px 은 가장 넓은 열에 몰아 준다.
         * target 을 넘는 경우(모든 열이 최소 폭인데도 모자랄 때)는 그대로 둔다 — 가로 스크롤.
         */
        function quantize(plan, target) {
            var cols = [];
            var sum = 0;
            plan.forEach(function (w, c) {
                var i = Math.floor(w);
                plan.set(c, i);
                cols.push(c);
                sum += i;
            });
            var slack = target - sum;
            if (slack > 0 && cols.length) {
                var widest = cols.reduce(function (a, b) { return plan.get(a) >= plan.get(b) ? a : b; });
                plan.set(widest, plan.get(widest) + slack);
            }
        }

        function schedule() {
            if (pending) return;
            pending = true;
            requestAnimationFrame(function () { pending = false; layout(); });
        }

        function wire() {
            // 이벤트부터 건다. 첫 계산이 실패해도 이후 기회(리사이즈·끌기)를 잃지 않는다
            table.on('columnResized', function (col) { if (!laying) layout(col); });
            // 창 크기·가로세로 배치 변화, 그리고 숨어 있던 표(모달)가 보이게 될 때
            if (global.ResizeObserver) {
                new ResizeObserver(function () { if (!laying) schedule(); }).observe(table.element);
            }
            // 행 수가 바뀌면 세로 스크롤바가 생기거나 없어져 쓸 수 있는 폭이 달라진다
            table.on('dataProcessed', schedule);
            table.on('pageLoaded', schedule);
            schedule();
        }

        // 표가 다 만들어진 뒤라야 getColumns() 와 머리글 DOM 을 쓸 수 있다
        if (table.initialized) wire();
        else table.on('tableBuilt', wire);
    }

    global.CtsTable = {
        selectionColumn: selectionColumn,
        withHeaderMinWidth: withHeaderMinWidth,
        enhanceResize: enhanceResize
    };
})(window);
