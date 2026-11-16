/**
 * css/cts-tabler.css 의 .cts-range 슬라이더를 움직이는 쪽.
 * 지나온 구간(--p)과 레이블 옆 값 표시를 지금 값에 맞춘다.
 *
 *     CtsRange.bind('frequency', '%');     // #frequency 와 #frequency-val 을 묶는다
 *     CtsRange.get('frequency');           // 정수
 *     CtsRange.set('frequency', 40);       // 값이 null 이면 마크업 기본값을 그대로 둔다
 */
(function (global) {
    const painters = {};

    function bind(id, unit) {
        const el = document.getElementById(id);
        const out = document.getElementById(id + '-val');
        if (!el) return;
        const paint = function () {
            const min = Number(el.min), max = Number(el.max);
            const p = max > min ? (Number(el.value) - min) / (max - min) * 100 : 0;
            el.style.setProperty('--p', p + '%');
            if (out) out.textContent = el.value + (unit || '');
        };
        el.addEventListener('input', paint);
        painters[id] = paint;
        paint();
    }

    /** 한 화면의 슬라이더를 한 번에. {id: unit} 꼴로 넘긴다 */
    function bindAll(map) {
        Object.keys(map).forEach(id => bind(id, map[id]));
    }

    function get(id) {
        const el = document.getElementById(id);
        return el ? parseInt(el.value, 10) : null;
    }

    function set(id, value) {
        if (value == null) return;   // 저장된 값이 없으면 마크업의 기본값을 그대로 둔다
        const el = document.getElementById(id);
        if (!el) return;
        el.value = value;
        if (painters[id]) painters[id]();
    }

    global.CtsRange = { bind: bind, bindAll: bindAll, get: get, set: set };
})(window);
