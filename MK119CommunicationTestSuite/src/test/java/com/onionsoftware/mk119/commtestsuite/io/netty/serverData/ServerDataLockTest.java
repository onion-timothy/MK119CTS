package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** lock 판정은 쓰기가 덮는 칸 전부를 본다. 시작 칸만 보면 다중 레지스터 쓰기가 잠긴 칸을 덮는다. */
class ServerDataLockTest {

    private static Simulation simulation(String target, DataType dataType) {
        SimulationPolicy p = new SimulationPolicy();
        p.setData_type(dataType);
        Simulation s = new Simulation();
        s.setTarget(target);
        s.setPolicies(Collections.singletonList(p));
        return s;
    }

    @Test
    void multiRegisterSimulationDoesNotOverwriteALockedSecondRegister() throws Exception {
        ModbusData sd = new ModbusData(10);
        sd.setServerData("3_1", 10, DataType.UI16AB, ServerDataChangeSource.API_WRITE, 30);

        // 3_0 의 UI32 simulation 은 3_0, 3_1 을 쓴다. 501138 = 0x0007A592 → 3_1 에는 7 이 들어갈 뻔했다
        assertTrue(sd.isLocked("3_0", DataType.UI32CB));
        assertFalse(sd.setServerData(simulation("3_0", DataType.UI32CB), 501138, ServerDataChangeSource.VALUE_SIMULATION));
        assertEquals(10, sd.readValueAt("3_1").intValue());
    }

    @Test
    void singleRegisterWriteNextToALockStillGoesThrough() throws Exception {
        ModbusData sd = new ModbusData(10);
        sd.setServerData("3_1", 10, DataType.UI16AB, ServerDataChangeSource.API_WRITE, 30);

        assertFalse(sd.isLocked("3_0", DataType.UI16AB));
        assertTrue(sd.setServerData(simulation("3_0", DataType.UI16AB), 5, ServerDataChangeSource.VALUE_SIMULATION));
        assertEquals(5, sd.readValueAt("3_0").intValue());
        assertEquals(10, sd.readValueAt("3_1").intValue());
    }

    /** lock 칸 집합이 바뀌면 그 자리에서 한 번, 만료는 만료 시각에 알린다. 그대로면 알리지 않는다. */
    @Test
    void lockChangesAreNotifiedRightAwayIncludingExpiry() throws Exception {
        List<Set<String>> notices = new CopyOnWriteArrayList<>();
        IOServer server = mock(IOServer.class);
        doAnswer(inv -> notices.add(new TreeSet<>(inv.<Set<String>>getArgument(0))))
                .when(server).fireLocksChanged(anySet());
        ModbusData sd = new ModbusData(10);
        sd.setIoServer(server);

        sd.setServerData("3_0", 70000, DataType.UI32AD, ServerDataChangeSource.API_WRITE, 1);
        assertEquals(Collections.singletonList(new TreeSet<>(Arrays.asList("3_0", "3_1"))), notices);   // 두 칸에 한 번

        sd.setServerData("3_0", 70000, DataType.UI32AD, ServerDataChangeSource.API_WRITE, 1);           // 연장만
        sd.setServerData(simulation("3_5", DataType.UI16AB), 3, ServerDataChangeSource.VALUE_SIMULATION);
        assertEquals(1, notices.size());

        Thread.sleep(1300);                                                                             // 연장된 만료 뒤
        assertEquals(Collections.emptySet(), notices.get(notices.size() - 1));
        assertEquals(2, notices.size());

        sd.stopSimulationFor("3_5", 30, DataType.UI16AB, ServerDataChangeSource.PROTOCOL_WRITE);
        assertEquals(Collections.singleton("3_5"), notices.get(2));
        sd.setServerData("3_5", 1, DataType.UI16AB, ServerDataChangeSource.API_WRITE, 0);               // lock 0 은 해제
        assertEquals(Collections.emptySet(), notices.get(3));

        sd.setServerData("3_7", 1, DataType.UI16AB, ServerDataChangeSource.API_WRITE, ServerData.PERMANENT_LOCK_SECONDS);
        sd.clearPermanentLocks();                                                                       // 편집 세션 종료
        assertEquals(Arrays.asList(Collections.singleton("3_7"), Collections.emptySet()), notices.subList(4, 6));
    }

    @Test
    void protocolWriteOverALockedSecondRegisterIsIgnored() throws Exception {
        ModbusData sd = new ModbusData(10);
        sd.setServerData("3_3", 10, DataType.UI16AB, ServerDataChangeSource.API_WRITE, 30);

        assertFalse(sd.setServerData("3_2", 70000, DataType.UI32AD, ServerDataChangeSource.PROTOCOL_WRITE));
        assertEquals(10, sd.readValueAt("3_3").intValue());
    }
}
