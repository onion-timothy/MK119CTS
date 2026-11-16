/**
 * Bootstrap 5 모달을 부르는 쪽. jQuery 플러그인 API 가 없어진 자리를 메운다.
 *
 *     $('#m').modal('show')                 →  CtsModal.show('#m')
 *     $('#m').modal('hide')                 →  CtsModal.hide('#m')
 *     $('#m').one('hidden.bs.modal', fn)    →  CtsModal.onHidden('#m', fn)
 *
 * 마지막 것이 특히 중요하다. Bootstrap 4 는 jQuery 로 이벤트를 쐈지만 5 는 네이티브 이벤트고,
 * 그 타입이 통째로 "hidden.bs.modal" 이다. jQuery 는 점 뒤를 네임스페이스로 읽으므로
 * $(x).one('hidden.bs.modal', fn) 은 영영 걸리지 않는다 — 조용히 안 되는 쪽이라 더 나쁘다.
 */
(function (global) {
    function el(target) {
        return typeof target === 'string'
            ? document.querySelector(target.charAt(0) === '#' || target.charAt(0) === '.' ? target : '#' + target)
            : (target || null);
    }

    /**
     * Modal 클래스를 찾는다.
     *
     * tabler.min.js 는 Bootstrap 5 를 품고 있지만 **window.bootstrap 을 내보내지 않는다** —
     * 제 이름 아래(window.tabler)에만 둔다. 그래서 global.bootstrap.Modal 로 바로 가면
     * TypeError 가 나고, 모달이 그냥 안 열린다. 세 자리를 차례로 본다.
     */
    function Modal() {
        const t = global.tabler;
        return (global.bootstrap && global.bootstrap.Modal)
            || (t && t.bootstrap && t.bootstrap.Modal)
            || (t && t.Modal)
            || null;
    }

    function instance(target) {
        const node = el(target);
        const M = Modal();
        return (node && M) ? M.getOrCreateInstance(node) : null;
    }

    function show(target) {
        const m = instance(target);
        if (m) m.show();
    }

    /** 떠 있지 않으면 아무 일도 하지 않는다 (getInstance 가 null 이면 띄운 적이 없는 것이다). */
    function hide(target) {
        const node = el(target);
        const M = Modal();
        if (!node || !M) return;
        const m = M.getInstance(node);
        if (m) m.hide();
    }

    /** 닫힌 뒤 한 번만 부른다. once 를 false 로 주면 계속 듣는다. */
    function onHidden(target, fn, once) {
        const node = el(target);
        if (node) node.addEventListener('hidden.bs.modal', fn, { once: once !== false });
    }

    global.CtsModal = { show: show, hide: hide, onHidden: onHidden, instance: instance };
})(window);
