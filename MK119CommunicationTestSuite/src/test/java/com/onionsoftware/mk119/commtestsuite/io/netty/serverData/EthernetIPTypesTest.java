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
        assertEquals(CIPData.Type.INT, EthernetIPTypes.cipTypeOf(DataType.SI16BA));
        assertEquals(CIPData.Type.DINT, EthernetIPTypes.cipTypeOf(DataType.SI32DA));
        assertEquals(CIPData.Type.REAL, EthernetIPTypes.cipTypeOf(DataType.FP32DA));
        assertEquals(CIPData.Type.BOOL, EthernetIPTypes.cipTypeOf(DataType.BINARY));
    }

    @Test
    void otherTypesAreNotSent() {
        // 부호 없는 타입, little-endian 이 아닌 바이트 순서, CIP 에 없는 폭, 폭 없는 타입
        for (DataType t : new DataType[]{DataType.UI8, DataType.UI16BA, DataType.SI16AB, DataType.SI32AD,
                DataType.FP32AD, DataType.UI32DA, DataType.SI64HA, DataType.FP64HA, DataType.INTEGER, DataType.FLOAT}) {
            assertNull(EthernetIPTypes.cipTypeOf(t), t.name());
        }
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
        assertEquals(DataType.FP32DA, EthernetIPTypes.dataTypeForLegacyPrefix("REAL"));
        assertEquals(DataType.SI16BA, EthernetIPTypes.dataTypeForLegacyPrefix("INT"));
        assertEquals(DataType.SI8, EthernetIPTypes.dataTypeForLegacyPrefix("SINT"));
        assertEquals(DataType.SI32DA, EthernetIPTypes.dataTypeForLegacyPrefix("DINT"));
        assertEquals(DataType.BINARY, EthernetIPTypes.dataTypeForLegacyPrefix("BOOL"));
        // BITS 는 더 이상 내보내지 않는다. 같은 폭의 DINT 로
        assertEquals(DataType.SI32DA, EthernetIPTypes.dataTypeForLegacyPrefix("BITS"));
        assertNull(EthernetIPTypes.dataTypeForLegacyPrefix(null));
    }
}
