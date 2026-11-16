/**
 * 장비의 프로토콜 정의와 통신 설정 칸. device_editor 와 batchAddDevices 가 같이 쓴다.
 * 칸 마크업은 fragments/device-form.html :: settings 이고, 이 파일이 그 칸을 채우고 읽고 검사한다.
 *
 * 규칙의 기준은 편집기다. 서버(DeviceSettingsValidator)가 같은 규칙·문구를 따른다 —
 * 고칠 때 함께 고치고, 사례 표(src/test/resources/device-settings-cases.json)로 둘을 맞춰 본다.
 *
 *     CtsDeviceForm.problems('MODBUS', cs)   // → [{ key, id, message }]  DOM 없이 돈다
 *     CtsDeviceForm.definitionProblems('GENERIC', cs, def)   // 프로토콜 정의·파라미터. def = 고른 정의 {id, name, params} (모르면 null)
 *     CtsDeviceForm.mount()                  // 고르는 칸의 선택지와 슬라이더를 붙인다 (화면마다 한 번)
 *     CtsDeviceForm.read('MODBUS')           // 칸 → cs
 *     CtsDeviceForm.fill('MODBUS', cs)       // cs → 칸. 값이 없는 칸은 그대로 둔다 (편집기)
 *     CtsDeviceForm.show('MODBUS', cs)       // cs → 칸. 값이 없으면 비우고, 칸에 맞지 않는 값도 보여 준다 (일괄 등록)
 */
(function (global) {
    const INT_MAX = 2147483647;

    /**
     * 프로토콜마다 쓸 수 있는 전송 계층과 기본 포트.
     * 전송 계층 조합은 ApiController.TRANSPORT_PROTOCOLS 와 같다 — 고칠 때 함께 고친다.
     */
    const PROTOCOLS = {
        // definition: 프로토콜 정의를 가리킨다 (docs/PROTOCOL_ENGINE_DESIGN.md 10.1). GENERIC 은 반드시, RCU 는 RS 를 쓸 때만
        GENERIC:     { text: 'Generic',      transports: ['TCP', 'UDP'], definition: 'required',
                       note: 'Speaks the messages of a protocol definition.' },
        MODBUS:      { text: 'Modbus',       transports: ['TCP', 'UDP'], port: { TCP: 502, UDP: 502 },
                       note: 'Modbus slave. Registers sets the address count of each table.' },
        XGT_PLC:     { text: 'LSIS-XGT',     transports: ['TCP', 'UDP'], port: { TCP: 2004, UDP: 2005 },
                       note: 'LS XGT PLC over FEnet.' },
        DAEYUN_RCU:  { text: 'MK RCU',       transports: ['TCP'], port: { TCP: 1470 }, definition: 'optional',
                       note: 'MK RCU. A protocol definition answers the serial (RS) pass-through.' },
        SNMP:        { text: 'SNMP',         transports: ['UDP'], port: { UDP: 161 },
                       note: 'SNMP agent.' },
        BACNET:      { text: 'BACnet',       transports: ['UDP'],
                       note: 'BACnet/IP device. Every BACnet device shares 0.0.0.0:47808/UDP.' },
        OPC_AGENT:   { text: 'MK OPC Agent', transports: ['UDP'], port: { UDP: 9999 },
                       note: 'MK OPC Agent.' },
        DB:          { text: 'DB',           transports: ['TCP', 'UDP'],
                       note: 'Writes simulated values into a database over JDBC.' },
        ETHERNET_IP: { text: 'Ethernet/IP',  transports: ['TCP'], port: { TCP: 44818 },
                       note: 'EtherNet/IP adapter with explicit and implicit messaging.' },
    };
    const PROTOCOL_ORDER = ['GENERIC', 'MODBUS', 'XGT_PLC', 'DAEYUN_RCU', 'SNMP', 'BACNET',
                            'OPC_AGENT', 'DB', 'ETHERNET_IP'];

    /**
     * 프로토콜별 통신 설정. key 는 communicationSettings 의 이름, id 는 입력칸.
     * Generic 은 프로토콜 정의와 그 파라미터뿐이라 여기에 없다 — definitionProblems 가 본다.
     *   options : 고르는 칸. 편집기에서는 비어 있을 수 없지만 파일에서 온 값은 틀릴 수 있다
     *   range   : 슬라이더(CtsRange). 정수로 저장한다
     *   min/max : 정수 칸
     *   ipv4    : IPv4 주소
     */
    const SETTINGS = {
        MODBUS: [
            { key: 'unitId', id: 'mb-unit', label: 'Unit ID', min: 0, max: 255 },
            { key: 'packetType', id: 'mb-packet', label: 'Packet type',
              options: [{ v: '0', t: 'Modbus-RTU' }, { v: '1', t: 'Modbus-TCP' }] },
            { key: 'registers', id: 'mb-registers', label: 'Registers', min: 1, max: INT_MAX },
        ],
        XGT_PLC: [
            { key: 'cpuId', id: 'xgt-cpu', label: 'CPU ID', min: 0, max: 255 },
            { key: 'maxBlockSize', id: 'xgt-block', label: 'Max block size', min: 1, max: 1400 },
            { key: 'registers', id: 'xgt-registers', label: 'Registers', min: 1, max: INT_MAX },
        ],
        DAEYUN_RCU: [
            { key: 'cpuAddr', id: 'rcu-cpu', label: 'CPU address', min: 0, max: 255 },
            { key: 'diCount', id: 'rcu-di', label: 'DI channels', range: true, min: 1, max: 32 },
            { key: 'doCount', id: 'rcu-do', label: 'DO channels', range: true, min: 1, max: 32 },
            { key: 'aiCount', id: 'rcu-ai', label: 'AI channels', range: true, min: 1, max: 8 },
        ],
        SNMP: [
            { key: 'snmpVersion', id: 'snmp-version', label: 'Version',
              options: [{ v: '0', t: 'SNMPv1' }, { v: '1', t: 'SNMPv2' }] },
            { key: 'getCommunity', id: 'snmp-get', label: 'Get community' },
            { key: 'setCommunity', id: 'snmp-set', label: 'Set community' },
        ],
        BACNET: [
            { key: 'deviceInstanceId', id: 'bac-instance', label: 'Device instance', min: 1, max: 65536 },
            { key: 'broadcast', id: 'bac-broadcast', label: 'Broadcast address' },
        ],
        OPC_AGENT: [
            { key: 'registers', id: 'opc-tags', label: 'Tags', min: 1, max: INT_MAX },
        ],
        DB: [
            { key: 'dbdriver', id: 'db-driver', label: 'Driver' },
            { key: 'dbUrl', id: 'db-url', label: 'URL' },
            { key: 'dbUsername', id: 'db-user', label: 'Username' },
            { key: 'dbPassword', id: 'db-password', label: 'Password' },
            { key: 'dbDatabase', id: 'db-name', label: 'Database' },
        ],
        ETHERNET_IP: [
            { key: 'cpuSlot', id: 'eip-slot', label: 'CPU slot', min: 0, max: 255 },
            // EthernetIPData 가 Short.parseShort 로 읽어서 상한이 32767 이다
            { key: 'syn_port', id: 'eip-syn', label: 'Implicit SYN port', min: 0, max: 32767 },
            { key: 'implicit_t2o_addr', id: 'eip-t2o-addr', label: 'T→O address', ipv4: true },
            { key: 'implicit_t2o_assy_size', id: 'eip-t2o-size', label: 'T→O assembly size', min: 0, max: INT_MAX },
            { key: 'implicit_o2t_assy_size', id: 'eip-o2t-size', label: 'O→T assembly size', min: 0, max: INT_MAX },
        ],
    };

    // ── 검사 (DOM 없음) ─────────────────────────────────────

    function isIpv4(v) {
        const parts = String(v).trim().split('.');
        return parts.length === 4 && parts.every(p => /^\d{1,3}$/.test(p) && +p <= 255);
    }

    function rangeText(s) {
        if (s.max != null) return ' from ' + s.min + ' to ' + s.max;
        if (s.min != null) return ' of ' + s.min + ' or more';
        return '';
    }

    function optionText(s) {
        const t = s.options.map(o => o.t);
        return t.slice(0, -1).join(', ') + ' or ' + t[t.length - 1];
    }

    /** 칸 하나의 문제. 없으면 null */
    function problemOf(s, value) {
        const v = value == null ? '' : String(value);
        if (v.trim() === '') return s.label + ' cannot be empty.';
        if (s.options) {
            return s.options.some(o => o.v === v.trim()) ? null : s.label + ' must be ' + optionText(s) + '.';
        }
        if (s.min != null) {
            const n = Number(v.trim());
            if (!/^-?\d+$/.test(v.trim()) || n < s.min || (s.max != null && n > s.max)) {
                return s.label + ' must be a whole number' + rangeText(s) + '.';
            }
        }
        if (s.ipv4 && !isIpv4(v)) return s.label + ' must be an IPv4 address.';
        return null;
    }

    /** 이 프로토콜의 설정 칸 문제를 칸 순서대로. 편집기는 첫 것만 알린다 */
    function problems(protocol, cs) {
        const out = [];
        (SETTINGS[protocol] || []).forEach(function (s) {
            const message = problemOf(s, cs ? cs[s.key] : null);
            if (message) out.push({ key: s.key, id: s.id, message: message });
        });
        return out;
    }

    /**
     * 프로토콜 정의와 파라미터. 서버(DeviceSettingsValidator.definitionProblems)와 같은 규칙·문구다.
     * def 는 고른 정의 { id, name, params: { 이름: { type, default, min, max } } }. 모르면 null — 그때는 id 모양만 본다.
     * 결과 id: 'def-select' 또는 'def-param-<이름>'
     */
    function definitionProblems(protocol, cs, def) {
        const info = PROTOCOLS[protocol];
        if (!info || !info.definition) return [];
        const out = [];
        const raw = cs ? cs.definitionId : null;
        const idText = raw == null ? '' : String(raw).trim();
        if (idText === '') {
            if (info.definition === 'required') out.push({ key: 'definitionId', id: 'def-select', message: 'Choose a protocol definition.' });
            return out;
        }
        if (!/^-?\d+$/.test(idText)) {
            out.push({ key: 'definitionId', id: 'def-select', message: 'Protocol definition must be the id of a saved definition.' });
            return out;
        }
        if (!def) return out;
        const params = cs && cs.params && typeof cs.params === 'object' ? cs.params : {};
        const declared = def.params || {};
        Object.keys(params).forEach(function (k) {
            if (!Object.prototype.hasOwnProperty.call(declared, k)) {
                out.push({ key: 'params', id: 'def-select', message: k + ' is not a parameter of "' + def.name + '".' });
            }
        });
        Object.keys(declared).forEach(function (n) {
            const p = declared[n] || {};
            const v = params[n];
            const t = v == null ? '' : String(v).trim();
            if (t === '') {
                if (p.default == null) out.push({ key: 'params', id: 'def-param-' + n, message: 'Parameter ' + n + ' needs a value.' });
                return;
            }
            const m = paramProblem(n, p, t);
            if (m) out.push({ key: 'params', id: 'def-param-' + n, message: m });
        });
        return out;
    }

    /** integer(기본)면 정수, 선언의 min·max 안 — DeviceSettingsValidator.paramProblem 과 같다 (형식 11) */
    function paramProblem(name, p, t) {
        if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(t)) return 'Parameter ' + name + ' must be a number.';
        const v = Number(t);
        const real = p.value === 'float';
        if (!real && v !== Math.round(v)) return 'Parameter ' + name + ' must be a whole number.';
        const hasMin = p.min != null, hasMax = p.max != null;
        const lo = hasMin ? Number(p.min) : -Number.MAX_VALUE, hi = hasMax ? Number(p.max) : Number.MAX_VALUE;
        if (v < lo || v > hi) {
            if (hasMin && hasMax) return 'Parameter ' + name + ' must be from ' + fmtNum(lo) + ' to ' + fmtNum(hi) + '.';
            return 'Parameter ' + name + ' must be ' + (hasMin ? 'at least ' + fmtNum(lo) : 'at most ' + fmtNum(hi)) + '.';
        }
        return null;
    }
    function fmtNum(n) { return Math.abs(n) >= 1e21 ? String(n) : String(+n); }

    /** 편집기가 저장하는 모양으로. 이 프로토콜의 키만, 슬라이더는 정수, 나머지는 글자 */
    function forSave(protocol, cs) {
        const out = {};
        (SETTINGS[protocol] || []).forEach(function (s) {
            const v = cs ? cs[s.key] : null;
            if (v == null) return;
            out[s.key] = s.range ? parseInt(v, 10) : String(v);
        });
        return out;
    }

    function setting(protocol, id) {
        return (SETTINGS[protocol] || []).filter(s => s.id === id)[0] || null;
    }

    // ── 칸 (DOM) ────────────────────────────────────────────

    function el(id) { return document.getElementById(id); }

    function eachSetting(fn) {
        Object.keys(SETTINGS).forEach(p => SETTINGS[p].forEach(s => fn(s, p)));
    }

    function mount() {
        eachSetting(function (s) {
            const node = el(s.id);
            if (!node) return;
            if (s.options) {
                node.innerHTML = '';
                s.options.forEach(o => node.appendChild(new Option(o.t, o.v)));
            }
            if (s.range) CtsRange.bind(s.id, '');
        });
    }

    function read(protocol) {
        const cs = {};
        (SETTINGS[protocol] || []).forEach(function (s) {
            cs[s.key] = s.range ? CtsRange.get(s.id) : el(s.id).value;
        });
        return cs;
    }

    function fill(protocol, cs) {
        (SETTINGS[protocol] || []).forEach(function (s) {
            const v = cs ? cs[s.key] : null;
            if (v == null) return;
            if (s.range) CtsRange.set(s.id, Math.round(Number(v)));
            else el(s.id).value = v;
        });
    }

    /**
     * 파일 값을 그대로 보여 준다. 고르는 칸에 없는 값은 맨 앞 선택지로 남기고,
     * 슬라이더가 담을 수 없는 값은 옆 값 표시에 그대로 적는다 — 검사가 오류로 알린다.
     */
    function show(protocol, cs) {
        (SETTINGS[protocol] || []).forEach(function (s) {
            const node = el(s.id);
            const v = cs ? cs[s.key] : null;
            const text = v == null ? '' : String(v);
            if (s.options) {
                $(node).find('option.is-extra').remove();
                const known = s.options.some(o => o.v === text.trim());
                if (!known) {
                    const extra = new Option(text === '' ? 'Select…' : text, text);
                    extra.className = 'is-extra';
                    node.insertBefore(extra, node.firstChild);
                }
                node.value = text.trim() === '' || !known ? text : text.trim();
            } else if (s.range) {
                const n = Number(text);
                const fits = text.trim() !== '' && /^-?\d+$/.test(text.trim()) && n >= s.min && n <= s.max;
                if (fits) CtsRange.set(s.id, n);
                else CtsRange.set(s.id, Math.min(Math.max(isFinite(n) ? n : s.min, s.min), s.max));
                const out = el(s.id + '-val');
                if (out && !fits) out.textContent = text.trim() === '' ? '—' : text;
                if (out) out.classList.toggle('text-danger', !fits);
            } else {
                node.value = text;
            }
        });
    }

    /** 문제 칸에 is-invalid 를 건다. 이 프로토콜의 칸만 본다 */
    function mark(protocol, list) {
        const bad = {};
        (list || []).forEach(x => { bad[x.id] = true; });
        (SETTINGS[protocol] || []).forEach(function (s) {
            const node = el(s.id);
            if (node) node.classList.toggle('is-invalid', !!bad[s.id]);
        });
    }

    global.CtsDeviceForm = {
        PROTOCOLS: PROTOCOLS, PROTOCOL_ORDER: PROTOCOL_ORDER, SETTINGS: SETTINGS,
        problems: problems, definitionProblems: definitionProblems, forSave: forSave, setting: setting,
        mount: mount, read: read, fill: fill, show: show, mark: mark,
    };
})(window);
