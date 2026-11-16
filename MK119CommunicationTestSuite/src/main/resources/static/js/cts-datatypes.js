/**
 * 프로토콜·target 이 받는 data type. 서버의 DataTypeRules 와 같은 표다 — 고칠 때 함께 고친다.
 * 목록의 첫 타입이 기본값이다. 판정은 서버가 다시 한다.
 */
(function (global) {
    const SIZED = ['UI8', 'SI8', 'UI16AB', 'UI16BA', 'SI16AB', 'SI16BA',
        'UI32AD', 'UI32CB', 'UI32BC', 'UI32DA', 'SI32AD', 'SI32CB', 'SI32BC', 'SI32DA',
        'SI64AH', 'SI64GB', 'SI64BG', 'SI64HA',
        'FP32AD', 'FP32CB', 'FP32BC', 'FP32DA', 'FP64AH', 'FP64GB', 'FP64BG', 'FP64HA'];
    const PLAIN = ['INTEGER', 'FLOAT', 'BINARY'];
    const ETHERNET_IP = ['SI8', 'SI16BA', 'SI32DA', 'FP32DA', 'BINARY'];
    const ALL = SIZED.concat(PLAIN);

    function bytesOf(t) {
        if (t.indexOf('8') >= 0) return 1;
        if (t.indexOf('16') >= 0) return 2;
        if (t.indexOf('32') >= 0) return 4;
        if (t.indexOf('64') >= 0) return 8;
        return 0;
    }

    function widths() {
        const w = Array.prototype.slice.call(arguments);
        return SIZED.filter(t => w.indexOf(bytesOf(t)) >= 0);
    }

    function union(a, b) {
        return a.concat(b.filter(t => a.indexOf(t) < 0));
    }

    function typesFor(protocol, target) {
        const t = String(target || '').trim();
        switch (protocol) {
            case 'MODBUS':
                if (/^[12]_/.test(t)) return ['BINARY'];
                if (/^[34]_/.test(t)) return widths(2, 4, 8);
                return union(['BINARY'], widths(2, 4, 8));
            case 'XGT_PLC': {
                const size = t.charAt(2).toUpperCase();
                if (size === 'B') return widths(1);
                if (size === 'W') return widths(2);
                if (size === 'D') return widths(4);
                return widths(1, 2, 4);
            }
            case 'ETHERNET_IP':
                return ETHERNET_IP.slice();
            case 'BACNET': {
                const lower = t.toLowerCase();
                if (lower.indexOf('binary_') === 0) return ['BINARY'];
                if (lower.indexOf('multi_state_') === 0) return ['INTEGER'];
                if (lower.indexOf('analog_') === 0) return ['FLOAT', 'INTEGER'];
                return PLAIN.slice();
            }
            case 'DAEYUN_RCU':
                if (t.indexOf('@RCUDI_') === 0) return ['BINARY'];
                if (t.indexOf('@RCUAI_') === 0) return ['INTEGER'];
                return PLAIN.slice();
            case 'SNMP':
            case 'GENERIC':
            case 'OPC_AGENT':
            case 'DB':
                return PLAIN.slice();
            default:
                return ALL.slice();
        }
    }

    global.CtsDataTypes = { ALL: ALL, typesFor: typesFor };
})(window);
