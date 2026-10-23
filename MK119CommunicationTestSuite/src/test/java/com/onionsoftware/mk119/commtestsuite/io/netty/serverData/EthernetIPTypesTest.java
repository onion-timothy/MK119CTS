package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import etherip.types.CIPData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** CTS DataType -> CIP 타입 대응과 예전 target 형식의 처리. */
class EthernetIPTypesTest {

    @Test
    void ctsTypeToCipType() {
        assertEquals(CIPData.Type.SINT, EthernetIPTypes.cipTypeOf(DataType.SI8));
        assertEquals(CIPData.Type.INT, EthernetIPTypes.cipTypeOf(DataType.UI8));       // 한 단계 넓게
        assertEquals(CIPData.Type.INT, EthernetIPTypes.cipTypeOf(DataType.SI16BA));    // 바이트 순서는 보지 않는다
        assertEquals(CIPData.Type.DINT, EthernetIPTypes.cipTypeOf(DataType.UI16AB));   // 한 단계 넓게
        assertEquals(CIPData.Type.DINT, EthernetIPTypes.cipTypeOf(DataType.SI32DA));
        assertEquals(CIPData.Type.REAL, EthernetIPTypes.cipTypeOf(DataType.FP32CB));
        assertEquals(CIPData.Type.BOOL, EthernetIPTypes.cipTypeOf(DataType.BOOL));
        assertEquals(CIPData.Type.BITS, EthernetIPTypes.cipTypeOf(DataType.BITS));
        assertNull(EthernetIPTypes.cipTypeOf(DataType.UI32AD));
        assertNull(EthernetIPTypes.cipTypeOf(DataType.SI64AH));
        assertNull(EthernetIPTypes.cipTypeOf(DataType.FP64AH));
    }

    @Test
    void legacyTargets() {
        assertEquals("REAL", EthernetIPTypes.legacyPrefixOf("REAL@TAG1"));
        assertEquals("TAG1", EthernetIPTypes.tagOf("REAL@TAG1"));
        assertEquals("sys_runtime_acc[0]", EthernetIPTypes.tagOf("DINT@sys_runtime_acc[0]"));
        assertNull(EthernetIPTypes.legacyPrefixOf("TAG1"));
        assertEquals("TAG1", EthernetIPTypes.tagOf("TAG1"));
        assertNull(EthernetIPTypes.legacyPrefixOf("STRUCT@X"));   // 수치 타입이 아니면 접두어로 보지 않는다
    }

    @Test
    void legacyPrefixKeepsTheWireType() {
        // 이미 같은 CIP 타입으로 나가면 그대로 둔다
        assertEquals(DataType.FP32CB, EthernetIPTypes.dataTypeForLegacyPrefix("REAL", DataType.FP32CB));
        assertEquals(DataType.SI16BA, EthernetIPTypes.dataTypeForLegacyPrefix("INT", DataType.SI16BA));
        // 예전에는 INT 로 나가던 UI16 은 이제 DINT 로 나가므로 SI16 으로 바꾼다
        assertEquals(DataType.SI16AB, EthernetIPTypes.dataTypeForLegacyPrefix("INT", DataType.UI16AB));
        assertEquals(DataType.SI8, EthernetIPTypes.dataTypeForLegacyPrefix("SINT", DataType.UI8));
        assertEquals(DataType.SI32AD, EthernetIPTypes.dataTypeForLegacyPrefix("DINT", DataType.UI32AD));
        assertEquals(DataType.BOOL, EthernetIPTypes.dataTypeForLegacyPrefix("BOOL", DataType.UI8));
        assertEquals(DataType.BITS, EthernetIPTypes.dataTypeForLegacyPrefix("BITS", DataType.UI32AD));
    }
}
