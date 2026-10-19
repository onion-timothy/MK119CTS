package com.onionsoftware.mk119.commtestsuite.simulation;

import org.springframework.scheduling.support.PeriodicTrigger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 주기 작업의 첫 실행 시각을 흩어 주는 트리거 생성기.
 *
 * <p>{@code new PeriodicTrigger(n, SECONDS)} 는 초기 지연이 0 이라, 같은 주기로 등록된 작업들이
 * 모두 같은 순간에 처음 실행되고 그 뒤로도 한 덩어리로 붙어 다닌다. 장비 한 대에 시뮬레이션
 * 수천 건을 10초 주기로 올리면 10초마다 수천 건의 ServerData 변경이 한 틱에 몰리고,
 * 그 묶음이 그대로 Server Data 화면으로 쏟아져 전송 버퍼가 터진다.
 *
 * <p>그래서 등록 시점에 0 ~ {@link #MAX_JITTER_MS} 범위의 초기 지연을 무작위로 준다.
 * 상한을 둔 이유는 두 가지다.
 * <ul>
 *   <li>주기 전체(예: 60초)를 창으로 쓰면 장비를 켜고 첫 값이 나올 때까지 그만큼 기다리게 된다</li>
 *   <li>변경 묶음은 250ms 마다 비워지므로({@code ServerDataBridge.FLUSH_INTERVAL_MS}),
 *       2초 창이면 서로 다른 전송 창 여덟 칸으로 갈라지기에 충분하다</li>
 * </ul>
 *
 * <p>주기가 상한보다 짧으면 창을 주기 길이로 줄인다. 1초 주기에 2초를 흩뿌리면
 * 흩어지는 것이 아니라 한 틱을 통째로 건너뛰는 셈이 되기 때문이다.
 */
public final class JitteredTrigger {

    /** 초기 지연의 상한 (ms). */
    public static final long MAX_JITTER_MS = 2_000L;

    private JitteredTrigger() {
    }

    /**
     * 초 단위 주기로 도는 트리거를 만들되 첫 실행만 무작위로 늦춘다.
     * 두 번째 실행부터는 주기 그대로이므로 전체 실행 횟수는 달라지지 않는다.
     */
    public static PeriodicTrigger ofSeconds(int intervalSeconds) {
        long periodMs = (long) intervalSeconds * 1000L;
        // 초기 지연은 트리거의 TimeUnit 으로 해석된다. 초 단위로 두면 지터를 1초 미만으로
        // 줄 수 없으므로 ms 단위 트리거로 만든다.
        PeriodicTrigger trigger = new PeriodicTrigger(periodMs, TimeUnit.MILLISECONDS);
        trigger.setInitialDelay(jitterMs(periodMs));
        return trigger;
    }

    /** 0 이상 min(periodMs, MAX_JITTER_MS) 미만의 초기 지연. */
    static long jitterMs(long periodMs) {
        long window = Math.min(periodMs, MAX_JITTER_MS);
        if (window <= 0L) return 0L;
        return ThreadLocalRandom.current().nextLong(window);
    }
}
