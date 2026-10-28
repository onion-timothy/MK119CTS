package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;

import java.util.Set;

@FunctionalInterface
public interface ServerDataListener {
    void onServerDataChanged(ServerDataEvent event);

    /**
     * lock 칸 집합이 바뀌었다. 쓰기·정지·해제·세션 종료는 그 자리에서, 만료는 만료 시각에 온다.
     *
     * @param locks 지금 잠긴 칸 전체
     */
    default void onLocksChanged(IOServer ioServer, Set<String> locks) {
    }
}
