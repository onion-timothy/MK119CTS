package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

/**
 * ServerData 값이 변경될 때의 세팅 경로를 표시한다.
 */
public enum ServerDataChangeSource {
    /** 정상 시뮬레이션 값 세팅. lock 대상이면 skip. */
    VALUE_SIMULATION,
    /** SIMULATION 모드 에러로 인한 값 세팅. lock 대상이면 skip. */
    ERROR_SIMULATION,
    /** 프로토콜 제어 수용(외부 write). lock 무시. */
    PROTOCOL_WRITE,
    /** 서버데이터 편집 API(WebSocket) 를 통한 수동 편집. lock 을 자동 세팅한다. */
    API_WRITE
}
