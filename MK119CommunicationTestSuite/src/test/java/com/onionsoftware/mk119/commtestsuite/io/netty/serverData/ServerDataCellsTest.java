package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 저장 칸 계산. lock 판정과 Test Plan 편집기가 같은 결과를 봐야 한다. */
class ServerDataCellsTest {
    @Test
    void modbus() {
        assertEquals(Collections.singletonList("3_10"), ServerDataCells.of(Protocol.MODBUS, "3_10", DataType.UI16AB));
        assertEquals(Arrays.asList("3_10", "3_11"), ServerDataCells.of(Protocol.MODBUS, "3_10", DataType.UI32AD));
        assertEquals(Arrays.asList("4_0", "4_1", "4_2", "4_3"), ServerDataCells.of(Protocol.MODBUS, "4_0", DataType.FP64AH));
        assertEquals(Collections.singletonList("1_3"), ServerDataCells.of(Protocol.MODBUS, "1_3", null));
        assertEquals(Collections.singletonList("x_y"), ServerDataCells.of(Protocol.MODBUS, "x_y", null));   // 읽을 수 없으면 그대로
    }

    @Test
    void xgtByteAddresses() {
        assertEquals(Arrays.asList("%DB20", "%DB21"), ServerDataCells.of(Protocol.XGT_PLC, "%dw10", DataType.UI16AB));
        assertEquals(Arrays.asList("%MB4", "%MB5", "%MB6", "%MB7"), ServerDataCells.of(Protocol.XGT_PLC, "%MD1", DataType.UI32AD));
        assertEquals(Collections.singletonList("%DB7"), ServerDataCells.of(Protocol.XGT_PLC, "%DB7", DataType.UI8));
    }

    @Test
    void ethernetIpTagsAndOthers() {
        assertEquals(Collections.singletonList("TAG1"), ServerDataCells.of(Protocol.ETHERNET_IP, "REAL@TAG1", DataType.FP32AD));
        assertEquals(Collections.singletonList("1.3.6.1.0"), ServerDataCells.of(Protocol.SNMP, "1.3.6.1.0", null));
        assertEquals(Collections.singletonList("@RCUAI_3"), ServerDataCells.of(Protocol.DAEYUN_RCU, "@RCUAI_3", null));
    }
}
