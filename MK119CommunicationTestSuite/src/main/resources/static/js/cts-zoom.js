/*
 * 큰 화면 배율(css/cts-tabler.css 의 0절)과 JS 좌표를 맞춘다. 모든 Tabler 화면의 <head> 에서 읽는다.
 *
 * zoom 이 걸린 요소 안에서는 같은 길이가 두 단위로 나온다 (Chrome 157, zoom 1.25 에서 잰 값):
 *   눈에 보이는 px (배율이 곱해진 값)   getBoundingClientRect, 마우스 clientX/Y, innerWidth/Height
 *   CSS px (배율 전 값)               style·SVG 속성에 쓰는 길이, scrollTop, offset/client/scrollWidth·Height
 * 둘을 섞어 계산하면 배율만큼 어긋난다. 잰 값을 그 요소의 배율로 나눠 CSS px 로 바꾼 뒤 쓴다.
 * 배율이 1 이면(작은 화면) 모든 함수가 원래 값을 그대로 돌려준다.
 *
 * body 는 배율이 1 이다. body 에 붙는 팝업은 눈에 보이는 px 그대로 놓인다.
 * Tabulator 안에서 둘을 섞는 곳은 js/cts-table.js 의 installZoomFixes 가 메운다.
 */
(function () {
    'use strict';

    /** el 에 실제로 걸린 배율 (조상 것까지 곱한 값) */
    function of(el) {
        return (el && el.currentCSSZoom) || 1;
    }

    /** getBoundingClientRect 를 el 의 CSS px 로 */
    function rect(el) {
        const r = el.getBoundingClientRect(), z = of(el);
        if (z === 1) return r;
        return { left: r.left / z, top: r.top / z, right: r.right / z, bottom: r.bottom / z,
                 width: r.width / z, height: r.height / z, x: r.x / z, y: r.y / z };
    }

    /** 마우스 위치를 el 의 왼쪽 위 기준 CSS px 로 */
    function point(e, el) {
        const r = el.getBoundingClientRect(), z = of(el);
        return { x: (e.clientX - r.left) / z, y: (e.clientY - r.top) / z };
    }

    window.CtsZoom = { of: of, rect: rect, point: point };
})();
