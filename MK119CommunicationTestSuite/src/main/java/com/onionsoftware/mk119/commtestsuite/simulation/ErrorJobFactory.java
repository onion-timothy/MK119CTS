package com.onionsoftware.mk119.commtestsuite.simulation;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import com.onionsoftware.mk119.commtestsuite.io.db.DBDeviceService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Error 스케줄용 Runnable을 mode에 맞춰 생성한다.
 *  - SIMULATION 모드: {@link SimulationErrorJob} — errorValue를 ServerData/SQL에 밀어넣음
 *  - 그 외(DISCARD/TIMEOUT/GARBAGE/DISCONNECT): {@link ErrorJob} — IOServer 시스템 에러 플래그 활성화
 */
@Component
@RequiredArgsConstructor
public class ErrorJobFactory {
    private final IOServerInstances ioServerInstances;
    private final DBDeviceService dbDeviceService;
    /** ErrorService ← ErrorJobFactory ← SimulationService 순환 방지용 lazy 주입. */
    @Lazy
    private final SimulationService simulationService;

    public Runnable create(Error error) {
        if (error.getMode() == ErrorMode.SIMULATION) {
            return new SimulationErrorJob(error, ioServerInstances, dbDeviceService, simulationService);
        }
        return new ErrorJob(error, ioServerInstances);
    }
}
