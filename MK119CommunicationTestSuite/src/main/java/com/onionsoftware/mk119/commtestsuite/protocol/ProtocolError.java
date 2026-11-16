package com.onionsoftware.mk119.commtestsuite.protocol;

/**
 * 엔진의 실패. 세 갈래다.
 * <ul>
 *   <li>{@link Runtime} — 실행 중 오류(0 으로 나눔, 범위 넘침 …). 그 요청에 응답하지 않고 로그를 남긴다</li>
 *   <li>{@link NoMatch} — 받을 때 "이 메시지가 아님". 오류가 아니라 다음 메시지를 시도하라는 뜻</li>
 *   <li>{@link NeedMore} — 받을 때 바이트가 모자람. 스트림이면 더 기다린다</li>
 * </ul>
 * 매칭은 이 예외를 흐름 제어로 자주 던지므로 스택을 채우지 않는다.
 */
public class ProtocolError extends RuntimeException {
    protected ProtocolError(String message) {
        super(message, null, false, false);
    }

    public static final class Runtime extends ProtocolError {
        public Runtime(String message) { super(message); }
    }

    public static final class NoMatch extends ProtocolError {
        public NoMatch(String message) { super(message); }
    }

    public static final class NeedMore extends ProtocolError {
        public static final NeedMore INSTANCE = new NeedMore();
        private NeedMore() { super("need more bytes"); }
    }

    public static Runtime runtime(String format, Object... args) {
        return new Runtime(args.length == 0 ? format : String.format(format, args));
    }

    public static NoMatch noMatch(String format, Object... args) {
        return new NoMatch(args.length == 0 ? format : String.format(format, args));
    }
}
