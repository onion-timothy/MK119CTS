/* 공용 Tabulator 유틸. 표마다 CtsTable.attach(table, { rowH }) 하나로 아래 규칙을 건다.
 *
 * 열 폭 :
 *   1) 열 폭의 합은 늘 표 폭과 같다. 한 열을 늘리면 나머지가 줄고, 줄이면 나머지가 는다.
 *   2) 어떤 열도 머리글 글자가 `...` 로 잘리는 폭 아래로는 줄지 않는다. 이를 어기는 조정은
 *      어기기 직전까지만 받는다 — **끄는 동안에도** 그렇다(놓은 뒤에 되돌리는 것이 아니다).
 *   3) 처음엔 모든 열이 같은 폭이다. 그 뒤로 한 열이 Δ 만큼 바뀌면 나머지 모든 열이 같은 px 씩
 *      나눠 받거나 내놓는다(늘릴 때도 줄일 때도). 어느 열도 우선하지 않고, 열 정의에 폭(width)을
 *      적지 않는다 — 폭을 고정하는 열(resizable:false)만 예외다.
 *   모든 열이 최소 폭인데도 모자라면(좁은 화면) 잘라 숨기지 않고 가로로 스크롤한다.
 *
 * 이 로직은 layout:'fitData' 를 전제로 한다. fitColumns 는 setWidth() 뒤에 자체 재배치를
 * 돌려 여기서 계산한 폭을 덮어쓰므로 쓸 수 없다.
 *
 * 행 높이 (쪽을 나누고 높이가 정해진 표) :
 *   행 영역 = k × h, 둘 다 정수다. 쪽당 행 수 k 와 행 높이 h 를 표 높이에서 구하고,
 *   나눠떨어지지 않는 몇 px 은 footer(쪽 넘김 줄)가 갖는다. 그래서 행이 중간에서 잘리거나
 *   한 행 미만의 여백이 남지 않는다. 비는 것은 마지막 쪽의 '행 단위' 빈자리뿐이다.
 *   이런 표는 renderVertical:'basic' 으로 만든다 — 한 쪽이 수십 행이라 가상 렌더가 필요 없고,
 *   가상 렌더는 행 높이가 바뀌면 그릴 행 수를 다시 세지 않아 아래가 빌 수 있다.
 *   셀은 한 줄이다. 여러 줄 내용은 첫 줄만 보이고 나머지는 툴팁으로 보인다.
 *
 * 정렬 : CtsTable.columns() 가 kind 로 정한다. 칸은 number → 오른쪽, text → 왼쪽,
 *   action(체크박스·토글·버튼·아이콘) → 가운데. 머리글은 왼쪽이고 action 열만 가운데다.
 *
 * 머리글 최소 폭은 '그려진 글자' 를 재서 구한다. 제목 길이로 어림잡으면 글꼴·굵기·
 * text-transform 에 따라 틀린다. 잰 값을 Tabulator 의 minWidth 로도 넣어, 끄는 동안
 * Tabulator 가 스스로 그 폭에서 멈추게 한다.
 */
(function (global) {
    'use strict';

    var ALIGN = { number: 'right', text: 'left', action: 'center' };

    /**
     * 열 정의의 kind 로 정렬을 정한다. 칸은 kind 대로, 머리글은 왼쪽이다. 단 기능 열(action)은
     * 내용(체크박스·토글·버튼)이 가운데라 머리글도 가운데다. kind 는 Tabulator 가 모르는 키라 지우고 넘긴다.
     */
    function columns(cols) {
        return cols.map(function (c) {
            if (!c || !c.kind) return c;
            var align = ALIGN[c.kind];
            if (!align) throw new Error('CtsTable: unknown column kind "' + c.kind + '"');
            var out = Object.assign({}, c, { hozAlign: align, headerHozAlign: c.kind === 'action' ? 'center' : 'left' });
            delete out.kind;
            return out;
        });
    }

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

    /**
     * 머리글 글자가 잘리지 않는 최소 폭 = `...` 가 생기기 직전의 폭.
     * 열 폭에서 '제목 글자가 들어가는 칸' 을 뺀 나머지(머리글 여백, 정렬 화살표 자리 — 테마는
     * 화살표 자리를 제목의 padding 으로 잡는다)가 글자 말고 늘 드는 폭이다. 여기에 글자 폭을 더한다.
     */
    function measureHeaderMin(col) {
        var el = col.getElement();
        var title = el && el.querySelector('.tabulator-col-title');
        if (!title || !el.offsetWidth) return 40;

        var text = textWidth(title);
        // 글자가 없는 머리(아이콘 열 등)는 resizable:false 로 고정 폭을 주는 것이 원칙이다
        if (text <= 0) return 24;
        var cs = getComputedStyle(title);
        var room = title.clientWidth - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0);
        return Math.ceil(el.offsetWidth - room + text) + 1;
    }

    /**
     * 끌기 동안 건 상한을 푼다. Tabulator 의 setMaxWidth(false) 는 'false < minWidth' 로 보고
     * 상한을 최소 폭으로 바꿔 버리므로(그 폭에 열이 묶인다), 그 검사만 비켜 간다.
     */
    function clearMaxWidth(col) {
        var c = col._column;
        var min = c.minWidth;
        c.minWidth = 0;
        try { c.setMaxWidth(false); } finally { c.minWidth = min; }
    }

    /**
     * 열 끌기가 끝나지 않는 문제를 막는다.
     *
     * Tabulator 는 끌기의 mousemove/mouseup 을 document.body 에 건다. 버튼을 창 밖에서 떼면
     * mouseup 이 페이지에 오지 않아 끌기가 끝나지 않는다 — 커서를 계속 따라가다가, 한참 뒤
     * 아무 데나 누르면 그 지점으로 폭이 정해진다.
     * 버튼이 떼어진 것은 움직임에서 알 수 있으므로(mousemove 의 buttons === 0), 그때 마지막
     * 위치에서 끌기를 대신 끝낸다. 종료 핸들러는 resizableColumnGuide 가 꺼져 있으면 이벤트
     * 내용을 쓰지 않으므로 맨 mouseup 으로 충분하다.
     *
     * 전역에 한 번만 건다 — 표마다 걸면 표를 다시 만들 때마다 핸들러가 쌓인다.
     */
    var guardInstalled = false;
    function installResizeDragGuard() {
        if (guardInstalled || typeof document === 'undefined') return;
        guardInstalled = true;
        var dragging = false;
        document.addEventListener('mousedown', function (e) {
            var t = e.target;
            dragging = !!(t && t.closest && t.closest('.tabulator-col-resize-handle'));
        }, true);
        document.addEventListener('mousemove', function (e) {
            if (!dragging || e.buttons !== 0) return;
            dragging = false;
            document.body.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
        }, true);
        document.addEventListener('mouseup', function () { dragging = false; }, true);
    }

    /**
     * 큰 화면 배율(css/cts-tabler.css 0절, js/cts-zoom.js)에서 Tabulator(6.3.1)가 두 단위를 섞는 세 곳을 메운다.
     * 배율이 1 이면 모두 원래대로 돈다. 전역에 한 번만 건다.
     *
     * 1) 표 본문 높이: adjustTableSize 가 머리·아래 줄 높이를 getBoundingClientRect(눈에 보이는 px)로 재서
     *    calc(100% − Npx)(CSS px)에 넣는다. 본문이 배율 몫만큼 짧아져 마지막 행이 잘렸다.
     *    같은 함수를 바꿔 끼운다 — 원본을 그대로 옮기고 두 높이만 CSS px 로 잰다.
     *    Tabulator 는 표를 setTimeout 뒤에 그리므로, 만든 직후 attach 에서 바꿔 끼우면 첫 그리기부터 맞다.
     * 2) 목록 편집기(editor:'list'): 목록을 칸의 눈에 보이는 위치 + offsetHeight(CSS px)에 놓고 폭을
     *    offsetWidth 로 잡아서, 칸 아래쪽을 덮고 칸보다 좁다. 목록이 body 에 붙는 순간 칸에 맞춰 다시 놓는다.
     * 3) 열 폭 끌기: 마우스가 움직인 거리(눈에 보이는 px)를 열 폭(CSS px)에 그대로 더해 열이 커서보다
     *    빨리 늘어난다. 끄는 동안의 mousemove 를 거리만 배율로 나눈 이벤트로 바꿔 건넨다.
     */
    var zoomFixed = false;
    function installZoomFixes(table) {
        if (zoomFixed || !window.CtsZoom || !table.rowManager) return;
        zoomFixed = true;

        var proto = Object.getPrototypeOf(table.rowManager), original = proto.adjustTableSize;
        // 옮겨 온 원본과 다른 판이면 바꿔 끼우지 않는다 (판을 올리면 이 함수를 다시 맞춰야 한다)
        if (typeof original === 'function' && /verticalFillMode[\s\S]*getBoundingClientRect/.test(String(original))) {
            proto.adjustTableSize = function () {
                if (CtsZoom.of(this.element) === 1) return original.apply(this, arguments);
                var before = this.element.clientHeight, resized = false;
                if (this.renderer.verticalFillMode === 'fill') {
                    var fm = this.table.footerManager;
                    var s = Math.floor(CtsZoom.rect(this.table.columnManager.getElement()).height
                            + (fm && fm.active && !fm.external ? CtsZoom.rect(fm.getElement()).height : 0));
                    if (this.fixedHeight) {
                        var minH = isNaN(this.table.options.minHeight) ? this.table.options.minHeight : this.table.options.minHeight + 'px';
                        var h = 'calc(100% - ' + s + 'px)';
                        this.element.style.minHeight = minH || h;
                        this.element.style.height = h;
                        this.element.style.maxHeight = h;
                    } else {
                        this.element.style.height = '';
                        this.element.style.height = this.table.element.clientHeight - s + 'px';
                        this.element.scrollTop = this.scrollTop;
                    }
                    this.renderer.resize();
                    if (!this.fixedHeight && before !== this.element.clientHeight) {
                        resized = true;
                        if (this.subscribed('table-resize')) this.dispatch('table-resize'); else this.redraw();
                    }
                    this.scrollBarCheck();
                }
                this._positionPlaceholder();
                return resized;
            };
        } else {
            console.warn('CtsTable: Tabulator 판이 달라 큰 화면 배율의 표 높이 보정을 건너뛴다');
        }

        new MutationObserver(function (records) {
            records.forEach(function (rec) {
                rec.addedNodes.forEach(function (node) {
                    if (node.nodeType === 1 && node.classList.contains('tabulator-edit-list')) placeList(node);
                });
            });
        }).observe(document.body, { childList: true });
        function placeList(list) {
            var cell = document.querySelector('.tabulator-cell.tabulator-editing');
            if (!cell || CtsZoom.of(cell) === 1) return;
            var r = cell.getBoundingClientRect(), lr = list.getBoundingClientRect();
            list.style.minWidth = r.width + 'px';
            // 화면 아래쪽 칸이라 위로 뒤집어 놓인 목록은 칸 위에 붙인다
            var top = lr.top < r.top ? r.top - lr.height : r.bottom;
            list.style.top = (top + window.pageYOffset) + 'px';
        }

        var drag = null;   // { x0: 누른 자리의 clientX, z: 열의 배율 }
        document.addEventListener('mousedown', function (e) {
            var h = e.target && e.target.closest && e.target.closest('.tabulator-col-resize-handle');
            drag = h && CtsZoom.of(h) !== 1 ? { x0: e.clientX, z: CtsZoom.of(h) } : null;
        }, true);
        document.addEventListener('mousemove', function (e) {
            if (!drag || !e.isTrusted) return;
            // 원래 이벤트는 Tabulator(body 의 mousemove)에 닿지 않게 막고, 거리를 배율로 나눈 것을 대신 보낸다
            e.stopPropagation();
            document.body.dispatchEvent(new MouseEvent('mousemove', {
                bubbles: true, cancelable: true, buttons: e.buttons,
                clientX: drag.x0 + (e.clientX - drag.x0) / drag.z, clientY: e.clientY
            }));
        }, true);
        document.addEventListener('mouseup', function () { drag = null; }, true);
    }

    /*
     * 행 맞춤에 꼭 필요한 규칙. 표 꾸밈(css/cts-table.css)을 쓰지 않는 화면(batch)에서도 같게 돌도록
     * 여기서 한 번 넣는다. 행 높이는 표마다 --cts-row-h 로 준다. 행 아래 구분선은 행 높이 안에 든다
     * (box-sizing: border-box). 셀은 한 줄이고 세로 가운데다. 쪽당 행 수가 높이에 딱 맞으므로 세로 스크롤은 없다.
     */
    var FIT_CSS =
        '.cts-fit-rows .tabulator-tableholder{overflow-y:hidden!important;overflow-x:auto!important}' +
        '.cts-fit-rows .tabulator-tableholder .tabulator-row{height:var(--cts-row-h)!important;min-height:0!important}' +
        // 칸은 행의 위에 붙인다(vertical-align top). 칸은 행 안의 인라인 블록이라 middle 이면 행의 글자 기준선에
        // 맞춰 몇 px 내려앉을 때가 있다(표를 다시 그린 직후 등) — 그러면 글자가 행 아래쪽에 붙는다. 칸 높이가 행과 같으니 top 이 곧 가운데다
        '.cts-fit-rows .tabulator-tableholder .tabulator-row .tabulator-cell{height:100%!important;vertical-align:top!important;' +
            'line-height:calc(var(--cts-row-h) - 1px);padding-top:0!important;padding-bottom:0!important;' +
            'white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
        // 칸 안의 요소(배지·버튼·체크박스)는 칸의 큰 줄 높이를 물려받지 않고 가운데에 선다
        '.cts-fit-rows .tabulator-tableholder .tabulator-cell>*{line-height:normal;vertical-align:middle}';
    var fitCssInstalled = false;
    function installFitCss() {
        if (fitCssInstalled) return;
        fitCssInstalled = true;
        var st = document.createElement('style');
        st.id = 'cts-table-fit-rows';
        st.textContent = FIT_CSS;
        document.head.appendChild(st);
    }

    /**
     * @param table Tabulator
     * @param opts  { rowH: 기준 행 높이(px) } — 쪽을 나누고 높이가 정해진 표에서만 쓴다.
     *              실제 행 높이는 표 높이를 나눠떨어지게 하려고 이보다 조금 커질 수 있다.
     */
    /*
     * 첫 열 배치 전에는 표 안 가로 스크롤을 끈다. Tabulator 는 처음에 열을 내용 폭대로(fitData) 그리고
     * 균등 분배는 다음 프레임에 하므로, 긴 내용이 있으면 한 프레임 동안 열 합이 표 폭을 넘는다.
     * macOS 의 오버레이 스크롤바는 그 한 프레임에도 나타나 1~2초 떠 있다가 사라진다.
     */
    var PENDING = 'cts-pending-layout';
    var pendingCssInstalled = false;
    function installPendingCss() {
        if (pendingCssInstalled) return;
        pendingCssInstalled = true;
        var st = document.createElement('style');
        st.id = 'cts-table-pending-layout';
        st.textContent = '.tabulator.' + PENDING + ' .tabulator-tableholder{overflow-x:hidden!important}';
        document.head.appendChild(st);
    }

    function attach(table, opts) {
        installPendingCss();
        if (table.element) table.element.classList.add(PENDING);
        installResizeDragGuard();
        installZoomFixes(table);
        opts = opts || {};
        var laying = false;
        var pending = false;
        // 열은 ColumnComponent 로 식별한다. field 는 비어 있거나(체크박스 열) 서로 겹칠 수 있다
        var mins = new Map();    // col -> 머리글 최소 폭. layout() 한 번(끄는 동안엔 끌기 한 번) 동안만 쓴다
        var learned = new Map(); // col -> 실제로 잘려 본 적이 있어 올려 둔 최소 폭
        var laidOut = false;     // 처음 한 번은 모든 열을 같은 폭으로 둔다
        var dragging = null;     // 지금 끄는 열
        var snapshot = null;     // 끌기를 시작할 때의 열 폭(col -> px). 끌다가 되돌리면 정확히 돌아온다

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

        function flexCols() {
            return table.getColumns().filter(function (c) { return c.isVisible() && !isFixed(c); });
        }

        function fixedWidth() {
            return table.getColumns().reduce(function (s, c) {
                return s + (c.isVisible() && isFixed(c) ? c.getWidth() : 0);
            }, 0);
        }

        /** 쓸 수 있는 폭. clientWidth 는 세로 스크롤바를 뺀 값. 1px 은 subpixel 여유. */
        function avail() {
            var holder = table.element.querySelector('.tabulator-tableholder');
            return holder ? holder.clientWidth - 1 : 0;
        }

        /** 잰 최소 폭을 Tabulator 에도 넣는다. 끄는 동안 Tabulator 가 스스로 그 폭에서 멈춘다. */
        function syncMin(col) {
            var m = Math.ceil(minOf(col));
            if (col._column.minWidth !== m) col._column.setMinWidth(m);
        }

        /**
         * budget 을 cols 에 균등 분배한다. 균등 몫이 머리글 최소 폭을 밑도는 열은 최소 폭에
         * 고정하고 남은 열끼리 다시 나눈다. budget 이 모자라면 모두 최소 폭이 된다(가로 스크롤).
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

        /**
         * cols 가 diff 만큼 같은 px 씩 받거나(diff > 0) 내놓는다(diff < 0). 내놓다가 최소 폭에 닿은 열은
         * 거기서 멈추고, 못 낸 몫은 아직 여유가 있는 열끼리 다시 같은 px 씩 나눈다.
         * @return 다 못 낸 양(0 이하). 모두 최소 폭이어서 더 낼 수 없을 때만 0 이 아니다
         */
        function share(cols, plan, diff) {
            if (diff > 0) {
                cols.forEach(function (c) { plan.set(c, plan.get(c) + diff / cols.length); });
                return 0;
            }
            var need = -diff;
            var active = cols.filter(function (c) { return plan.get(c) - minOf(c) > 0.01; });
            while (need > 0.01 && active.length) {
                var each = need / active.length;
                active = active.filter(function (c) {
                    var give = Math.min(each, plan.get(c) - minOf(c));
                    plan.set(c, plan.get(c) - give);
                    need -= give;
                    return plan.get(c) - minOf(c) > 0.01;
                });
            }
            return -need;
        }

        /** @param actor 폭을 바꾼 열(끈 열·더블클릭한 열). 없으면 표 폭이 바뀐 것이다 */
        function layout(actor) {
            try { doLayout(actor || null, 0); }
            catch (e) { laying = false; console.warn('CtsTable: 열 폭 계산 실패', e); }
        }

        /*
         * 처음 한 번은 모든 열을 같은 폭으로 둔다(머리글 최소 폭은 지킨다).
         * 그 뒤로는 한 열이 Δ 만큼 바뀌면 나머지 모든 열이 −Δ 를 같은 px 씩 나눠 받거나 내놓는다.
         * 표 폭이 바뀌면 모든 열이 그 차이를 같은 px 씩 나눈다. 어느 열도 우선하지 않는다.
         */
        function doLayout(actor, pass) {
            if (laying || !table) return;
            var width = avail();
            if (width <= 0) return;                    // 아직 안 보이는 표(모달 등)
            var flex = flexCols();
            // 폭을 나눌 열이 없으면 배치할 것도 없다 — 가로 스크롤을 바로 돌려준다
            if (!flex.length) { table.element.classList.remove(PENDING); return; }
            if (!dragging) mins.clear();               // 글꼴·제목이 바뀌었을 수 있다. 끄는 동안엔 한 번 잰 값을 쓴다

            var target = width - fixedWidth();
            var plan = new Map();
            if (!laidOut) {
                spread(flex, target).forEach(function (w, c) { plan.set(c, w); });
                laidOut = true;
            } else {
                // 끄는 동안엔 끌기 시작 때의 폭에서 센다 — 늘렸다가 되돌리면 원래 폭으로 돌아온다
                var base = (snapshot && actor === dragging) ? snapshot : null;
                var others = flex.filter(function (c) { return c !== actor; });
                if (actor) plan.set(actor, actor.getWidth());
                others.forEach(function (c) {
                    plan.set(c, Math.max(minOf(c), base && base.has(c) ? base.get(c) : c.getWidth()));
                });
                var sum = 0;
                plan.forEach(function (w) { sum += w; });
                var rest = share(others, plan, target - sum);
                // 나머지가 모두 최소 폭인데도 모자라면 바꾼 열이 그만큼 덜 바뀐다(위반 직전까지만 받는다).
                // 그래도 모자라면(좁은 화면) 가로 스크롤이다
                if (rest < 0 && actor) share([actor], plan, rest);
            }

            quantize(plan, target);

            laying = true;
            try {
                flex.forEach(syncMin);
                plan.forEach(function (w, c) {
                    if (c.getWidth() !== w) c.setWidth(w);
                });
            } finally {
                laying = false;
            }
            // 빈 표의 자리표시("No …")는 Tabulator 가 표 높이를 맞출 때만 열 폭 합으로 폭을 적는다.
            // 열 폭만 바뀐 뒤(가로↔세로 전환 등)에는 예전 폭이 남아 좁아진 표에 가로 스크롤이 생긴다 — 여기서 맞춘다
            var rm = table.rowManager;
            if (rm && typeof rm._positionPlaceholder === 'function') rm._positionPlaceholder();

            // 예측이 몇 px 빗나가 머리글이 잘렸으면, 잘린 만큼을 배우고 한 번 더 맞춘다.
            // learned 는 커지기만 하므로 되풀이는 곧 멈춘다(최대 두 번 더). 끄는 동안엔 놓은 뒤에 한다
            if (!dragging && pass < 2 && learnTruncated()) doLayout(actor, pass + 1);

            // 첫 배치가 끝났다 — 이제부터의 가로 스크롤은 진짜다(최소 폭 합 > 표 폭). 생겼으면 행 수를 다시 센다
            if (pass === 0 && table.element.classList.contains(PENDING)) {
                table.element.classList.remove(PENDING);
                var holder = table.element.querySelector('.tabulator-tableholder');
                if (fitsRows && holder && holder.scrollWidth > holder.clientWidth) schedule();
            }
        }

        /**
         * 폭을 정수로 맞춘다. 소수 폭을 그대로 주면 열마다 브라우저의 반올림이 달라져 합이
         * 표 폭을 넘고 가로 스크롤이 생긴다. 내림한 뒤 남는 몇 px(열 수보다 적다)은 소수 부분이 큰
         * 열부터 1px 씩 준다(최대 잔여법). 소수 부분이 같으면 앞 열부터다.
         * target 을 넘는 경우(모든 열이 최소 폭인데도 모자랄 때)는 그대로 둔다.
         */
        function quantize(plan, target) {
            var sum = 0;
            var frac = [];
            plan.forEach(function (w, c) {
                var i = Math.floor(w + 1e-6);
                frac.push({ c: c, f: w - i });
                plan.set(c, i);
                sum += i;
            });
            var slack = Math.round(target - sum);
            frac.sort(function (a, b) { return b.f - a.f; });
            for (var k = 0; slack > 0 && k < frac.length; k++, slack--) {
                plan.set(frac[k].c, plan.get(frac[k].c) + 1);
            }
        }

        /**
         * 끌기 시작: 끄는 열이 늘 수 있는 상한을 Tabulator 에 건다. 상한 = 지금 폭 + 나머지 열이
         * 머리글을 지키며 내놓을 수 있는 폭 + 표의 빈 폭. 그 위로는 Tabulator 가 끌어도 늘리지 않는다.
         * 하한은 syncMin 이 이미 걸어 두었다. 끄는 동안 나머지 열은 columnWidth 마다 끌기 시작 때의
         * 폭(snapshot)에서 다시 나눈다.
         */
        function startDrag(col) {
            if (isFixed(col)) return;
            mins.clear();
            dragging = col;
            snapshot = new Map();
            var flex = flexCols();
            var used = fixedWidth();
            var room = 0;
            flex.forEach(function (c) {
                snapshot.set(c, c.getWidth());
                used += c.getWidth();
                if (c !== col) room += Math.max(0, c.getWidth() - minOf(c));
            });
            room += Math.max(0, avail() - used);
            flex.forEach(syncMin);
            col._column.setMaxWidth(Math.max(Math.ceil(minOf(col)), Math.floor(col.getWidth() + room)));

            var end = function () {
                document.removeEventListener('mouseup', end, true);
                document.removeEventListener('touchend', end, true);
                if (dragging !== col) return;
                dragging = null;
                snapshot = null;
                clearMaxWidth(col);
            };
            document.addEventListener('mouseup', end, true);
            document.addEventListener('touchend', end, true);
        }

        // ── 행 높이 ────────────────────────────────────────────────
        var rowBase = opts.rowH > 0 ? opts.rowH : 0;
        var fitsRows = rowBase > 0 && !!table.options.pagination && !!table.options.height;
        if (rowBase > 0 && !fitsRows) {
            console.warn('CtsTable: rowH 는 쪽을 나누고(pagination) 높이(height)가 정해진 표에만 쓴다', table.element);
        }
        if (fitsRows && table.options.renderVertical !== 'basic') {
            console.warn('CtsTable: 행을 맞추는 표는 renderVertical:\'basic\' 으로 만든다', table.element);
        }
        if (fitsRows) installFitCss();
        var footerExtra = 0;     // footer 가 맡은 나머지 px
        var rowsK = 0;

        /**
         * 행 영역 = k × h 로 맞춘다.
         *   A = 표 높이 − 머리글 − footer − 가로 스크롤바, k = ⌊A / 기준⌋, h = ⌊A / k⌋.
         *   나머지 A − k·h 는 footer 여백이 갖는다. 그러면 Tabulator 가 tableholder 를
         *   calc(100% − 머리글 − footer) 로 잡을 때 행 영역이 정확히 k·h 가 된다.
         * 열 배치 뒤에 불러야 한다 — 가로 스크롤바가 생길지는 열 폭이 정한다.
         */
        function fitRows() {
            var el = table.element;
            var holder = el.querySelector('.tabulator-tableholder');
            var header = el.querySelector('.tabulator-header');
            var footer = el.querySelector('.tabulator-footer');
            if (!holder || !footer) return;
            // 높이는 모두 CSS px 로 잰다 — 큰 화면 배율에서 getBoundingClientRect 는 눈에 보이는 px 다 (js/cts-zoom.js)
            var elH = Math.floor(CtsZoom.rect(el).height);
            if (elH <= 0) return;                      // 아직 안 보이는 표(모달 등)

            var hbar = holder.offsetHeight - holder.clientHeight;
            var natural = (header ? CtsZoom.rect(header).height : 0) +
                          CtsZoom.rect(footer).height - footerExtra;
            var chrome = Math.ceil(natural - 0.01);
            var A = elH - chrome - hbar;
            if (A <= 0) return;
            var k = Math.max(1, Math.floor(A / rowBase));
            var h = Math.floor(A / k);
            // 머리글+footer 를 정수로 만든다 — Tabulator 가 그 합을 내림해서 tableholder 를 잡는다.
            // 브라우저의 높이는 1/64px 단위라 정확히 정수를 노리면 106.98 처럼 모자라 1px 이 내려간다.
            // 그래서 0.05px 을 얹는다(내림하면 노린 정수가 된다)
            var extra = (A - k * h) + (chrome - natural) + 0.05;

            var resize = false;
            if (Math.abs(extra - footerExtra) > 0.01) {
                footerExtra = extra;
                footer.style.paddingTop = (extra / 2) + 'px';
                footer.style.paddingBottom = (extra / 2) + 'px';
                resize = true;
            }
            if (el.style.getPropertyValue('--cts-row-h') !== h + 'px') {
                el.style.setProperty('--cts-row-h', h + 'px');
            }
            el.classList.add('cts-fit-rows');
            if (resize || Math.abs(holder.clientHeight - k * h) > 1) table.rowManager.adjustTableSize();

            rowsK = k;
            if (table.getPageSize() !== k) setPageSize(k);
        }

        /** 쪽당 행 수를 바꾸되 보던 첫 행이 든 쪽에 머문다(Tabulator 의 setPageSize 는 1쪽으로 간다). */
        function setPageSize(k) {
            var size = table.getPageSize();
            var page = table.getPage();
            var first = (typeof size === 'number' && size > 0 && page) ? (page - 1) * size : 0;
            // Tabulator 는 표를 다시 지을 때(table-built) 쪽 크기를 이 옵션값으로 되돌린다
            table.options.paginationSize = k;
            Promise.resolve(table.setPageSize(k)).then(function () {
                var p = Math.floor(first / k) + 1;
                if (p > 1 && p <= (table.getPageMax() || 1)) return table.setPage(p);
            }).catch(function () {});
        }

        /** 열 폭 합이나 행 영역이 어긋났는가. 렌더가 끝날 때마다 보는 값이라 싸게 센다. */
        function broken() {
            var w = avail();
            if (w > 0) {
                var sum = 0;
                table.getColumns().forEach(function (c) { if (c.isVisible()) sum += c.getWidth() || 0; });
                if (Math.abs(sum - (w + 1)) > 2) return true;
            }
            if (fitsRows) {
                var holder = table.element.querySelector('.tabulator-tableholder');
                if (!holder || !holder.clientHeight) return false;
                if (table.getPageSize() !== rowsK) return true;
                var h = parseFloat(table.element.style.getPropertyValue('--cts-row-h')) || 0;
                if (Math.abs(holder.clientHeight - rowsK * h) > 1) return true;
            }
            return false;
        }

        function run() {
            // 숨은 채로 만들어진 표는 Tabulator 가 머리글 높이를 0px 로 박아 두고, 보이게 되어도 다시 재지
            // 않을 때가 있다(그 표의 autoResize 가 시작되지 않는다 — testplans 의 실행 이력). 그러면 머리글이
            // 빈 띠로 보이고 행 높이 계산도 틀린다. 보이는데 0px 이면 한 번 다시 그려 재게 한다
            var headers = table.element.querySelector('.tabulator-headers');
            if (headers && table.element.offsetHeight > 0 && headers.style.height === '0px') table.redraw();
            layout(null);
            if (fitsRows) {
                try { fitRows(); } catch (e) { console.warn('CtsTable: 행 높이 계산 실패', e); }
            }
        }

        function schedule() {
            if (pending) return;
            pending = true;
            requestAnimationFrame(function () { pending = false; run(); });
        }

        function wire() {
            // 이벤트부터 건다. 첫 계산이 실패해도 이후 기회(리사이즈·끌기)를 잃지 않는다
            table.on('columnResizing', startDrag);
            // 끄는 동안: 끄는 열의 폭이 바뀔 때마다 나머지 열을 그 자리에서 맞춘다
            table.on('columnWidth', function (col) { if (!laying && dragging && col === dragging) layout(col); });
            table.on('columnResized', function (col) {
                if (laying) return;
                layout(col);
                if (fitsRows) schedule();             // 가로 스크롤바가 생기거나 없어졌을 수 있다
            });
            registry.push(table);
            // 창 크기·가로세로 배치 변화, 숨어 있던 표(모달)가 보이게 될 때, footer 가 접혀 높이가 바뀔 때
            if (global.ResizeObserver) {
                var ro = new ResizeObserver(function () { if (!laying) schedule(); });
                ro.observe(table.element);
                var footer = table.element.querySelector('.tabulator-footer');
                if (footer) ro.observe(footer);
            }
            // 행 수가 바뀌면 쓸 수 있는 폭이 달라질 수 있다
            table.on('dataProcessed', schedule);
            table.on('pageLoaded', schedule);
            /*
             * 숨은 채로 만들어진 표, 다시 지어진 표, 데이터를 늦게 받은 표는 Tabulator 가 나중에
             * 열 폭이나 쪽 크기를 되돌려 놓는다. 순서가 매번 달라 시점으로는 가드할 수 없다.
             * 그러니 **결과를 검사한다.** 어긋났으면 다시 잡고, 멀쩡하면 아무 일도 하지 않는다.
             */
            table.on('renderComplete', function () { if (!laying && broken()) schedule(); });
            schedule();
        }

        // 표가 다 만들어진 뒤라야 getColumns() 와 머리글 DOM 을 쓸 수 있다
        if (table.initialized) wire();
        else table.on('tableBuilt', wire);
    }

    /*
     * ── 사이드바 접는 동안만 표 재계산을 멈춘다 ──────────────────────────────
     *
     * Tabulator 의 autoResize 는 표 크기가 바뀔 때마다 다시 그린다. 창을 끌어 늘릴 때는
     * 괜찮은데, 사이드바 접기는 **CSS 전환**이라 크기가 프레임마다 바뀐다. 한 번 접고 펴는 데
     * 35~40번 다시 그리느라 600ms 넘게 들었고(행 수와 거의 무관한 고정비다) 세로 화면에서
     * 모션이 눈에 띄게 끊겼다.
     *
     * 그렇다고 autoResize 를 상시로 끄면 안 된다. 숨은 채로 만들어지는 표(XGT 서버데이터의
     * 접힌 블록)가 그 재계산에 기대고 있어서, 끄면 머리글이 안 나온다.
     *
     * 그래서 전환이 도는 동안에만 관찰자를 떼었다가, 끝나면 도로 붙이고 한 번 다시 그린다.
     * 전환 밖에서는 예전과 완전히 같다.
     */
    var registry = [];

    function eachResizeModule(fn) {
        registry.forEach(function (t) {
            var rt = t && t.modules && t.modules.resizeTable;
            if (rt && rt.resizeObserver) fn(t, rt);
        });
    }

    function pauseResize(paused) {
        eachResizeModule(function (t, rt) {
            if (paused) {
                rt.resizeObserver.disconnect();
                if (rt.containerObserver) rt.containerObserver.disconnect();
                return;
            }
            try { rt.resizeObserver.observe(t.element); } catch (e) { /* 떨어진 표는 건너뛴다 */ }
            if (rt.containerObserver && t.element.parentNode) {
                try { rt.containerObserver.observe(t.element.parentNode); } catch (e) { /* 같음 */ }
            }
            if (typeof rt.redrawTable === 'function') rt.redrawTable();
        });
    }

    function watchSidebar() {
        var menu = document.getElementById('sidebar-menu');
        if (!menu) return;
        var resume = 0;
        function hold() { clearTimeout(resume); pauseResize(true); }
        /*
         * 전환이 끝나는 **그 프레임**에 다시 그리면 마지막 몇 프레임이 같이 밀린다.
         * 한 박자 늦춰, 그릴 때는 화면이 이미 멎어 있게 한다.
         */
        function release() {
            clearTimeout(resume);
            resume = setTimeout(function () { pauseResize(false); }, 80);
        }
        menu.addEventListener('show.bs.collapse', hold);
        menu.addEventListener('hide.bs.collapse', hold);
        menu.addEventListener('shown.bs.collapse', release);
        menu.addEventListener('hidden.bs.collapse', release);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', watchSidebar);
    } else {
        watchSidebar();
    }

    global.CtsTable = {
        columns: columns,
        selectionColumn: selectionColumn,
        attach: attach
    };
})(window);
