/**
 * Simulation target 입력 보조.
 * <p>
 * 프로토콜마다 target 을 조각(셀렉터·입력칸)으로 나눠 고르게 하고, 그 조각으로 target 문자열을 만든다.
 * 저장되는 값은 여전히 문자열 하나다. 텍스트 칸은 그대로 두고 서로 맞춘다 — 조각을 고르면 텍스트가 바뀌고,
 * 텍스트를 직접 치면 조각에 다시 나눠 넣는다.
 * <p>
 * 형식·범위 판정은 서버 SimulationTargetValidator 가 다시 한다. 형식이나 범위를 고치면 함께 고친다.
 *
 *   const aid = CtsTargetAid.mount(el, {
 *       device,                       // 장비 (protocol, communicationSettings)
 *       siblings,                     // 같은 장비의 Simulation [{id, name, target, policies}]
 *       selfId,                       // 편집 중인 Simulation id (새로 만들면 없음)
 *       statusEl,                     // 상태 줄을 그릴 곳 (없으면 그리지 않는다)
 *       onChange: target => {},       // 조각을 골라 target 이 바뀌었을 때
 *       insertText: text => {},       // DB: 정책 자리표시자를 커서 위치에 넣는다
 *       policyIds: () => ['v1'],      // DB: 지금 정의된 정책 ID
 *       checkShared: false,           // 다른 Simulation 과 같은 target 이어도 알리지 않는다(Test Plan 처럼 그 target 에 쓰는 화면)
 *   });
 *   aid.setValue(text);    // 텍스트 칸에서 직접 친 값
 *   aid.refresh();         // 정책 ID·형제 목록이 바뀌었을 때
 *   aid.problems();        // [{level: 'warn'|'error', text}]
 *   CtsTargetAid.check(opts, text);   // 화면 없이 problems() 만
 */
(function (global) {
    // BACnet object instance 는 22비트이고 4194303 은 '아무 instance' 를 뜻하는 예약값이다
    const BACNET_INSTANCE_MAX = 4194302;
    const DAEYUN_CHANNELS = 32;
    const XGT_BLOCKS = [
        { v: 'P', t: 'P · I/O relay' },
        { v: 'M', t: 'M · Internal relay' },
        { v: 'K', t: 'K · Keep relay' },
        { v: 'D', t: 'D · Data register' },
        { v: 'S', t: 'S · Step controller' },
        { v: 'U', t: 'U · Special module' },
    ];
    const XGT_SIZES = [
        { v: 'B', t: 'B · Byte', bytes: 1 },
        { v: 'W', t: 'W · Word', bytes: 2 },
        { v: 'D', t: 'D · Double word', bytes: 4 },
    ];
    const SNMP_BASES = [
        { v: '.1.3.6.1.2.1', t: 'mib-2' },
        { v: '.1.3.6.1.4.1', t: 'enterprises' },
    ];

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g,
            c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    function intOf(v) {
        return /^\d+$/.test(String(v == null ? '' : v).trim()) ? parseInt(v, 10) : null;
    }

    function setting(ctx, key, fallback) {
        const cs = (ctx.device && ctx.device.communicationSettings) || {};
        const n = parseInt(cs[key], 10);
        return isNaN(n) ? fallback : n;
    }

    /** data type 의 바이트 수. 정해지지 않으면 0. */
    function bytesOf(dt) {
        const t = String(dt || '');
        if (t.indexOf('8') >= 0) return 1;
        if (t.indexOf('16') >= 0) return 2;
        if (t.indexOf('32') >= 0) return 4;
        if (t.indexOf('64') >= 0) return 8;
        return 0;
    }

    function firstType(sim) {
        return sim && sim.policies && sim.policies.length ? sim.policies[0].data_type : null;
    }

    /** PGD(통신 설정 tx)에서 SIMULATION 필드만 뽑는다. 같은 ID 는 하나로 친다(값이 ID 로 저장된다). */
    /*
     * GENERIC·MK RCU 의 타겟은 장비의 프로토콜 정의가 정한다 (docs/PROTOCOL_ENGINE_DESIGN.md 9.3).
     * 정의별 타겟 목록(/api/protocols/{id}/targets)을 한 번 받아 두고, 받는 동안 그린 보조는 받은 뒤 다시 그린다.
     */
    const DEF_TARGETS = {};          // 정의 id → { name, targets: [...], patterns: [...] } 또는 { failed: true }
    const DEF_LOADING = {};
    const LIVE = new Set();          // 다시 그릴 보조

    function definitionIdOf(ctx) {
        const cs = (ctx.device && ctx.device.communicationSettings) || {};
        const id = cs.definitionId;
        return id == null || String(id).trim() === '' ? null : String(id).trim();
    }

    /** 정의의 타겟 목록. 아직 없으면 받기 시작하고 undefined, 정의가 없으면 null */
    function definitionTargets(ctx) {
        const id = definitionIdOf(ctx);
        if (id == null) return null;
        if (DEF_TARGETS[id]) return DEF_TARGETS[id];
        if (!DEF_LOADING[id] && typeof fetch === 'function') {
            DEF_LOADING[id] = true;
            fetch('/api/protocols/' + encodeURIComponent(id) + '/targets')
                .then(r => r.ok ? r.json() : { failed: true })
                .catch(() => ({ failed: true }))
                .then(function (d) {
                    DEF_TARGETS[id] = d;
                    LIVE.forEach(a => a.refresh());
                });
        }
        return undefined;
    }

    /** 고를 수 있는 타겟 [{ id, group, dataType(설명) }] */
    function simulationFields(ctx) {
        const d = definitionTargets(ctx);
        if (!d || d.failed) return [];
        return (d.targets || []).map(t => ({ id: t.target, tx: t.group || 'other', dataType: t.type }));
    }

    function matchesPattern(ctx, target) {
        const d = definitionTargets(ctx);
        if (!d || d.failed) return null;
        return (d.patterns || []).filter(function (p) {
            try { return new RegExp('^(?:' + p.pattern + ')$').test(target); } catch (e) { return false; }
        })[0] || null;
    }

    /** 다른 Simulation 들을 같은 형식으로 나눈 값. 나눌 수 없는 target 은 뺀다. */
    function parsedSiblings(scheme, ctx) {
        return ctx.siblings.filter(s => s.id !== ctx.selfId)
            .map(s => ({ sim: s, v: scheme.parse(String(s.target || '').trim(), ctx) }))
            .filter(x => x.v);
    }

    /** 숫자 조각에서 다른 조각이 같은 형제들이 쓰는 값. */
    function usedNumbers(scheme, ctx, v, key, sameKeys) {
        return parsedSiblings(scheme, ctx)
            .filter(x => sameKeys.every(k => x.v[k] === v[k]))
            .map(x => ({ n: intOf(x.v[key]), sim: x.sim }))
            .filter(x => x.n != null);
    }

    function maxPlusOne(nums, min) {
        return nums.length ? Math.max.apply(null, nums) + 1 : min;
    }

    function fieldOptions(ctx, current, used) {
        const d = definitionTargets(ctx);
        if (d === null) return [{ v: current || '', t: 'This device has no protocol definition', disabled: true }];
        if (d === undefined) return [{ v: current || '', t: 'Loading targets…', disabled: true }];
        if (d.failed) return [{ v: current || '', t: 'The protocol definition could not be read', disabled: true }];
        const fields = simulationFields(ctx);
        const opts = fields.map(f => ({
            v: f.id,
            t: f.id + (f.dataType ? '  ·  ' + f.dataType : '') + (used[f.id] ? '  ·  used by ' + used[f.id] : ''),
            group: f.tx,
        }));
        if (current && !fields.some(f => f.id === current)) {
            opts.unshift({ v: current, t: current + (matchesPattern(ctx, current) ? '  ·  matches a pattern' : '  ·  not in the definition') });
        }
        if (!opts.length) opts.push({ v: '', t: 'The definition declares no targets', disabled: true });
        return opts;
    }

    function usedByName(scheme, ctx) {
        const used = {};
        ctx.siblings.filter(s => s.id !== ctx.selfId)
            .forEach(s => { const t = String(s.target || '').trim(); if (!used[t]) used[t] = s.name; });
        return used;
    }

    /** PGD 필드가 어느 TX 에 어떤 타입으로 실려 나가는지. */
    function fieldNote(ctx, field) {
        const d = definitionTargets(ctx);
        if (!d || d.failed) return null;
        const f = simulationFields(ctx).filter(x => x.id === field)[0];
        if (f) return 'In ' + f.tx + ' of "' + d.name + '"' + (f.dataType ? ' as ' + f.dataType : '');
        const p = matchesPattern(ctx, field);
        return p ? 'Matches pattern ' + p.pattern + ' of "' + d.name + '"' : null;
    }

    /** 서버(SimulationTargetValidator.definitionTargetError)와 같은 판정 */
    function fieldProblems(ctx, field) {
        const d = definitionTargets(ctx);
        if (d === null) return [{ level: 'error', text: 'This device has no protocol definition. Choose one in the device editor first.' }];
        if (d === undefined) return [];
        if (d.failed) return [{ level: 'error', text: 'The protocol definition of this device does not exist or does not compile.' }];
        if (!field) return [{ level: 'error', text: 'Pick a target.' }];
        if (!simulationFields(ctx).some(f => f.id === field) && !matchesPattern(ctx, field)) {
            return [{ level: 'error', text: 'No target "' + field + '" in protocol definition "' + d.name + '".' }];
        }
        return [];
    }

    function rangeProblem(label, n, min, max) {
        if (n == null) return { level: 'error', text: 'Enter ' + label + '.' };
        if (n < min || n > max) return { level: 'error', text: label.charAt(0).toUpperCase() + label.slice(1) + ' ' + n + ' is out of range (' + min + '–' + max + ').' };
        return null;
    }

    /*
     * 프로토콜별 형식.
     *   parts(v, ctx)   보일 조각. kind: select | number | text | combo
     *   parse(t, ctx)   target → 조각 값. 형식이 아니면 null
     *   build(v, ctx)   조각 값 → target
     *   defaults(ctx)   비어 있을 때 조각 값
     *   check(v, ctx)   [{level, text}]
     *   note(v, ctx)    상태 줄에 덧붙이는 설명
     *   next(v, ctx)    'Next free' 가 고를 값 {key, value}
     */
    const SCHEMES = {
        BACNET: {
            format: '<object_type> <instance>',
            parts: () => [
                { key: 'kind', label: 'Object', kind: 'select', options: [
                    { v: 'analog', t: 'Analog' }, { v: 'binary', t: 'Binary' }, { v: 'multi_state', t: 'Multi-state' }] },
                { key: 'io', label: 'Kind', kind: 'select', options: [
                    { v: 'input', t: 'Input' }, { v: 'output', t: 'Output' }, { v: 'value', t: 'Value' }] },
                { key: 'instance', label: 'Instance', kind: 'number', next: true, placeholder: '0–' + BACNET_INSTANCE_MAX, width: 230 },
            ],
            defaults: () => ({ kind: 'analog', io: 'input', instance: '' }),
            parse(t) {
                const m = /^(analog|binary|multi_state)_(input|output|value)\s+(\d+)$/.exec(t);
                return m ? { kind: m[1], io: m[2], instance: m[3] } : null;
            },
            build: v => v.kind + '_' + v.io + ' ' + v.instance,
            check(v) {
                const p = rangeProblem('an instance number', intOf(v.instance), 0, BACNET_INSTANCE_MAX);
                return p ? [p] : [];
            },
            next(v, ctx) {
                const used = usedNumbers(this, ctx, v, 'instance', ['kind', 'io']).map(x => x.n);
                return { key: 'instance', value: maxPlusOne(used, 0) };
            },
        },

        MODBUS: {
            format: '<fc>_<address>',
            parts: () => [
                { key: 'fc', label: 'Function code', kind: 'select', options: [
                    { v: '1', t: '1 · Coil' }, { v: '2', t: '2 · Discrete input' },
                    { v: '3', t: '3 · Holding register' }, { v: '4', t: '4 · Input register' }] },
                { key: 'address', label: 'Address', kind: 'number', next: true, width: 200 },
            ],
            defaults: () => ({ fc: '3', address: '' }),
            parse(t) {
                const m = /^([1-4])_(\d+)$/.exec(t);
                return m ? { fc: m[1], address: m[2] } : null;
            },
            build: v => v.fc + '_' + v.address,
            check(v, ctx) {
                const p = rangeProblem('an address', intOf(v.address), 0, setting(ctx, 'registers', 65536) - 1);
                return p ? [p] : [];
            },
            note: (v, ctx) => 'Addresses 0–' + (setting(ctx, 'registers', 65536) - 1),
            next(v, ctx) {
                // 레지스터는 타입 폭만큼 차지한다(4바이트 = 2칸). 겹치지 않게 가장 뒤의 끝 다음을 고른다
                let end = 0;
                usedNumbers(this, ctx, v, 'address', ['fc']).forEach(x => {
                    const span = (v.fc === '3' || v.fc === '4') ? Math.max(1, bytesOf(firstType(x.sim)) / 2) : 1;
                    end = Math.max(end, x.n + span);
                });
                return { key: 'address', value: end };
            },
        },

        XGT_PLC: {
            format: '%<block><size><address>',
            parts: () => [
                { key: 'block', label: 'Block', kind: 'select', options: XGT_BLOCKS },
                { key: 'size', label: 'Size', kind: 'select', options: XGT_SIZES },
                { key: 'address', label: 'Address', kind: 'number', next: true, width: 200 },
            ],
            defaults: () => ({ block: 'M', size: 'W', address: '' }),
            parse(t) {
                const m = /^%([MDPKSU])([BWD])(\d+)$/i.exec(t);
                return m ? { block: m[1].toUpperCase(), size: m[2].toUpperCase(), address: m[3] } : null;
            },
            build: v => '%' + v.block + v.size + v.address,
            width: v => XGT_SIZES.filter(s => s.v === v.size)[0].bytes,
            check(v, ctx) {
                const w = this.width(v);
                const p = rangeProblem('an address', intOf(v.address), 0, Math.floor(setting(ctx, 'registers', 65536) / w) - 1);
                return p ? [p] : [];
            },
            note(v, ctx) {
                const w = this.width(v), a = intOf(v.address);
                const max = Math.floor(setting(ctx, 'registers', 65536) / w) - 1;
                return 'Addresses 0–' + max + (a == null ? '' : '  ·  bytes ' + (a * w) + (w > 1 ? '–' + (a * w + w - 1) : '') + ' of block ' + v.block);
            },
            next(v, ctx) {
                // 같은 블록이면 크기가 달라도 바이트가 겹친다. 바이트로 끝을 재고 내 폭으로 나눈다
                let endByte = 0;
                parsedSiblings(this, ctx).filter(x => x.v.block === v.block).forEach(x => {
                    endByte = Math.max(endByte, (intOf(x.v.address) + 1) * this.width(x.v));
                });
                return { key: 'address', value: Math.ceil(endByte / this.width(v)) };
            },
        },

        DAEYUN_RCU: {
            format: '@RCUDI_<channel>, @RCUAI_<channel> or a target of the protocol definition',
            parts(v, ctx) {
                const parts = [{ key: 'kind', label: 'Source', kind: 'select', options: [
                    { v: 'DI', t: 'Digital input channel' }, { v: 'AI', t: 'Analog input channel' }, { v: 'FIELD', t: 'Serial (RS) definition target' }] }];
                if (v.kind === 'FIELD') {
                    parts.push({ key: 'field', label: 'Target', kind: 'combo', options: fieldOptions(ctx, v.field, usedByName(this, ctx)), width: 260 });
                } else {
                    const count = Math.min(setting(ctx, v.kind === 'DI' ? 'diCount' : 'aiCount', DAEYUN_CHANNELS), DAEYUN_CHANNELS);
                    const prefix = v.kind === 'DI' ? '@RCUDI_' : '@RCUAI_';
                    const used = usedByName(this, ctx);
                    const opts = [];
                    for (let c = 1; c <= count; c++) {
                        opts.push({ v: String(c), t: String(c) + (used[prefix + c] ? '  ·  used by ' + used[prefix + c] : '') });
                    }
                    if (v.channel && (intOf(v.channel) == null || intOf(v.channel) < 1 || intOf(v.channel) > count)) {
                        opts.unshift({ v: v.channel, t: v.channel + '  ·  out of range' });
                    }
                    parts.push({ key: 'channel', label: 'Channel', kind: 'select', options: opts, width: 200 });
                }
                return parts;
            },
            defaults: () => ({ kind: 'DI', channel: '', field: '' }),
            parse(t) {
                const m = /^@RCU(DI|AI)_(.*)$/.exec(t);
                if (m) return { kind: m[1], channel: m[2], field: '' };
                return { kind: 'FIELD', channel: '', field: t };
            },
            build: v => v.kind === 'FIELD' ? v.field : '@RCU' + v.kind + '_' + v.channel,
            note: (v, ctx) => v.kind === 'FIELD' ? fieldNote(ctx, v.field) : null,
            check(v, ctx) {
                if (v.kind === 'FIELD') return fieldProblems(ctx, v.field);
                const count = Math.min(setting(ctx, v.kind === 'DI' ? 'diCount' : 'aiCount', DAEYUN_CHANNELS), DAEYUN_CHANNELS);
                const p = rangeProblem('a channel', intOf(v.channel), 1, count);
                return p ? [p] : [];
            },
            // 고른 조각이 바뀌면 고를 수 있는 값도 바뀐다
            onPick(v, key, ctx) {
                if (key === 'kind' && v.kind === 'FIELD' && !v.field) {
                    const f = simulationFields(ctx)[0];
                    v.field = f ? f.id : '';
                }
                if (key === 'kind' && v.kind !== 'FIELD') {
                    // 새 범위에 들어가는 채널이면 그대로 둔다
                    const n = intOf(v.channel);
                    const count = Math.min(setting(ctx, v.kind === 'DI' ? 'diCount' : 'aiCount', DAEYUN_CHANNELS), DAEYUN_CHANNELS);
                    if (n == null || n < 1 || n > count) v.channel = '';
                }
            },
        },

        GENERIC: {
            format: 'a target of the protocol definition',
            parts(v, ctx) {
                // 패턴 타겟(ch1 … ch32)도 쓸 수 있게 고르기와 치기를 함께 받는다
                return [{ key: 'field', label: 'Target', kind: 'combo', options: fieldOptions(ctx, v.field, usedByName(this, ctx)), width: 320 }];
            },
            defaults: ctx => ({ field: '' }),
            parse: t => ({ field: t }),
            build: v => v.field,
            check: (v, ctx) => fieldProblems(ctx, v.field),
            note: (v, ctx) => fieldNote(ctx, v.field),
        },

        SNMP: {
            format: 'a dotted OID',
            parts(v, ctx) {
                const bases = SNMP_BASES.slice();
                parsedSiblings(this, ctx).forEach(x => {
                    if (!bases.some(b => b.v === x.v.base)) bases.push({ v: x.v.base, t: 'used by ' + x.sim.name });
                });
                return [
                    { key: 'base', label: 'Base OID', kind: 'combo', options: bases, width: 300, placeholder: '.1.3.6.1.4.1' },
                    { key: 'index', label: 'Sub-identifier', kind: 'text', next: true, width: 220, placeholder: 'e.g. 1 or 1.0' },
                ];
            },
            defaults: () => ({ base: '', index: '' }),
            parse(t) {
                // 마지막 arc 를 번호로, 나머지를 base 로 본다
                const m = /^(\.?\d+(?:\.\d+)*)\.(\d+)$/.exec(t);
                return m ? { base: m[1], index: m[2] } : null;
            },
            build: v => v.base.replace(/\.+$/, '') + '.' + v.index.replace(/^\.+/, ''),
            check(v) {
                if (!v.base) return [{ level: 'error', text: 'Enter a base OID.' }];
                if (!/^\.?\d+(\.\d+)*$/.test(v.base)) return [{ level: 'error', text: 'The base OID must be dotted numbers (e.g. .1.3.6.1.4.1).' }];
                if (!/^\d+(\.\d+)*$/.test(v.index)) return [{ level: 'error', text: 'Enter the sub-identifier as numbers (e.g. 1 or 1.0).' }];
                return [];
            },
            next(v, ctx) {
                const used = usedNumbers(this, ctx, v, 'index', ['base']).map(x => x.n);
                return { key: 'index', value: maxPlusOne(used, 1) };
            },
        },

        OPC_AGENT: {
            format: 'a tag list index',
            parts: () => [{ key: 'index', label: 'Tag index', kind: 'number', next: true, width: 200 }],
            defaults: () => ({ index: '' }),
            parse(t) {
                return /^\d+$/.test(t) ? { index: t } : null;
            },
            build: v => v.index,
            check(v, ctx) {
                const p = rangeProblem('a tag index', intOf(v.index), 0, setting(ctx, 'registers', 65536) - 1);
                return p ? [p] : [];
            },
            note: (v, ctx) => 'Indexes 0–' + (setting(ctx, 'registers', 65536) - 1),
            next(v, ctx) {
                return { key: 'index', value: maxPlusOne(usedNumbers(this, ctx, v, 'index', []).map(x => x.n), 0) };
            },
        },

        ETHERNET_IP: {
            format: '<tag>[<element>]',
            parts: () => [
                { key: 'tag', label: 'Tag name', kind: 'text', width: 260, placeholder: 'e.g. sys_runtime_acc' },
                { key: 'element', label: 'Element (optional)', kind: 'number', width: 140, placeholder: 'none' },
            ],
            defaults: () => ({ tag: '', element: '' }),
            parse(t) {
                // 끝의 [n] 만 element 로 뗀다. 예전 형식(REAL@TAG1)과 공백은 나눌 수 없다
                if (!t || /[@\s]/.test(t)) return null;
                const m = /^(.*)\[(\d+)\]$/.exec(t);
                return m && m[1] ? { tag: m[1], element: m[2] } : { tag: t, element: '' };
            },
            build: v => v.tag + (v.element !== '' ? '[' + v.element + ']' : ''),
            check(v) {
                if (!v.tag) return [{ level: 'error', text: 'Enter a tag name.' }];
                if (/[@\s]/.test(v.tag)) return [{ level: 'error', text: 'A tag name cannot contain spaces or "@".' }];
                return [];
            },
            // 서버가 같은 태그의 두 번째 등록을 막는다(SimulationService.duplicateTagError)
            duplicate: 'error',
        },

        DB: {
            format: 'an SQL statement with ${policy ID} placeholders',
            parts: () => [],
            parse: t => ({}),
            build: () => '',
            defaults: () => ({}),
            check(v, ctx, text) {
                const ids = ctx.policyIds();
                const found = [], re = /\$\{([^}]*)\}/g;
                let m;
                while ((m = re.exec(text)) !== null) if (found.indexOf(m[1]) < 0) found.push(m[1]);
                const out = [];
                found.filter(id => ids.indexOf(id) < 0).forEach(id =>
                    out.push({ level: 'warn', text: '${' + id + '} has no policy with that ID, so it stays in the SQL as is.' }));
                ids.filter(id => id && found.indexOf(id) < 0).forEach(id =>
                    out.push({ level: 'warn', text: 'Policy ' + id + ' is not used in the SQL. Add ${' + id + '} where its value goes.' }));
                return out;
            },
        },
    };

    class TargetAid {
        constructor(el, opts) {
            this.el = el;
            this.opts = opts;
            this.ctx = {
                device: opts.device,
                siblings: opts.siblings || [],
                selfId: opts.selfId,
                policyIds: opts.policyIds || (() => []),
            };
            this.scheme = SCHEMES[opts.device && opts.device.protocol] || null;
            this.text = '';
            this.v = this.scheme ? this.scheme.defaults(this.ctx) : {};
            this.parsed = true;
            if (!el) return;   // 화면 없이 판정만 한다(CtsTargetAid.check)
            LIVE.add(this);    // 프로토콜 정의의 타겟 목록이 오면 다시 그린다
            el.classList.add('ta');
            el.addEventListener('change', e => this.onPart(e, true));
            el.addEventListener('input', e => this.onPart(e, false));
            el.addEventListener('click', e => this.onClick(e));
            this.render();
        }

        setValue(text) {
            this.text = String(text || '').trim();
            if (!this.scheme) return this.renderStatus();
            if (this.text === '') {
                this.v = this.scheme.defaults(this.ctx);
                this.parsed = true;
            } else {
                const v = this.scheme.parse(this.text, this.ctx);
                this.parsed = !!v;
                if (v) this.v = v;
            }
            this.render();
        }

        setSiblings(siblings) {
            this.ctx.siblings = siblings || [];
            this.render();
        }

        refresh() {
            this.render();
        }

        /** 저장 전에 알릴 문제. 서버가 다시 검증하므로 여기서는 화면에만 쓴다. */
        problems() {
            if (!this.scheme || this.text === '') return [];
            const out = [];
            if (!this.parsed) {
                out.push({ level: 'warn', text: 'This target does not match ' + this.scheme.format + '.' });
                return out;
            }
            this.scheme.check.call(this.scheme, this.v, this.ctx, this.text).forEach(p => out.push(p));
            if (this.opts.checkShared === false) return out;
            const others = this.ctx.siblings.filter(s => s.id !== this.ctx.selfId
                && String(s.target || '').trim() === this.text && this.opts.device.protocol !== 'DB');
            if (others.length) {
                const strict = this.scheme.duplicate === 'error';
                out.push({ level: strict ? 'error' : 'warn',
                    text: 'Also used by simulation "' + others[0].name + '"' + (others.length > 1 ? ' and ' + (others.length - 1) + ' more' : '') + '.'
                        + (strict ? ' A tag can be registered once per device.' : '') });
            }
            return out;
        }

        // ── 화면 ────────────────────────────────────────────────

        render() {
            if (!this.el) return;
            if (!this.scheme) {
                this.el.innerHTML = '';
                this.el.classList.add('is-empty');
                return this.renderStatus();
            }
            const parts = this.scheme.parts.call(this.scheme, this.v, this.ctx);
            const proto = this.opts.device.protocol;
            const focus = document.activeElement && this.el.contains(document.activeElement) ? document.activeElement.dataset.key : null;

            let html = '<div class="ta-parts">';
            parts.forEach(p => { html += this.partHtml(p); });
            if (proto === 'DB') html += this.chipsHtml();
            html += '</div>';
            this.el.innerHTML = html;
            this.el.classList.toggle('is-unparsed', !this.parsed);
            this.el.classList.toggle('is-db', proto === 'DB');

            if (focus) {
                const f = this.el.querySelector('[data-key="' + focus + '"]');
                if (f) {
                    f.focus();
                    if (f.setSelectionRange && f.type === 'text') f.setSelectionRange(f.value.length, f.value.length);
                }
            }
            this.renderStatus();
        }

        partHtml(p) {
            const value = this.parsed ? (this.v[p.key] == null ? '' : String(this.v[p.key])) : '';
            const style = p.width ? ' style="width:' + p.width + 'px"' : '';
            let control;
            if (p.kind === 'select') {
                let groups = '', lastGroup = null;
                p.options.forEach(o => {
                    if (o.group !== lastGroup) {
                        if (lastGroup) groups += '</optgroup>';
                        if (o.group) groups += '<optgroup label="' + esc(o.group) + '">';
                        lastGroup = o.group || null;
                    }
                    groups += '<option value="' + esc(o.v) + '"' + (o.v === value ? ' selected' : '')
                        + (o.disabled ? ' disabled' : '') + '>' + esc(o.t) + '</option>';
                });
                if (lastGroup) groups += '</optgroup>';
                // 나눌 수 없는 target 이면 어느 값도 고르지 않은 상태로 둔다
                const blank = !this.parsed || (value === '' && !p.options.some(o => o.v === ''))
                    ? '<option value="" selected disabled>—</option>' : '';
                // 두 벌을 같이 단다. custom-select 는 Bootstrap 4, form-select 는 5 의 이름이다.
                // 두 테마가 섞여 있는 동안 어느 화면에 올라가도 모양이 나오게 한다
                control = '<select class="custom-select custom-select-sm form-select form-select-sm"'
                        + ' data-key="' + p.key + '">' + blank + groups + '</select>';
            } else {
                const list = p.kind === 'combo' ? ' list="ta-list-' + p.key + '"' : '';
                const numeric = p.kind === 'number' ? ' inputmode="numeric"' : '';
                control = '<input type="text" class="form-control form-control-sm" data-key="' + p.key + '" value="' + esc(value) + '"'
                    + list + numeric + ' placeholder="' + esc(p.placeholder || '') + '" autocomplete="off" spellcheck="false">';
                if (p.kind === 'combo') {
                    control += '<datalist id="ta-list-' + p.key + '">'
                        + p.options.map(o => '<option value="' + esc(o.v) + '">' + esc(o.t) + '</option>').join('') + '</datalist>';
                }
                if (p.next) {
                    // Bootstrap 5 는 버튼을 input-group 안에 바로 넣는다. 4 의 .input-group-append 는 없어졌고,
                    // 그 래퍼를 그대로 두면 5 에서 버튼이 칸 밖으로 떨어져 높이도 어긋난다.
                    // 4 에서도 바로 넣는 쪽이 동작한다 — 모서리만 css 에서 잡아 준다
                    control = '<div class="input-group input-group-sm">' + control
                        + '<button type="button" class="btn btn-outline-secondary" data-act="next"'
                        + ' title="Pick the first unused value after the other simulations of this device">Next free</button></div>';
                }
            }
            return '<label class="ta-part"' + style + '><span class="ta-label">' + esc(p.label) + '</span>' + control + '</label>';
        }

        chipsHtml() {
            const ids = this.ctx.policyIds().filter(Boolean);
            let html = '<div class="ta-part ta-chips"><span class="ta-label">Insert a policy value</span><div>';
            if (!ids.length) html += '<span class="ta-muted">Policies are set in the next step.</span>';
            ids.forEach(id => {
                html += '<button type="button" class="btn btn-sm ta-chip" data-act="insert" data-id="' + esc(id) + '">${' + esc(id) + '}</button>';
            });
            return html + '</div></div>';
        }

        renderStatus() {
            const el = this.opts.statusEl;
            if (!el) return;
            if (!this.scheme) { el.innerHTML = ''; return; }
            const problems = this.problems();
            const lines = [];
            if (this.text === '') {
                lines.push({ level: 'hint', text: this.opts.device.protocol === 'DB'
                    ? 'Type the SQL above. A policy chip inserts its placeholder at the cursor.'
                    : 'Pick the parts above, or type the target as ' + this.scheme.format + '.' });
            } else if (!problems.some(p => p.level === 'error') && this.parsed && this.opts.device.protocol !== 'DB') {
                lines.push({ level: 'ok', text: this.okText() });
            }
            problems.forEach(p => lines.push(p));
            el.innerHTML = lines.map(l => '<div class="ta-line is-' + l.level + '">' + esc(l.text) + '</div>').join('');
        }

        okText() {
            const bits = [];
            const note = this.scheme.note ? this.scheme.note.call(this.scheme, this.v, this.ctx) : null;
            if (note) bits.push(note);
            const types = global.CtsDataTypes ? global.CtsDataTypes.typesFor(this.opts.device.protocol, this.text) : [];
            // 타입이 좁혀질 때만 알린다. 셋(INTEGER·FLOAT·BINARY)이면 레지스터가 아닌 프로토콜의 기본 목록이다
            if (types.length && types.length <= 2) bits.push('Data type: ' + types.join(', '));
            return bits.join('  ·  ') || 'Looks good.';
        }

        // ── 입력 ────────────────────────────────────────────────

        onPart(e, committed) {
            const key = e.target.dataset && e.target.dataset.key;
            if (!key || !this.scheme) return;
            const isSelect = e.target.tagName === 'SELECT';
            // select 는 change 만, 입력칸은 input 마다 반영한다(change 는 이미 반영한 값이다)
            if (isSelect !== committed) return;
            let value = e.target.value;
            const part = this.scheme.parts.call(this.scheme, this.v, this.ctx).filter(p => p.key === key)[0];
            if (part && part.kind === 'number' && /\D/.test(value)) {
                value = value.replace(/\D/g, '');
                e.target.value = value;
            }
            if (!this.parsed) {
                // 나눌 수 없던 target 을 조각으로 다시 만들기 시작한다
                this.v = this.scheme.defaults(this.ctx);
                this.parsed = true;
            }
            this.v[key] = value;
            if (this.scheme.onPick) this.scheme.onPick(this.v, key, this.ctx);
            this.emit();
            if (isSelect) this.render(); else this.renderStatus();
        }

        onClick(e) {
            const btn = e.target.closest('[data-act]');
            if (!btn || !this.scheme) return;
            if (btn.dataset.act === 'insert') {
                if (this.opts.insertText) this.opts.insertText('${' + btn.dataset.id + '}');
                return;
            }
            if (btn.dataset.act === 'next') {
                if (!this.parsed) { this.v = this.scheme.defaults(this.ctx); this.parsed = true; }
                const n = this.scheme.next.call(this.scheme, this.v, this.ctx);
                this.v[n.key] = String(n.value);
                this.emit();
                this.render();
            }
        }

        emit() {
            this.text = this.scheme.build.call(this.scheme, this.v, this.ctx).trim();
            if (this.opts.onChange) this.opts.onChange(this.text);
        }
    }

    global.CtsTargetAid = {
        mount: (el, opts) => new TargetAid(el, opts),
        /** 화면 없이 target 하나의 문제만 본다. 표의 모든 행을 검증할 때 쓴다. opts 는 mount 와 같다. */
        check(opts, text) {
            const aid = new TargetAid(null, opts);
            aid.setValue(text);
            return aid.problems();
        },
        /** target 을 조각으로 나눠 직접 쓸 때(등록 현황 지도). 모르는 프로토콜이면 null. */
        scheme: p => SCHEMES[p] || null,
        bytesOf: bytesOf,
        BACNET_INSTANCE_MAX: BACNET_INSTANCE_MAX,
    };
})(window);
