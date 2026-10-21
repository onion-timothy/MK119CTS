package com.onionsoftware.mk119.commtestsuite.db.service;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.repository.ErrorRepository;
import com.onionsoftware.mk119.commtestsuite.simulation.ErrorJobFactory;
import com.onionsoftware.mk119.commtestsuite.simulation.JitteredTrigger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

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
        // 디바이스 재기동이 아닌 단건 add/update 경로 — 전체 지터 창에서 랜덤.
        // 이 경로로 들어온 에러는 우선순위가 랜덤이다 (문서 참조).
        startErrorScheduleAt(error, JitteredTrigger.jitterMs((long) error.getInterval() * 1000L));
    }

    /**
     * 지정된 초기 지연(ms)으로 스케줄을 등록한다. {@link #startAllErrorSchedules} 가 로드 순서별
     * 지터 슬롯을 계산해 넘긴다. 외부 호출은 {@link #startErrorSchedule(Error)} 를 사용.
     */
    private void startErrorScheduleAt(Error error, long initialDelayMs) {
        if (error.getEnabled() != Error.ERROR_ENABLED)
            return;
        if (error.getInterval() <= 0)
            return;

        stopErrorSchedule(error.getId());

        int deviceId = error.getDevice().getId();
        Runnable task = errorJobFactory.create(error);

        // 에러 판정도 ServerData 를 건드리므로 시뮬레이션과 같은 이유로 첫 실행을 흩는다.
        PeriodicTrigger trigger = JitteredTrigger.ofSecondsWithInitialDelay(error.getInterval(), initialDelayMs);
        ScheduledFuture<?> future = threadPoolTaskScheduler.schedule(task, trigger);
        errorSchedules.put(error.getId(), future);

        LoggerFactory.getLogger("simulation").info("Error schedule started: error {} on device {}, mode={}, interval={}s, frequency={}%, initialDelay={}ms", error.getId(), deviceId, error.getMode(), error.getInterval(), error.getFrequency(), initialDelayMs);
    }

    /**
     * 에러 스케줄을 중지한다.
     */
    public void stopErrorSchedule(Integer errorId) {
        ScheduledFuture<?> future = errorSchedules.remove(errorId);
        if (future != null) {
            future.cancel(true);
            LoggerFactory.getLogger("simulation").info("System error schedule stopped: error {}", errorId);
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
     *
     * <p><b>우선순위 규칙:</b> 시스템 에러는 디바이스 당 한 번에 하나만 활성이고, 매 사이클에
     * 먼저 틱한 에러가 shouldFire 를 굴릴 기회를 갖는다. 따라서 "먼저 등록된(ID 가 작은) 에러"
     * 가 매 사이클 선권을 갖도록 지터 슬롯을 분배한다 — 전체 2s 지터 창을 N 등분해서,
     * ID 순으로 [0, w/N), [w/N, 2w/N), ... 슬롯을 할당하고 각 슬롯 안에서는 랜덤.
     * interval 보장은 유지되고 (매 사이클 60s 는 그대로), 같은 사이클 내 틱 순서만 로드 순서로 고정됨.
     *
     * <p>단건 add/update 로 들어오는 에러는 {@link #startErrorSchedule(Error)} 를 거쳐
     * 전체 창 랜덤으로 등록된다. 우선순위를 다시 정렬하려면 디바이스를 재기동해야 한다.
     */
    public void startAllErrorSchedules(Integer deviceId) {
        List<Error> errors = selectEnabledErrorsByDeviceId(deviceId);
        errors.sort(Comparator.comparingInt(Error::getId));
        int n = errors.size();
        long window = JitteredTrigger.MAX_JITTER_MS;
        long slot = n > 0 ? Math.max(1L, window / n) : window;
        for (int i = 0; i < n; i++) {
            long slotStart = (long) i * slot;
            long slotWidth = (i == n - 1) ? Math.max(1L, window - slotStart) : slot;
            long initialDelay = slotStart + ThreadLocalRandom.current().nextLong(slotWidth);
            startErrorScheduleAt(errors.get(i), initialDelay);
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
