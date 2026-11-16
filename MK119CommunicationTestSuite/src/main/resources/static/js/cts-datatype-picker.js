/**
 * data type 을 코드 한 줄이 아니라 뜻으로 고르게 하는 입력 보조.
 *
 * UI32CB 같은 코드는 네 가지가 한 덩어리로 붙어 있다 — 정수/실수, 부호, 비트 폭, 바이트 순서.
 * 그걸 그대로 목록에 늘어놓으면 모드버스 레지스터에서만 24줄이 된다. 여기서는 네 개의
 * 선택칸으로 나눠 묻고, 고른 것을 합쳐 원래 코드를 만든다.
 *
 * 선택칸은 **고를 게 둘 이상일 때만** 나온다. 프로토콜이 한 가지만 허용하면
 * (예: SNMP 는 INTEGER/FLOAT/BINARY 뿐이라 폭도 순서도 없다) 그 칸은 아예 그리지 않는다.
 *
 *     const p = CtsDataTypePicker.mount(el, { onChange: code => ... });
 *     p.setTypes(['UI16AB','UI16BA',...], 'UI16AB');   // 고를 수 있는 코드와 지금 값
 *     p.value();                                        // 'UI16AB'
 *
 * 값을 들고 있는 것은 부르는 쪽이다. 이 모듈은 화면과 조합만 맡는다.
 */
(function (global) {
    /**
     * 바이트 순서 이름. 코드는 양 끝 글자만 따서 CB·BC 처럼 읽기 어려우므로
     * 통용되는 이름과 실제 바이트 차례를 같이 적는다.
     * 차례는 enums/DataType.java 의 주석과 같다.
     */
    const ORDERS = {
        '16': [
            ['AB', 'Big-endian', 'A B'],
            ['BA', 'Little-endian', 'B A'],
        ],
        '32': [
            ['AD', 'Big-endian', 'A B C D'],
            ['CB', 'Word-swapped', 'C D A B'],
            ['BC', 'Byte-swapped', 'B A D C'],
            ['DA', 'Little-endian', 'D C B A'],
        ],
        '64': [
            ['AH', 'Big-endian', 'A B C D E F G H'],
            ['GB', 'Word-swapped', 'G H E F C D A B'],
            ['BG', 'Byte-swapped', 'B A D C F E H G'],
            ['HA', 'Little-endian', 'H G F E D C B A'],
        ],
    };

    function orderLabel(bits, order) {
        const row = (ORDERS[bits] || []).filter(o => o[0] === order)[0];
        return row ? row[1] + ' (' + row[2] + ')' : order;
    }

    /** 코드를 조각으로. 폭도 순서도 없는 BINARY·INTEGER·FLOAT 는 kind 하나로 둔다. */
    function parse(code) {
        const m = /^(UI|SI|FP)(8|16|32|64)([A-H]{2})?$/.exec(code);
        if (!m) return { kind: code, sign: '', bits: '', order: '' };
        return {
            kind: m[1] === 'FP' ? 'FP' : 'I',
            sign: m[1] === 'UI' ? 'U' : (m[1] === 'SI' ? 'S' : ''),
            bits: m[2],
            order: m[3] || '',
        };
    }

    /** 조각을 다시 코드로. */
    function build(p) {
        if (p.kind !== 'I' && p.kind !== 'FP') return p.kind;
        return (p.kind === 'FP' ? 'FP' : (p.sign === 'U' ? 'UI' : 'SI')) + p.bits + (p.order || '');
    }

    const KIND_LABEL = { I: 'Integer', FP: 'Float', BINARY: 'Binary', INTEGER: 'Integer', FLOAT: 'Float' };
    const SIGN_LABEL = { S: 'Signed', U: 'Unsigned' };

    function uniq(list) {
        const seen = {}, out = [];
        list.forEach(v => { if (!seen[v]) { seen[v] = 1; out.push(v); } });
        return out;
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, m =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[m]));
    }

    class Picker {
        constructor(el, opts) {
            this.el = el;
            this.opts = opts || {};
            this.types = [];
            this.parts = { kind: '', sign: '', bits: '', order: '' };
            this.el.addEventListener('change', e => {
                const key = e.target.dataset && e.target.dataset.part;
                if (!key) return;
                this.parts[key] = e.target.value;
                // 앞 칸을 바꾸면 뒤 칸의 선택지가 달라진다. 남은 것 중에서 다시 고른다
                this.settle();
                this.render();
                this.fire();
            });
        }

        /** 고를 수 있는 코드 목록과 지금 값. 값이 목록에 없으면 첫 코드로 맞춘다. */
        setTypes(types, current) {
            this.types = Array.isArray(types) ? types.slice() : [];
            const pick = this.types.indexOf(current) >= 0 ? current : this.types[0];
            this.parts = parse(pick || '');
            this.settle();
            this.render();
        }

        value() {
            const code = build(this.parts);
            return this.types.indexOf(code) >= 0 ? code : (this.types[0] || '');
        }

        /** 지금 고른 앞 칸들과 맞는 코드만 남긴다. keys 까지만 비교한다. */
        matching(keys) {
            return this.types.filter(t => {
                const p = parse(t);
                return keys.every(k => this.parts[k] === '' || p[k] === this.parts[k]);
            });
        }

        /** 앞 칸을 바꾼 뒤 뒤 칸이 못 쓰는 값으로 남아 있으면 쓸 수 있는 첫 값으로 내린다. */
        settle() {
            ['kind', 'sign', 'bits', 'order'].forEach((key, i) => {
                const before = ['kind', 'sign', 'bits', 'order'].slice(0, i);
                const list = uniq(this.matching(before).map(t => parse(t)[key]));
                if (list.indexOf(this.parts[key]) < 0) this.parts[key] = list[0] === undefined ? '' : list[0];
            });
        }

        /** 한 칸의 선택지. 둘 이상일 때만 그린다. */
        choices(key) {
            const before = ['kind', 'sign', 'bits', 'order'];
            return uniq(this.matching(before.slice(0, before.indexOf(key))).map(t => parse(t)[key]))
                .filter(v => v !== '');
        }

        /**
         * 한 칸. 고를 게 둘 이상이면 select, 하나뿐이면 정해진 값을 글로 보여 준다.
         * 아예 없으면(그 프로토콜에 그런 개념이 없으면) 그리지 않는다.
         *
         * 하나뿐일 때 숨겨 버리면 "왜 설정이 없지?" 가 된다. 예를 들어 XGT 는 폭이 target 에
         * 들어 있어서(%M**W**10 의 W) 여기서 고를 수 없는데, 칸이 사라지면 그 사정이 안 보인다.
         */
        fieldHtml(key, label, values, text, wide) {
            if (values.length === 0) return '';
            const col = wide ? 'col-12' : 'col';
            // 하나뿐이면 못 고르는 select 로 둔다. 글로 적으면 옆 칸과 높이·줄이 어긋난다.
            // 값은 숨은 select 가 들고 있으므로 disabled 라서 빠질 걱정은 없다
            const fixed = values.length === 1;
            const opts = values.map(v =>
                '<option value="' + esc(v) + '"' + (v === this.parts[key] ? ' selected' : '') + '>'
                + esc(text(v)) + '</option>').join('');
            return '<div class="' + col + '">'
                + '<div class="cts-dtp-label">' + esc(label) + '</div>'
                + '<select class="form-select" data-part="' + key + '"' + (fixed ? ' disabled' : '') + '>'
                + opts + '</select>'
                + '</div>';
        }

        render() {
            if (!this.el) return;
            const bits = this.parts.bits;
            let html = '<div class="row g-2 cts-dtp">';
            html += this.fieldHtml('kind', 'Type', this.choices('kind'), v => KIND_LABEL[v] || v);
            html += this.fieldHtml('sign', 'Sign', this.choices('sign'), v => SIGN_LABEL[v] || v);
            html += this.fieldHtml('bits', 'Bit length', this.choices('bits'), v => v + '-bit');
            // 바이트 순서는 글이 길어서 한 줄을 다 쓴다
            html += this.fieldHtml('order', 'Byte order', this.choices('order'),
                v => orderLabel(bits, v), true);
            html += '</div>';
            this.el.innerHTML = html;
        }

        fire() {
            if (typeof this.opts.onChange === 'function') this.opts.onChange(this.value());
        }
    }

    global.CtsDataTypePicker = {
        mount: (el, opts) => new Picker(el, opts),
        orderLabel: orderLabel,
        parse: parse,
        build: build,
    };
})(window);
