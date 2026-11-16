package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.onionsoftware.mk119.commtestsuite.enums.DataType.*;
import static org.junit.jupiter.api.Assertions.*;

/** 프로토콜·target 이 받는 타입 표와 값 규칙. */
class DataTypeRulesTest {

    private static List<DataType> allowed(Protocol p, String target) {
        return DataTypeRules.allowed(p, target);
    }

    @Test
    void plainProtocolsTakeBinaryIntegerFloat() {
        List<DataType> plain = Arrays.asList(INTEGER, FLOAT, BINARY);
        assertEquals(plain, allowed(Protocol.SNMP, "1.3.6.1.4.1.1.0"));
        assertEquals(plain, allowed(Protocol.GENERIC, "field1"));
        assertEquals(plain, allowed(Protocol.OPC_AGENT, "7"));
        assertEquals(plain, allowed(Protocol.DB, "UPDATE t SET v=${v1}"));
        assertEquals(plain, allowed(Protocol.DAEYUN_RCU, "tx_field"));
    }

    @Test
    void targetDecidesTheType() {
        assertEquals(Arrays.asList(BINARY), allowed(Protocol.BACNET, "binary_value 1"));
        assertEquals(Arrays.asList(INTEGER), allowed(Protocol.BACNET, "multi_state_input 3"));
        assertEquals(Arrays.asList(FLOAT, INTEGER), allowed(Protocol.BACNET, "analog_input 2"));
        assertEquals(Arrays.asList(BINARY), allowed(Protocol.DAEYUN_RCU, "@RCUDI_1"));
        assertEquals(Arrays.asList(INTEGER), allowed(Protocol.DAEYUN_RCU, "@RCUAI_1"));
        assertEquals(Arrays.asList(BINARY), allowed(Protocol.MODBUS, "1_5"));
        assertEquals(Arrays.asList(BINARY), allowed(Protocol.MODBUS, "2_5"));
    }

    @Test
    void ethernetIpTakesSignedLittleEndianAndBinary() {
        assertEquals(Arrays.asList(SI8, SI16BA, SI32DA, FP32DA, BINARY), allowed(Protocol.ETHERNET_IP, "TAG1"));
    }

    @Test
    void registerProtocolsKeepSizedTypes() {
        List<DataType> reg = allowed(Protocol.MODBUS, "3_10");
        assertEquals(UI16AB, reg.get(0));
        assertTrue(reg.contains(FP64HA));
        assertFalse(reg.contains(UI8), "a register holds 2 bytes");
        assertFalse(reg.contains(INTEGER));
        assertFalse(reg.contains(BINARY));
        assertEquals(Arrays.asList(UI8, SI8), allowed(Protocol.XGT_PLC, "%MB1"));
        assertEquals(Arrays.asList(UI16AB, UI16BA, SI16AB, SI16BA), allowed(Protocol.XGT_PLC, "%dw1"));
        List<DataType> d = allowed(Protocol.XGT_PLC, "%DD1");
        assertTrue(d.contains(FP32CB));
        assertFalse(d.contains(SI64AH));
    }

    @Test
    void mismatchNamesTheAllowedTypes() {
        assertNull(DataTypeRules.mismatch(Protocol.BACNET, "binary_value 1", BINARY));
        assertNull(DataTypeRules.mismatch(Protocol.BACNET, "binary_value 1", null));
        String e = DataTypeRules.mismatch(Protocol.BACNET, "binary_value 1", FLOAT);
        assertNotNull(e);
        assertTrue(e.contains("Use BINARY"), e);
    }

    @Test
    void valueRules() {
        assertNull(ServerDataValueRules.valueError(Protocol.GENERIC, "f", BINARY, 1));
        assertNotNull(ServerDataValueRules.valueError(Protocol.GENERIC, "f", BINARY, 2));
        assertNull(ServerDataValueRules.valueError(Protocol.GENERIC, "f", INTEGER, -5));
        assertNotNull(ServerDataValueRules.valueError(Protocol.GENERIC, "f", INTEGER, 1.5));
        assertNull(ServerDataValueRules.valueError(Protocol.GENERIC, "f", FLOAT, 1.5));
        // SNMP INTEGER 는 Integer32
        assertNotNull(ServerDataValueRules.valueError(Protocol.SNMP, "1.3.6", INTEGER, -2147483649d));
        // multi-state 는 1 부터
        assertNotNull(ServerDataValueRules.valueError(Protocol.BACNET, "multi_state_value 1", INTEGER, 0));
        assertNull(ServerDataValueRules.valueError(Protocol.BACNET, "multi_state_value 1", INTEGER, 1));
        // 타입을 비워도 target 이 하나만 받으면 그 타입으로 본다
        assertNotNull(ServerDataValueRules.valueError(Protocol.BACNET, "binary_input 1", null, 3));
        assertNotNull(ServerDataValueRules.valueError(Protocol.DAEYUN_RCU, "@RCUAI_1", null, 40000));
    }
}
