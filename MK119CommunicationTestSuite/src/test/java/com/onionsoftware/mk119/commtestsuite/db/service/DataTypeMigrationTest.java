package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.onionsoftware.mk119.commtestsuite.db.service.DataTypeMigration.forSimulation;
import static org.junit.jupiter.api.Assertions.*;

/** 저장된 data_type 을 새 타입 표로 옮기는 규칙. */
class DataTypeMigrationTest {

    @Test
    void ethernetIpKeepsTheWireType() {
        assertEquals("BINARY", forSimulation(Protocol.ETHERNET_IP, "FLAG", "BOOL", false));
        assertEquals("SI32DA", forSimulation(Protocol.ETHERNET_IP, "T", "BITS", false));
        assertEquals("SI8", forSimulation(Protocol.ETHERNET_IP, "T", "SI8", false));
        assertEquals("SI16BA", forSimulation(Protocol.ETHERNET_IP, "T", "UI8", false));       // 예전에 INT 로 나갔다
        assertEquals("SI16BA", forSimulation(Protocol.ETHERNET_IP, "T", "SI16AB", false));
        assertEquals("SI32DA", forSimulation(Protocol.ETHERNET_IP, "T", "UI16AB", false));    // 예전에 DINT 로 나갔다
        assertEquals("SI32DA", forSimulation(Protocol.ETHERNET_IP, "T", "SI32AD", false));
        assertEquals("FP32DA", forSimulation(Protocol.ETHERNET_IP, "T", "FP32CB", false));
        assertEquals("FP32DA", forSimulation(Protocol.ETHERNET_IP, "T", "FP32DA", false));
        assertNull(forSimulation(Protocol.ETHERNET_IP, "T", null, false));
    }

    @Test
    void targetWithOneTypeTakesIt() {
        assertEquals("BINARY", forSimulation(Protocol.BACNET, "binary_value 1", "UI16AB", false));
        assertEquals("INTEGER", forSimulation(Protocol.BACNET, "multi_state_value 1", "FP32AD", true));
        assertEquals("BINARY", forSimulation(Protocol.DAEYUN_RCU, "@RCUDI_1", null, false));
        assertEquals("INTEGER", forSimulation(Protocol.DAEYUN_RCU, "@RCUAI_1", "SI16AB", false));
        assertEquals("BINARY", forSimulation(Protocol.MODBUS, "1_3", "UI16AB", false));
        assertEquals("BINARY", forSimulation(Protocol.MODBUS, "2_3", null, false));
    }

    @Test
    void plainProtocolsSplitIntoIntegerAndFloat() {
        assertEquals("INTEGER", forSimulation(Protocol.SNMP, "1.3.6", "UI32AD", false));
        assertEquals("FLOAT", forSimulation(Protocol.SNMP, "1.3.6", "UI16AB", true));    // 값에 소수가 있다
        assertEquals("FLOAT", forSimulation(Protocol.SNMP, "1.3.6", "FP64AH", false));
        assertEquals("FLOAT", forSimulation(Protocol.SNMP, "1.3.6", null, false));       // 문자열로 나가던 그대로
        assertEquals("FLOAT", forSimulation(Protocol.BACNET, "analog_input 1", "FP32AD", false));
        assertEquals("INTEGER", forSimulation(Protocol.BACNET, "analog_input 1", "SI16AB", false));
        assertEquals("INTEGER", forSimulation(Protocol.GENERIC, "f", "UI8", false));
        assertEquals("INTEGER", forSimulation(Protocol.DB, "UPDATE", "SI32AD", false));
    }

    @Test
    void registerProtocolsAndMigratedValuesStay() {
        assertEquals("UI32CB", forSimulation(Protocol.MODBUS, "3_1", "UI32CB", false));
        assertEquals("UI16AB", forSimulation(Protocol.XGT_PLC, "%MW1", "UI16AB", false));
        assertEquals("FLOAT", forSimulation(Protocol.OPC_AGENT, "3", "FLOAT", false));
        assertEquals("SI16BA", forSimulation(Protocol.ETHERNET_IP, "T", "SI16BA", false));
    }

    @Test
    void fractionLooksAtValuesAndRange() {
        Map<String, Object> p = new HashMap<>();
        p.put("value", 3.0);
        assertFalse(DataTypeMigration.hasFraction(p));
        p.put("range", Arrays.asList(0.0, 0.5));
        assertTrue(DataTypeMigration.hasFraction(p));
        p.remove("range");
        p.put("value", Arrays.asList(1.0, 2.25));
        assertTrue(DataTypeMigration.hasFraction(p));
    }
}
