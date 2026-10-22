package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

/**
 * ServerData 값이 변경될 때의 세팅 경로를 표시한다.
 * <p>
 * lock 을 건 경로는 그 lock 의 authority 가 된다. 순위는
 * {@code API_WRITE > PROTOCOL_WRITE > ERROR_SIMULATION > VALUE_SIMULATION} 이다.
 * VALUE_SIMULATION 은 lock 을 걸지 않으므로 authority 로 나타나지 않는다.
 */
public enum ServerDataChangeSource {
    /** 정상 시뮬레이션 값 세팅. lock 대상이면 skip. */
    VALUE_SIMULATION(0),
    /** SIMULATION 모드 에러로 인한 값 세팅. lock 대상이면 skip. simulation_stop 의 lock 을 건다. */
    ERROR_SIMULATION(1),
    /** 프로토콜 제어 수용(외부 write). API_WRITE 의 lock 에만 막힌다. stopOnControl 의 lock 을 건다. */
    PROTOCOL_WRITE(2),
    /** 서버데이터 편집 API(REST, 편집 세션, Test Plan). 어떤 lock 에도 막히지 않는다. */
    API_WRITE(3);

    private final int authority;

    ServerDataChangeSource(int authority) {
        this.authority = authority;
    }

    /** lock authority 순위. 클수록 높다. */
    public int authority() {
        return authority;
    }

    /**
     * 이 경로의 쓰기가 {@code lockAuthority} 가 건 lock 에 막히는지.
     * <p>
     * 시뮬레이션 계열은 어떤 lock 에도 막힌다. lock 의 본래 목적이 시뮬레이션 정지이기 때문이다.
     * 그 밖의 경로는 자기보다 높은 authority 의 lock 에만 막힌다.
     */
    public boolean isBlockedBy(ServerDataChangeSource lockAuthority) {
        if (this == VALUE_SIMULATION || this == ERROR_SIMULATION) return true;
        return lockAuthority.authority > this.authority;
    }
}
