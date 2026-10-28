package com.onionsoftware.mk119.commtestsuite.testplan;

import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanAction;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataCells;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Test Plan 의 실행 시간 = 마지막 lock 이 풀리는 시각. lock 이 없으면 마지막 실행 시각이다.
 * <p>
 * lock 은 칸마다 센다. 같은 칸에 나중 쓰기가 들어오면 앞 lock 은 그 시각에 새 것으로 바뀌거나(lockSeconds &gt; 0)
 * 풀린다(0) — {@code ServerData.lock} 의 규칙. 앞 lock 은 늦어도 그 쓰기 시각(≤ 마지막 실행 시각)에 끝나므로,
 * 칸마다 마지막으로 쓴 Action 의 "At + lock" 만 보면 된다. 칸은 lock 판정과 같은 {@link ServerDataCells} 로 센다.
 * <p>
 * 등록 검증(S3), 실행기(끝나는 시각), 화면 응답이 같은 계산을 쓴다. 편집기의 다이어그램도 같은 규칙으로 그린다.
 */
public final class TestPlanTiming {
    private TestPlanTiming() {
    }

    /**
     * @param protocolOf 장비 id → 프로토콜. 장비가 없으면 null 을 돌려주면 된다(target 한 칸으로 센다)
     */
    public static long durationSeconds(List<TestPlanAction> actions, Function<Integer, Protocol> protocolOf) {
        List<TestPlanAction> sorted = new ArrayList<>(actions);
        sorted.sort(Comparator.comparingInt(TestPlanAction::getAtSeconds).thenComparingInt(TestPlanAction::getSeq));
        long end = 0;
        Map<String, Long> lockEnds = new HashMap<>();   // 장비:칸 -> 그 칸을 마지막으로 쓴 Action 의 lock 이 끝나는 시각
        for (TestPlanAction a : sorted) {
            end = Math.max(end, a.getAtSeconds());
            for (String cell : ServerDataCells.of(protocolOf.apply(a.getDeviceId()), a.getTarget(), dataTypeOf(a))) {
                lockEnds.put(a.getDeviceId() + ":" + cell, (long) a.getAtSeconds() + Math.max(0, a.getLockSeconds()));
            }
        }
        for (long until : lockEnds.values()) end = Math.max(end, until);
        return end;
    }

    private static DataType dataTypeOf(TestPlanAction a) {
        try {
            return a.getDataType() == null || a.getDataType().isEmpty() ? null : DataType.valueOf(a.getDataType());
        } catch (IllegalArgumentException e) {
            return null;   // 등록 검증(A6)이 막는다. 여기서는 target 한 칸으로 센다
        }
    }
}
