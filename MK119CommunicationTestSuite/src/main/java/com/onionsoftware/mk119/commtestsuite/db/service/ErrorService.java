package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.repository.ErrorRepository;
import com.onionsoftware.mk119.commtestsuite.simulation.ErrorJobFactory;
import com.onionsoftware.mk119.commtestsuite.simulation.JitteredTrigger;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Service
@RequiredArgsConstructor
public class ErrorService {
    final ErrorRepository errorRepository;
    final ThreadPoolTaskScheduler threadPoolTaskScheduler;
    final ErrorJobFactory errorJobFactory;

    /** Key: errorId, Value: ScheduledFuture */
    private final Map<Integer, ScheduledFuture<?>> errorSchedules = new HashMap<>();

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances ioServerInstances;

    // ── 기존 CRUD ──

    public List<Error> selectErrorsByDeviceId(Integer device_id) {
        return errorRepository.findErrorsByDeviceId(device_id);
    }

    public List<Error> selectEnabledErrorsByDeviceId(Integer device_id) {
        return errorRepository.findErrorsByDeviceIdAndEnabled(device_id, Error.ERROR_ENABLED);
    }

    public Error selectErrorById(Integer error_id) {
        return errorRepository.findById(error_id).orElseThrow(EntityNotFoundException::new);
    }

    public void insertError(Error error) {
        errorRepository.save(error);
    }

    public void updateError(Error error) {
        errorRepository.save(error);
    }

    public void deleteError(Integer device_id) {
        errorRepository.deleteById(device_id);
    }

    public boolean getErrorEnabled(Integer id) {
        return errorRepository.findById(id).orElseThrow(EntityNotFoundException::new)
                .getEnabled() == Error.ERROR_ENABLED;
    }

    // ── 에러 시뮬레이션 스케줄링 ──

    /**
     * Error를 스케줄에 등록한다.
     * mode에 따라 다른 Runnable이 스케줄되며 (SIMULATION vs 시스템 에러) 동일한 interval 파이프라인을 사용한다.
     */
    public void startErrorSchedule(Error error) {
        if (error.getEnabled() != Error.ERROR_ENABLED)
            return;
        if (error.getInterval() <= 0)
            return;

        stopErrorSchedule(error.getId());

        int deviceId = error.getDevice().getId();
        Runnable task = errorJobFactory.create(error);

        // 에러 판정도 ServerData 를 건드리므로 시뮬레이션과 같은 이유로 첫 실행을 흩는다.
        PeriodicTrigger trigger = JitteredTrigger.ofSeconds(error.getInterval());
        ScheduledFuture<?> future = threadPoolTaskScheduler.schedule(task, trigger);
        errorSchedules.put(error.getId(), future);

        MkLogger.log("simulation", LogLevel.INFO,
                "Error schedule started: error {0} on device {1}, mode={2}, interval={3}s, frequency={4}%",
                error.getId(), deviceId, error.getMode(), error.getInterval(), error.getFrequency());
    }

    /**
     * 에러 스케줄을 중지한다.
     */
    public void stopErrorSchedule(Integer errorId) {
        ScheduledFuture<?> future = errorSchedules.remove(errorId);
        if (future != null) {
            future.cancel(true);
            MkLogger.log("simulation", LogLevel.INFO, "System error schedule stopped: error {0}", errorId);
        }

        try {
            Error error = selectErrorById(errorId);
            if (error != null && error.getDevice() != null) {
                ioServerInstances.getServerInstance(error.getDevice().getId()).ifPresent(server -> {
                    if (server.getActiveSystemError() != null && server.getActiveSystemError().getId().equals(errorId)) {
                        server.deactivateSystemError();
                    }
                });
            }
        } catch (Exception e) {
            // Ignore if error not found or deleted
        }
    }

    /**
     * 디바이스의 모든 활성 에러 스케줄을 시작한다 (SIMULATION 포함).
     * 디바이스 시작 시 호출된다.
     */
    public void startAllErrorSchedules(Integer deviceId) {
        List<Error> errors = selectEnabledErrorsByDeviceId(deviceId);
        for (Error error : errors) {
            startErrorSchedule(error);
        }
    }

    /**
     * 디바이스의 모든 에러 스케줄을 중지한다.
     * 디바이스 중지 시 호출된다.
     */
    public void stopAllErrorSchedules(Integer deviceId) {
        List<Error> errors = selectErrorsByDeviceId(deviceId);
        for (Error error : errors) {
            stopErrorSchedule(error.getId());
        }
    }
}
