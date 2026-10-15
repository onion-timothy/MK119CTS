package com.onionsoftware.mk119.commtestsuite.simulation;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import lombok.RequiredArgsConstructor;

/**
 * 개별 에러의 확률 판정 및 시스템 에러 발생을 처리하는 Runnable Job.
 * ErrorJobFactory를 통해 인스턴스가 생성되며, ErrorService에서 스케줄링된다.
 */
@RequiredArgsConstructor
public class ErrorJob implements Runnable {
    private final Error error;
    private final IOServerInstances ioServerInstances;

    @Override
    public void run() {
        int deviceId = error.getDevice().getId();
        ioServerInstances.getServerInstance(deviceId).ifPresent(server -> {
            // 이미 시스템 에러가 활성 중이면 건너뛴다
            if (server.isSystemErrorActive()) {
                return;
            }
            if (error.shouldFire()) {
                int duration = error.getErrorDuration() > 0 ? error.getErrorDuration() : 30;
                server.activateSystemError(error, duration);
            }
        });
    }
}
