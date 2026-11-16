/**
 * 프로토콜 정의 문서(cts-protocol)의 모델. DOM 을 쓰지 않는다 — 편집기와 JXA 시험이 같이 쓴다.
 * 형식은 docs/PROTOCOL_DEFINITION_FORMAT.md 를 따른다.
 *
 *     CtsProtocolModel.newDoc()                 빈 문서
 *     CtsProtocolModel.validate(doc)            → [{ level: 'error'|'warn', message, at: [경로] }]
 *     CtsProtocolModel.kindOf(field, doc)       numeric / BCD / TEXT / … / structure / inline / unknown
 *     CtsProtocolModel.lengthText(field, doc)   표의 Length 칸 한 줄
 *     CtsProtocolModel.valueParts(field)        표의 Value 칸 조각 [{ k, t }]
 *     CtsProtocolModel.sequenceLayout(group)    순차 타겟 항목별 번지·칸 수
 *
 * CTS data type 코드(UI16AB …)는 쓰지 않는다 — 필드 숫자형은 UINT·INT·FLOAT + size·byteOrder (형식 6.1),
 * 파라미터는 value: integer·float (형식 11).
 */
(function (global) {
    'use strict';

    const SPECIAL_TYPES = ['BCD', 'TEXT', 'TEXTNUM', 'HEXNUM', 'BYTES', 'PAD', 'BITS'];
    const ENCODINGS = ['ascii', 'utf-8', 'utf-16be', 'utf-16le', 'euc-kr'];
    const OVERFLOW = ['error', 'clamp', 'wrap'];
    /** 이름·패턴 타겟이 선언하는 값 (형식 8.3). 표현은 메시지 필드가 갖는다 */
    const VALUES = ['binary', 'integer', 'float'];
    const NAMED_KEYS = ['target', 'value', 'access', 'doc'];
    const PATTERN_KEYS = ['kind', 'pattern', 'value', 'access', 'doc'];

    /**
     * 표현이 나르는 값의 종류 — 엔진(Definition.valueOf)과 같은 규칙.
     * 1비트 → binary, 여러 비트 → integer, 정수형·scale 없음 → integer, 그 밖의 수 → float, 글자·바이트·구조체 → null
     */
    function valueOfField(f, doc) {
        if (!f) return null;
        if (has(f, 'bits') && !f.type) return f.bits > 1 ? 'integer' : 'binary';
        const k = kindOf(f, doc);
        const scaled = has(f, 'scale') || has(f, 'offset');
        if (k === 'numeric') return f.type === 'FLOAT' || scaled ? 'float' : 'integer';
        if (k === 'BCD' || k === 'HEXNUM') return scaled ? 'float' : 'integer';
        if (k === 'TEXTNUM') return scaled || (typeof f.decimals === 'number' && f.decimals > 0) ? 'float' : 'integer';
        return null;
    }

    /** CRC 카탈로그. check 는 "123456789" 의 결과 — 설계 문서 8.2 와 같다 */
    const CRC_CATALOG = {
        'CRC-8/SMBUS':     { width: 8,  poly: '0x07',       init: '0x00',       refin: false, refout: false, xorout: '0x00',       check: '0xF4' },
        'CRC-16/ARC':      { width: 16, poly: '0x8005',     init: '0x0000',     refin: true,  refout: true,  xorout: '0x0000',     check: '0xBB3D' },
        'CRC-16/MODBUS':   { width: 16, poly: '0x8005',     init: '0xFFFF',     refin: true,  refout: true,  xorout: '0x0000',     check: '0x4B37' },
        'CRC-16/XMODEM':   { width: 16, poly: '0x1021',     init: '0x0000',     refin: false, refout: false, xorout: '0x0000',     check: '0x31C3' },
        'CRC-16/IBM-3740': { width: 16, poly: '0x1021',     init: '0xFFFF',     refin: false, refout: false, xorout: '0x0000',     check: '0x29B1' },
        'CRC-16/KERMIT':   { width: 16, poly: '0x1021',     init: '0x0000',     refin: true,  refout: true,  xorout: '0x0000',     check: '0x2189' },
        'CRC-32/ISO-HDLC': { width: 32, poly: '0x04C11DB7', init: '0xFFFFFFFF', refin: true,  refout: true,  xorout: '0xFFFFFFFF', check: '0xCBF43926' },
    };
    const OTHER_ALGORITHMS = ['CRC', 'SUM', 'XOR8', 'LRC8', 'EXPR'];
    const ALGORITHMS = Object.keys(CRC_CATALOG).concat(OTHER_ALGORITHMS);

    /** 필드 모양별로 쓰는 속성 (공통 속성 밖). 편집기 속성 패널과 타입 바꿀 때 정리에 쓴다 */
    const KIND_ATTRS = {
        numeric: ['size', 'byteOrder', 'scale', 'offset', 'onOverflow'],
        BCD:     ['size', 'byteOrder', 'scale', 'offset', 'onOverflow'],
        TEXT:    ['size', 'terminator', 'encoding', 'pad', 'align'],
        TEXTNUM: ['size', 'terminator', 'encoding', 'decimals', 'point', 'sign', 'pad', 'align', 'scale', 'offset', 'onOverflow'],
        HEXNUM:  ['size', 'case', 'scale', 'offset', 'onOverflow'],
        BYTES:   ['size'],
        PAD:     ['size', 'fill'],
        BITS:    ['size', 'bitOrder', 'fields'],
        structure: ['size', 'transform'],
    };
    const COMMON_ATTRS = ['name', 'type', 'structure', 'count', 'when', 'const', 'expect', 'value', 'target', 'checksum', 'doc'];
    const TRANSFORM_OPS = ['ascii-hex', 'add', 'xor'];

    /** 식이 들어가는 속성. $이름 참조를 이 자리들에서 찾는다 */
    const EXPR_ATTRS = ['match', 'when', 'expect', 'value', 'size', 'count', 'bodySize', 'address', 'bytes'];

    // 서버(Compiler.IDENT)와 같은 기준: 유니코드 글자나 _ 로 시작하고 글자·숫자·_ 만
    const IDENT = /^[\p{L}_][\p{L}\p{N}_]*$/u;
    const DOLLAR = /\$([\p{L}_][\p{L}\p{N}_]*)/gu;
    /** 필드 이름으로 쓸 수 없는 낱말 — 식에서 이름만 쓰면 필드 대신 다른 것으로 읽힌다 (서버 Compiler.RESERVED_FIELD_NAMES, 형식 3장) */
    const RESERVED_FIELD_NAMES = ['header', 'body', 'footer', 'req', 'args', 'i', 'this', 'it', 'true', 'false', 'in'];
    /** 메시지 이름으로 쓸 수 없는 낱말 — Reply 가 따로 쓴다 */
    const RESERVED_MESSAGE_NAMES = ['none', 'echo'];
    const HEX_LINE = /^\s*([0-9A-Fa-f]{2}\s*)*$/;

    /** 필드의 숫자형 표현 형식 (형식 6.1). 크기는 size, 바이트 순서는 byteOrder 속성 */
    const NUMBER_TYPES = ['UINT', 'INT', 'FLOAT'];
    const BYTE_ORDERS = ['big', 'little', 'word-swap', 'byte-swap'];
    function isNumberType(t) { return NUMBER_TYPES.indexOf(t) >= 0; }
    /** 그 형식이 가질 수 있는 크기와, 그 크기가 가질 수 있는 바이트 순서 */
    function numberSizes(t) { return t === 'FLOAT' ? [4, 8] : [1, 2, 3, 4, 8]; }
    function byteOrdersFor(size) { return size >= 4 ? BYTE_ORDERS : size >= 2 ? ['big', 'little'] : []; }
    /** 표에 보일 한 줄: "UINT 2", "INT 4 word-swap" */
    function numberText(f) {
        return f.type + (typeof f.size === 'number' ? ' ' + f.size : '') + (f.byteOrder && f.byteOrder !== 'big' && f.size > 1 ? ' ' + f.byteOrder : '');
    }
    function isIdent(s) { return typeof s === 'string' && IDENT.test(s); }
    function has(o, k) { return o != null && Object.prototype.hasOwnProperty.call(o, k) && o[k] !== '' && o[k] !== undefined; }
    function keys(o) { return o && typeof o === 'object' ? Object.keys(o) : []; }
    function list(v) { return Array.isArray(v) ? v : []; }

    // ── 새 항목 ───────────────────────────────────────────────

    function newDoc() {
        return {
            format: 'cts-protocol', version: 1, name: 'new-protocol', doc: '',
            params: {}, consts: {}, checksums: {}, framing: {}, targets: {}, structures: {}, frames: {},
            messages: [], tests: [],
        };
    }
    function newField() { return { name: '', type: 'UINT', size: 1 }; }
    function newMessage(direction, frame) {
        const m = { name: '', direction: direction };
        if (frame) m.frame = frame;
        m.body = [];
        if (direction === 'rx') m.respond = [];
        return m;
    }
    function newFrame() { return { header: [], footer: [] }; }
    function newChecksum() { return { algorithm: 'CRC-16/MODBUS', covers: { from: 'header', to: 'body' } }; }
    function newTargetGroup(kind) {
        if (kind === 'sequence') return { kind: 'sequence', base: 0, slotSize: 2, items: [] };
        if (kind === 'pattern') return { kind: 'pattern', pattern: '' };
        return { kind: 'named', items: [] };
    }
    function newRule() { return { reply: 'none' }; }
    function newTest() { return { name: '', send: '', expect: null }; }

    /** 객체 키 이름을 바꾼다. 순서를 지킨다 */
    function renameKey(obj, from, to) {
        const out = {};
        keys(obj).forEach(k => { out[k === from ? to : k] = obj[k]; });
        keys(obj).forEach(k => delete obj[k]);
        keys(out).forEach(k => { obj[k] = out[k]; });
    }

    // ── 필드 모양과 요약 ──────────────────────────────────────

    function kindOf(f, doc) {
        if (!f || typeof f !== 'object') return 'unknown';
        const t = f.type;
        if (has(f, 'structure')) return 'structure';
        if (t === 'BITS') return 'BITS';
        if (!t && Array.isArray(f.fields)) return 'inline';   // 예전 인라인 구조체 — 지금은 오류 (형식 6.10)
        if (!t) return 'unknown';
        if (isNumberType(t)) return 'numeric';
        if (SPECIAL_TYPES.indexOf(t) >= 0) return t;
        return 'unknown';
    }

    /** 고정값 한 줄. 16진·2진 표기 글자("0x02")는 수처럼, 그 밖의 글자는 따옴표로 */
    function showConst(v) {
        if (typeof v === 'string' && /^0[xXbB][0-9A-Fa-f]+$/.test(v)) return v;
        if (typeof v === 'string') return '\'' + JSON.stringify(v).slice(1, -1) + '\'';
        return String(v);
    }

    function lengthText(f, doc) {
        const k = kindOf(f, doc);
        let s = '';
        if (k === 'numeric') s = typeof f.size === 'number' ? f.size + ' B' : '';
        else if (has(f, 'size')) s = typeof f.size === 'number' ? f.size + ' B' : 'size ' + f.size;
        else if (has(f, 'terminator')) s = 'until ' + showConst(f.terminator);
        else if (k === 'structure') s = list(((doc && doc.structures || {})[f.structure] || {}).fields).length + ' fields';
        else if (k === 'BITS') s = list(f.fields).length + ' bit fields';
        if (has(f, 'count')) s = (s ? s + ' ' : '') + '× ' + f.count;
        return s;
    }

    function valueParts(f) {
        const out = [];
        if (!f) return out;
        if (has(f, 'checksum')) out.push({ k: 'checksum', t: f.checksum });
        if (has(f, 'const')) out.push({ k: 'const', t: showConst(f.const) });
        if (has(f, 'value')) out.push({ k: 'value', t: String(f.value) });
        if (has(f, 'target')) out.push({ k: 'target', t: f.target });
        if (has(f, 'expect')) out.push({ k: 'expect', t: f.expect });
        if (has(f, 'when')) out.push({ k: 'when', t: f.when });
        return out;
    }

    /** 순차 타겟 항목의 번지와 칸 수. 항목 크기를 모르면 slots 가 null */
    function itemBytes(it) {
        if (typeof it.size === 'number') return it.size;
        return null;
    }
    /**
     * BITS 의 비트 필드 자리 — 엔진(Compiler)과 같은 규칙. bit 를 적으면 그 자리(값의 맨 아래 비트 = 0),
     * 적지 않으면 앞 필드 다음 자리(lsb: 위로, msb: 아래로). bits 를 적지 않으면 1비트.
     */
    function bitLayout(f) {
        const total = (typeof (f && f.size) === 'number' ? f.size : 0) * 8;
        const msb = f && f.bitOrder === 'msb';
        let cursor = msb ? total : 0;
        return list(f && f.fields).map(function (b) {
            b = b || {};
            const bits = typeof b.bits === 'number' && b.bits >= 1 ? b.bits : 1;
            let pos;
            if (typeof b.bit === 'number') { pos = b.bit; cursor = msb ? pos : pos + bits; }
            else if (msb) { cursor -= bits; pos = cursor; }
            else { pos = cursor; cursor += bits; }
            return { pos: pos, bits: bits };
        });
    }
    function bitText(r) { return r.bits === 1 ? 'bit ' + r.pos : 'bits ' + r.pos + '–' + (r.pos + r.bits - 1); }

    function sequenceLayout(g) {
        const slotSize = typeof g.slotSize === 'number' && g.slotSize > 0 ? g.slotSize : 1;
        let addr = typeof g.base === 'number' ? g.base : 0;
        return list(g.items).map(function (it) {
            if (typeof it.at === 'number') addr = it.at;
            if (has(it, 'skip')) {
                const n = typeof it.skip === 'number' ? it.skip : 0;
                const row = { address: addr, slots: n };
                addr += n;
                return row;
            }
            const b = itemBytes(it);
            const slots = b == null ? null : Math.max(1, Math.ceil(b / slotSize));
            const row = { address: addr, slots: slots };
            if (slots != null) addr += slots;
            return row;
        });
    }

    // ── 검증 ──────────────────────────────────────────────────

    function validate(doc) {
        const out = [];
        const err = (at, message) => out.push({ level: 'error', at: at, message: message });
        const warn = (at, message) => out.push({ level: 'warn', at: at, message: message });
        if (!doc || typeof doc !== 'object' || Array.isArray(doc)) { err([], 'The document must be a JSON object.'); return out; }

        if (doc.format !== 'cts-protocol' || doc.version !== 1) err(['format'], 'Format must be "cts-protocol" version 1.');
        if (!has(doc, 'name')) err(['name'], 'Document name is required.');

        const params = doc.params || {}, consts = doc.consts || {};
        const checksums = doc.checksums || {}, targets = doc.targets || {}, structures = doc.structures || {}, frames = doc.frames || {};
        if (has(doc, 'types')) err(['types'], '"types" is now "structures"; a field uses one with "structure": name.');
        const messages = list(doc.messages);

        // 이름 있는 값($)
        keys(params).forEach(function (n) {
            if (!isIdent(n)) err(['params', n], 'Parameter name "' + n + '" must start with a letter and use only letters, digits and _.');
            // 파라미터는 값이라 표현이 없다 — value(integer 기본 / float)와 범위만 (형식 11). 엔진(Compiler.params)과 같다
            const p = params[n] || {}, pat = ['params', n];
            keys(p).filter(k => ['value', 'default', 'min', 'max', 'doc'].indexOf(k) < 0).forEach(function (k) {
                err(pat.concat([k]), k === 'type' ? 'Parameter "' + n + '" declares its value as integer or float (value), not a type.'
                                                  : 'Parameter "' + n + '" has no "' + k + '"; use value, default, min, max or doc.');
            });
            if (has(p, 'value') && p.value !== 'integer' && p.value !== 'float') err(pat.concat(['value']), 'Parameter "' + n + '": value must be integer or float.');
            const real = p.value === 'float';
            ['default', 'min', 'max'].forEach(function (k) {
                if (!has(p, k)) return;
                if (typeof p[k] !== 'number') err(pat.concat([k]), 'Parameter "' + n + '": ' + k + ' must be a number.');
                else if (!real && p[k] !== Math.round(p[k])) err(pat.concat([k]), 'Parameter "' + n + '" is integer, so ' + k + ' must be a whole number.');
            });
            if (typeof p.min === 'number' && typeof p.max === 'number' && p.min > p.max) err(pat.concat(['max']), 'Parameter "' + n + '": max is less than min.');
            if (typeof p.default === 'number' && (typeof p.min === 'number' && p.default < p.min || typeof p.max === 'number' && p.default > p.max)) {
                err(pat.concat(['default']), 'Parameter "' + n + '": the default is outside min–max.');
            }
        });
        keys(consts).forEach(function (n) {
            if (!isIdent(n)) err(['consts', n], 'Constant name "' + n + '" must start with a letter and use only letters, digits and _.');
            if (has(params, n)) err(['consts', n], 'Constant "' + n + '" has the same name as a parameter.');
        });
        const dollars = {};
        keys(params).concat(keys(consts)).forEach(n => { dollars[n] = true; });

        // 체크섬
        keys(checksums).forEach(function (n) {
            const c = checksums[n] || {}, at = ['checksums', n];
            if (!isIdent(n)) err(at, 'Checksum name "' + n + '" must start with a letter and use only letters, digits and _.');
            if (!has(c, 'algorithm')) err(at, 'Checksum "' + n + '" needs an algorithm.');
            else if (ALGORITHMS.indexOf(c.algorithm) < 0) err(at, 'Checksum "' + n + '" has unknown algorithm ' + c.algorithm + '.');
            if (c.algorithm === 'CRC') ['width', 'poly', 'init', 'xorout'].forEach(function (k) {
                if (!has(c, k)) err(at, 'Custom CRC "' + n + '" needs ' + k + '.');
            });
            if (c.algorithm === 'SUM' && !has(c, 'width')) err(at, 'SUM checksum "' + n + '" needs a width.');
            if (c.algorithm === 'EXPR' && !has(c, 'step')) err(at, 'EXPR checksum "' + n + '" needs a step expression.');
            if (!c.covers || !has(c.covers, 'from') || !has(c.covers, 'to')) err(at, 'Checksum "' + n + '" must say which bytes it covers (from, to).');
        });

        // 타겟
        const declared = {};
        keys(targets).forEach(function (g) {
            const grp = targets[g] || {}, at = ['targets', g];
            if (!isIdent(g)) err(at, 'Target group name "' + g + '" must start with a letter and use only letters, digits and _.');
            if (['sequence', 'named', 'pattern'].indexOf(grp.kind) < 0) { err(at, 'Target group "' + g + '" must be sequence, named or pattern.'); return; }
            if (grp.kind === 'pattern') {
                onlyValue(grp, at, 'Pattern group "' + g + '"', PATTERN_KEYS);
                if (!has(grp, 'pattern')) err(at, 'Pattern group "' + g + '" needs a pattern.');
                else { try { new RegExp(grp.pattern); } catch (e) { err(at, 'Pattern of "' + g + '" is not a valid regular expression.'); } }
                return;
            }
            list(grp.items).forEach(function (it, i) {
                const iat = at.concat(['items', i]);
                if (has(it, 'skip')) return;
                // 비트 집합: 항목 자신은 타겟이 없고 비트 필드마다 타겟·고정값을 갖는다 (순차 타겟 전용)
                if (it && it.type === 'BITS') {
                    if (grp.kind !== 'sequence') { err(iat, 'Item ' + i + ' of "' + g + '": BITS items belong in a sequence group.'); return; }
                    if (has(it, 'target')) err(iat.concat(['target']), 'Item ' + i + ' of "' + g + '" is BITS — give each bit field its target instead.');
                    if (typeof it.size !== 'number') err(iat, 'Item ' + i + ' of "' + g + '" (BITS) needs a size in bytes.');
                    checkBitLayout(it, iat, 'Item ' + i + ' of "' + g + '"');
                    list(it.fields).forEach(function (b, j) {
                        const bat = iat.concat(['fields', j]);
                        if (b && has(b, 'target')) {
                            if (declared[b.target]) err(bat, 'Target "' + b.target + '" is declared twice.');
                            declared[b.target] = { group: g, item: { target: b.target, type: 'BIT', bits: b.bits } };
                        }
                    });
                    return;
                }
                if (grp.kind === 'named') {
                    if (!has(it, 'target')) { err(iat, 'Item ' + i + ' of "' + g + '" needs a target.'); return; }
                    onlyValue(it, iat, 'Named target "' + it.target + '"', NAMED_KEYS);
                    if (declared[it.target]) err(iat, 'Target "' + it.target + '" is declared twice.');
                    declared[it.target] = { group: g, item: it, named: true };
                    return;
                }
                if (has(it, 'target')) {
                    if (declared[it.target]) err(iat, 'Target "' + it.target + '" is declared twice.');
                    declared[it.target] = { group: g, item: it };
                } else if (!has(it, 'const')) {
                    err(iat, 'Item ' + i + ' of "' + g + '" needs a target, a const or a skip.');
                }
                if (grp.kind === 'sequence') {
                    if (!has(it, 'type')) err(iat, 'Item ' + i + ' of "' + g + '" needs a type.');
                    else if (!isNumberType(it.type) && SPECIAL_TYPES.indexOf(it.type) < 0) err(iat, 'Item ' + i + ' of "' + g + '" has unknown type ' + it.type + '.');
                    else if (typeof it.size !== 'number') err(iat, 'Item ' + i + ' of "' + g + '" (' + it.type + ') needs a size in bytes.');
                    else if (isNumberType(it.type)) checkNumber(it, iat, 'Item ' + i + ' of "' + g + '"');
                }
            });
            if (grp.kind === 'sequence') {
                const seen = {};
                sequenceLayout(grp).forEach(function (r, i) {
                    if (r.slots == null || has(grp.items[i], 'skip')) return;
                    for (let a = r.address; a < r.address + r.slots; a++) {
                        if (seen[a] != null) err(at.concat(['items', i]), 'Address ' + a + ' of "' + g + '" is used by items ' + seen[a] + ' and ' + i + '.');
                        seen[a] = i;
                    }
                });
            }
        });

        /** 숫자형: 크기는 형식에 맞게(정수 1·2·3·4·8, 실수 4·8), 바이트 순서는 크기에 맞게 — 엔진(Compiler.numberEncoding)과 같다 */
        function checkNumber(f, at, what) {
            if (typeof f.size !== 'number') { err(at.concat(['size']), what + ' (' + f.type + ') needs a size in bytes (' + numberSizes(f.type).join(', ') + ').'); return; }
            if (numberSizes(f.type).indexOf(f.size) < 0) { err(at.concat(['size']), what + ': ' + f.type + ' size must be ' + numberSizes(f.type).join(', ') + ' bytes, not ' + f.size + '.'); return; }
            if (has(f, 'byteOrder') && BYTE_ORDERS.indexOf(f.byteOrder) < 0) err(at.concat(['byteOrder']), what + ': byte order must be big, little, word-swap or byte-swap.');
            else if (has(f, 'byteOrder') && f.size > 1 && byteOrdersFor(f.size).indexOf(f.byteOrder) < 0) err(at.concat(['byteOrder']), what + ': ' + f.byteOrder + ' needs 4 or 8 bytes; a ' + f.size + '-byte number is big or little.');
        }

        /** BITS 의 비트 필드 자리: 크기 안에 들고, 서로 겹치지 않는다 (비는 비트는 0) */
        function checkBitLayout(f, at, what) {
            if (typeof f.size === 'number' && (f.size < 1 || f.size > 8)) err(at.concat(['size']), what + ': BITS size must be 1 to 8 bytes.');
            const total = (typeof f.size === 'number' ? f.size : 0) * 8;
            const used = {};
            bitLayout(f).forEach(function (r, j) {
                const bat = at.concat(['fields', j]);
                const b = list(f.fields)[j] || {};
                if (has(b, 'bits') && (typeof b.bits !== 'number' || b.bits < 1)) { err(bat, what + ' bit field ' + j + ' needs bits (1 or more).'); return; }
                if (r.pos < 0 || r.pos + r.bits > total) { err(bat, what + ' bit field ' + j + ' (' + bitText(r) + ') is outside the ' + total + ' bits (0–' + (total - 1) + ').'); return; }
                for (let x = r.pos; x < r.pos + r.bits; x++) {
                    if (used[x] != null) { err(bat, what + ' bit field ' + j + ' (' + bitText(r) + ') overlaps bit field ' + used[x] + ' at bit ' + x + '.'); break; }
                }
                for (let x = r.pos; x < r.pos + r.bits; x++) used[x] = j;
            });
        }

        /** 이름·패턴 타겟은 값과 access 만 — 표현은 메시지 필드에 */
        function onlyValue(o, at, what, allowed) {
            const bad = keys(o).filter(k => allowed.indexOf(k) < 0)[0];
            if (bad) err(at.concat([bad]), what + ' declares only its value (binary, integer or float) and access. Put the representation (' + bad + ') on the message field that carries it.');
            if (has(o, 'value') && VALUES.indexOf(o.value) < 0) err(at.concat(['value']), what + ': value must be binary, integer or float.');
        }

        /** 타겟을 실은 필드: 수를 나르는가, 선언한 값과 맞는가 */
        function targetValue(f, fat, label) {
            if (!has(f, 'target')) return;
            const carried = valueOfField(f, doc);
            if (carried == null) {
                if (f.type || has(f, 'structure')) err(fat, label + ' is ' + (f.type || 'a structure') + ' — a target holds a number, so give the field a number type.');
                return;
            }
            const d = declared[f.target];
            const v = d && d.item && d.item.value;
            if (v && v !== 'float' && carried === 'float') err(fat, label + ': target "' + f.target + '" is ' + v + ', but the field carries a fractional value (scale, offset, decimals or FP). Declare the target as float or change the field.');
        }

        // 필드 목록 (재귀)
        const fieldDollars = [];
        /** inStructure: 구조체 정의 안 — 구조체는 한 단계라 거기서 구조체를 쓰지 않는다 (형식 6.10) */
        function fieldList(at, fields, owner, inStructure) {
            if (!Array.isArray(fields)) { err(at, owner + ': fields must be a list.'); return; }
            const names = {};
            fields.forEach(function (f, i) {
                const fat = at.concat([i]);
                if (!f || typeof f !== 'object') { err(fat, owner + ' field ' + i + ' is not an object.'); return; }
                const k = kindOf(f, doc);
                const label = owner + ' field ' + (f.name ? '"' + f.name + '"' : has(f, 'target') ? 'for "' + f.target + '"' : i);
                if (!has(f, 'name')) {
                    if (!has(f, 'const') && f.type !== 'PAD' && !has(f, 'checksum') && !has(f, 'target')) err(fat, owner + ' field ' + i + ' needs a name (only const, PAD and target fields may be unnamed).');
                } else if (!isIdent(f.name)) {
                    err(fat, label + ': the name must start with a letter and use only letters, digits and _.');
                } else if (RESERVED_FIELD_NAMES.indexOf(f.name) >= 0) {
                    err(fat, label + ': "' + f.name + '" is reserved; choose another name.');
                } else if (names[f.name]) {
                    err(fat, owner + ' has two fields named "' + f.name + '".');
                } else {
                    names[f.name] = true;
                }
                if (k === 'unknown') err(fat, label + (f.type ? (structures[f.type] ? ': "' + f.type + '" is a structure; write "structure": "' + f.type + '" instead of type.' : ' has unknown type ' + f.type + '.')
                                                            : ' needs a type.'));
                if (k === 'inline') err(fat, label + ' has inline fields; define the group in Structures and use "structure": name.');
                if (k === 'structure') {
                    if (has(f, 'type')) err(fat, label + ' has a type and a structure; give it one.');
                    if (!structures[f.structure]) err(fat, label + ' uses structure "' + f.structure + '", which is not defined.');
                    if (inStructure) err(fat, label + ' uses structure "' + f.structure + '"; a structure cannot contain another structure.');
                }
                if (has(f, 'transform')) {
                    if (k !== 'structure') err(fat, label + ': transforms go on a structure field.');
                    checkTransforms(f.transform, fat, label);
                }
                targetValue(f, fat, label);
                if (['BCD', 'BYTES', 'PAD', 'HEXNUM'].indexOf(k) >= 0 && !has(f, 'size')) err(fat, label + ' (' + k + ') needs a size.');
                if (k === 'numeric') checkNumber(f, fat, label);
                if ((k === 'TEXT' || k === 'TEXTNUM') && !has(f, 'size') && !has(f, 'terminator')) warn(fat, label + ' (' + k + ') has no size or terminator — it runs to the end of the enclosing area.');
                if (has(f, 'checksum') && !checksums[f.checksum]) err(fat, label + ' refers to checksum "' + f.checksum + '", which is not defined.');
                if (has(f, 'target') && keys(targets).length && !declared[f.target]) warn(fat, label + ' uses target "' + f.target + '", which is not declared in Targets.');
                if (k === 'BITS') {
                    if (typeof f.size !== 'number') err(fat, label + ' (BITS) needs a size in bytes.');
                    checkBitLayout(f, fat, label);
                    list(f.fields).forEach(function (b, j) {
                        const bat = fat.concat(['fields', j]);
                        if (!has(b, 'name') && !has(b, 'const') && !has(b, 'target')) err(bat, label + ' bit field ' + j + ' needs a name, a target or a const.');
                        else if (has(b, 'name') && !isIdent(b.name)) err(bat, label + ' bit field "' + b.name + '": the name must start with a letter and use only letters, digits and _.');
                        else if (has(b, 'name') && RESERVED_FIELD_NAMES.indexOf(b.name) >= 0) err(bat, label + ' bit field "' + b.name + '" is reserved; choose another name.');
                    });
                }

                EXPR_ATTRS.forEach(a => { if (typeof f[a] === 'string') fieldDollars.push({ at: fat, text: f[a] }); });
            });
        }

        /** 변환 목록 (필드의 transform, 메시지의 bodyTransform) */
        function checkTransforms(ts, at, what) {
            if (!Array.isArray(ts)) { err(at, what + ': transforms must be a list.'); return; }
            ts.forEach(function (t, j) { if (!t || TRANSFORM_OPS.indexOf(t.op) < 0) err(at, what + ' transform ' + j + ': op must be ascii-hex, add or xor.'); });
        }

        keys(structures).forEach(function (t) {
            const at = ['structures', t];
            if (!isIdent(t)) err(at, 'Structure name "' + t + '" must start with a letter and use only letters, digits and _.');
            fieldList(at.concat(['fields']), (structures[t] || {}).fields, 'Structure "' + t + '"', true);
        });

        keys(frames).forEach(function (n) {
            const fr = frames[n] || {}, at = ['frames', n];
            if (!isIdent(n)) err(at, 'Frame name "' + n + '" must start with a letter and use only letters, digits and _.');
            fieldList(at.concat(['header']), list(fr.header), 'Frame "' + n + '" header');
            fieldList(at.concat(['footer']), list(fr.footer), 'Frame "' + n + '" footer');
            if (typeof fr.bodySize === 'string') fieldDollars.push({ at: at, text: fr.bodySize });
        });

        // 메시지
        const msgNames = {}, txNames = {};
        messages.forEach(function (m, i) {
            if (m && m.direction === 'tx' && m.name) txNames[m.name] = true;
        });
        messages.forEach(function (m, i) {
            const at = ['messages', i];
            if (!m || typeof m !== 'object') { err(at, 'Message ' + i + ' is not an object.'); return; }
            const label = 'Message ' + (m.name ? '"' + m.name + '"' : i);
            if (!has(m, 'name')) err(at, 'Message ' + i + ' needs a name.');
            else if (!isIdent(m.name)) err(at, label + ': the name must start with a letter and use only letters, digits and _.');
            else if (RESERVED_MESSAGE_NAMES.indexOf(m.name) >= 0) err(at, label + ': "' + m.name + '" is reserved for Reply; choose another name.');
            else if (msgNames[m.name]) err(at, 'Two messages are named "' + m.name + '".');
            else msgNames[m.name] = true;
            if (m.direction !== 'rx' && m.direction !== 'tx') err(at, label + ' must be rx (received) or tx (sent).');
            if (has(m, 'frame') && !frames[m.frame]) err(at, label + ' uses frame "' + m.frame + '", which is not defined.');
            const fr = has(m, 'frame') ? (frames[m.frame] || {}) : null;
            if (fr) {
                if (m.header) warn(at, label + ' has its own header, but a frame provides it — the header here is ignored.');
                if (m.footer) warn(at, label + ' has its own footer, but a frame provides it — the footer here is ignored.');
            } else {
                fieldList(at.concat(['header']), list(m.header), label + ' header');
                fieldList(at.concat(['footer']), list(m.footer), label + ' footer');
            }
            fieldList(at.concat(['body']), list(m.body), label + ' body');
            if (has(m, 'bodyTransform')) checkTransforms(m.bodyTransform, at.concat(['bodyTransform']), label + ' body');
            if (typeof m.match === 'string') fieldDollars.push({ at: at, text: m.match });

            if (m.direction === 'rx') {
                if (has(m, 'set')) warn(at, label + ' is received — "set" only applies to sent messages.');
                const rules = list(m.respond);
                if (!rules.length) warn(at, label + ' has no respond rules — the device never answers it.');
                rules.forEach(function (r, j) {
                    const rat = at.concat(['respond', j]);
                    const reply = r && r.reply;
                    if (reply == null || reply === '') err(rat, label + ' respond rule ' + j + ' needs a reply (a sent message, none or echo).');
                    else if (reply !== 'none' && reply !== 'echo' && !txNames[reply]) err(rat, label + ' respond rule ' + j + ' replies with "' + reply + '", which is not a sent (tx) message.');
                    if (typeof r.when === 'string') fieldDollars.push({ at: rat, text: r.when });
                    keys(r.args).forEach(function (a) {
                        if (!isIdent(a)) err(rat, label + ' respond rule ' + j + ': argument name "' + a + '" must start with a letter and use only letters, digits and _.');
                        if (typeof r.args[a] === 'string') fieldDollars.push({ at: rat, text: r.args[a] });
                    });
                    list(r.write).forEach(function (w, k) {
                        const wat = rat.concat(['write', k]);
                        if (has(w, 'target')) {
                            const d = declared[w.target];
                            if (keys(targets).length && !d) warn(wat, label + ' writes to target "' + w.target + '", which is not declared.');
                            else if (d && d.item.access !== 'rw') err(wat, label + ' writes to target "' + w.target + '", which is not writable (access rw).');
                            if (!has(w, 'value')) err(wat, label + ' write ' + k + ' needs a value.');
                        } else if (has(w, 'map')) {
                            if (!targets[w.map] || targets[w.map].kind !== 'sequence') err(wat, label + ' write ' + k + ' uses map "' + w.map + '", which is not a sequence target group.');
                            if (!has(w, 'address') || !has(w, 'bytes')) err(wat, label + ' write ' + k + ' needs an address and bytes.');
                        } else {
                            err(wat, label + ' write ' + k + ' needs a target or a map.');
                        }
                        ['value', 'address', 'bytes'].forEach(a => { if (typeof w[a] === 'string') fieldDollars.push({ at: wat, text: w[a] }); });
                    });
                });
            } else if (m.direction === 'tx') {
                if (has(m, 'match')) warn(at, label + ' is sent — "match" only applies to received messages.');
                if (has(m, 'respond')) warn(at, label + ' is sent — respond rules only apply to received messages.');
                keys(m.set).forEach(s => { if (typeof m.set[s] === 'string') fieldDollars.push({ at: at, text: m.set[s] }); });
                // 보낼 때 모든 필드에 값이 있어야 한다
                const segs = fr ? { header: list(fr.header), body: list(m.body), footer: list(fr.footer) }
                                : { header: list(m.header), body: list(m.body), footer: list(m.footer) };
                Object.keys(segs).forEach(function (seg) {
                    segs[seg].forEach(function (f, j) {
                        if (!f || f.type === 'PAD') return;
                        const k = kindOf(f, doc);
                        if (k === 'structure' || k === 'BITS') return;   // 안쪽 필드가 각자 갖는다
                        const set = m.set && has(m.set, seg + '.' + f.name);
                        if (has(f, 'const') || has(f, 'value') || has(f, 'target') || has(f, 'checksum') || set) return;
                        const fat = (fr && seg !== 'body') ? ['frames', m.frame, seg, j] : at.concat([seg, j]);
                        err(fat, label + ': ' + seg + ' field "' + (f.name || j) + '" has no value to send (const, value, target, checksum or the message\'s set).');
                    });
                });
            }
        });
        if (doc.onUnmatched && has(doc.onUnmatched, 'reply')) {
            const r = doc.onUnmatched.reply;
            if (r !== 'none' && r !== 'echo' && !txNames[r]) err(['onUnmatched'], 'Unmatched frames reply with "' + r + '", which is not a sent (tx) message.');
        }
        if (!messages.some(m => m && m.direction === 'rx')) warn(['messages'], 'There is no received (rx) message — the device never answers anything.');

        // $이름
        fieldDollars.forEach(function (x) {
            let m;
            DOLLAR.lastIndex = 0;
            while ((m = DOLLAR.exec(x.text)) !== null) {
                if (!dollars[m[1]]) err(x.at, '"$' + m[1] + '" is not a parameter or a constant.');
            }
        });

        // 시험 사례
        list(doc.tests).forEach(function (t, i) {
            const at = ['tests', i];
            const label = 'Test ' + (t && t.name ? '"' + t.name + '"' : i);
            if (!t || !has(t, 'send')) err(at, label + ' needs bytes to send.');
            else if (!HEX_LINE.test(t.send)) err(at, label + ': send must be hex bytes like "01 03 00 0A".');
            if (t && t.expect != null && !HEX_LINE.test(t.expect)) err(at, label + ': expect must be hex bytes or empty for no reply.');
            keys(t && t.data).forEach(function (k) {
                if (keys(targets).length && !declared[k]) warn(at, label + ' sets target "' + k + '", which is not declared.');
            });
        });

        return out;
    }

    global.CtsProtocolModel = {
        SPECIAL_TYPES: SPECIAL_TYPES, ENCODINGS: ENCODINGS, OVERFLOW: OVERFLOW, VALUES: VALUES, valueOfField: valueOfField, bitLayout: bitLayout, bitText: bitText,
        CRC_CATALOG: CRC_CATALOG, ALGORITHMS: ALGORITHMS, KIND_ATTRS: KIND_ATTRS, COMMON_ATTRS: COMMON_ATTRS,
        isIdent: isIdent,
        NUMBER_TYPES: NUMBER_TYPES, isNumberType: isNumberType, numberSizes: numberSizes, byteOrdersFor: byteOrdersFor, numberText: numberText,
        newDoc: newDoc, newField: newField, newMessage: newMessage, newFrame: newFrame, newChecksum: newChecksum,
        newTargetGroup: newTargetGroup, newRule: newRule, newTest: newTest, renameKey: renameKey,
        kindOf: kindOf, lengthText: lengthText, valueParts: valueParts, showConst: showConst,
        sequenceLayout: sequenceLayout, validate: validate,
    };
})(window);
