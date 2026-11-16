package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Policy;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Simulation 등록 검증: data_type 필수, target 에 맞는 타입, 정책 값이 타입에 맞는지. */
class SimulationTargetValidatorTest {

    private final SimulationTargetValidator validator = new SimulationTargetValidator();

    private static Device device(Protocol protocol) {
        Device d = new Device();
        d.setProtocol(protocol);
        d.setCommunicationSettings(new HashMap<>());
        return d;
    }

    private static SimulationPolicy policy(Policy kind, DataType type, Object value, Double... range) {
        SimulationPolicy p = new SimulationPolicy();
        p.setId("v1");
        p.setPolicy(kind);
        p.setData_type(type);
        p.setValue(value);
        if (range.length > 0) p.setRange(Arrays.asList(range));
        return p;
    }

    private static Simulation sim(String target, SimulationPolicy... policies) {
        Simulation s = new Simulation();
        s.setTarget(target);
        s.setPolicies(Arrays.asList(policies));
        return s;
    }

    private String check(Protocol protocol, String target, SimulationPolicy... policies) {
        return validator.validate(device(protocol), sim(target, policies));
    }

    @Test
    void dataTypeIsRequired() {
        String e = check(Protocol.SNMP, "1.3.6.1.4.1.1.0", policy(Policy.STATIC, null, 1.0));
        assertNotNull(e);
        assertTrue(e.startsWith("Data type is required"), e);
        assertNotNull(check(Protocol.BACNET, "analog_value 1", policy(Policy.RANDOM, null, null, 0.0, 10.0)));
    }

    @Test
    void bacnetInstanceIsWithin22Bits() {
        SimulationPolicy p = policy(Policy.STATIC, DataType.FLOAT, 1.0);
        assertNull(check(Protocol.BACNET, "analog_input 0", p));
        assertNull(check(Protocol.BACNET, "analog_input 4194302", p));
        // 4194303 은 예약값, 그 위는 22비트를 넘는다
        assertNotNull(check(Protocol.BACNET, "analog_input 4194303", p));
        assertNotNull(check(Protocol.BACNET, "analog_input 5000000", p));
        assertNotNull(check(Protocol.BACNET, "analog_input -1", p));
    }

    @Test
    void typeMustFitTheTarget() {
        assertNotNull(check(Protocol.BACNET, "binary_value 1", policy(Policy.STATIC, DataType.FLOAT, 1.0)));
        assertNull(check(Protocol.BACNET, "binary_value 1", policy(Policy.STATIC, DataType.BINARY, 1.0)));
        assertNotNull(check(Protocol.SNMP, "1.3.6.1.4.1.1.0", policy(Policy.STATIC, DataType.UI16AB, 1.0)));
        assertNotNull(check(Protocol.ETHERNET_IP, "TAG1", policy(Policy.STATIC, DataType.UI16AB, 1.0)));
        assertNull(check(Protocol.ETHERNET_IP, "TAG1", policy(Policy.STATIC, DataType.SI16BA, 1.0)));
        assertNotNull(check(Protocol.MODBUS, "1_1", policy(Policy.STATIC, DataType.UI16AB, 1.0)));
        assertNotNull(check(Protocol.MODBUS, "3_1", policy(Policy.STATIC, DataType.INTEGER, 1.0)));
    }

    @Test
    void valuesMustFitTheType() {
        assertNotNull(check(Protocol.GENERIC, "f", policy(Policy.STATIC, DataType.BINARY, 2.0)));
        assertNotNull(check(Protocol.GENERIC, "f", policy(Policy.STATIC, DataType.INTEGER, 1.5)));
        assertNull(check(Protocol.GENERIC, "f", policy(Policy.STATIC, DataType.FLOAT, 1.5)));
        assertNotNull(check(Protocol.GENERIC, "f", policy(Policy.SEQUENCE, DataType.BINARY, Arrays.asList(0.0, 1.0, 2.0))));
        assertNull(check(Protocol.GENERIC, "f", policy(Policy.SEQUENCE, DataType.BINARY, Arrays.asList(0.0, 1.0))));
        assertNotNull(check(Protocol.GENERIC, "f", policy(Policy.RANDOM, DataType.BINARY, null, 0.0, 5.0)));
        assertNull(check(Protocol.GENERIC, "f", policy(Policy.RANDOM, DataType.BINARY, null, 0.0, 1.0)));
        assertNotNull(check(Protocol.BACNET, "multi_state_value 1", policy(Policy.STATIC, DataType.INTEGER, 0.0)));
        assertNotNull(check(Protocol.MODBUS, "3_1", policy(Policy.STATIC, DataType.UI16AB, 70000.0)));
        assertNotNull(check(Protocol.SNMP, "1.3.6.1.4.1.1.0", policy(Policy.RANDOM, DataType.INTEGER, null, 0.0, 3e9)));
    }

    @Test
    void accumulate() {
        assertNotNull(check(Protocol.GENERIC, "f", policy(Policy.ACCUMULATE, DataType.BINARY, null, 0.0, 1.0)));
        assertNotNull(check(Protocol.GENERIC, "f", policy(Policy.ACCUMULATE, DataType.INTEGER, null, 0.0, 0.5)));
        assertNull(check(Protocol.GENERIC, "f", policy(Policy.ACCUMULATE, DataType.INTEGER, null, 0.0, 2.0)));
        assertNull(check(Protocol.GENERIC, "f", policy(Policy.ACCUMULATE, DataType.FLOAT, null, 0.0, 0.5)));
    }

    @Test
    void legacyEthernetIpPrefixGivesTheType() {
        // 저장할 때 접두어가 data_type 으로 옮겨진다
        assertNull(check(Protocol.ETHERNET_IP, "REAL@TAG1", policy(Policy.STATIC, null, 1.5)));
        assertNull(check(Protocol.ETHERNET_IP, "REAL@TAG1", policy(Policy.STATIC, DataType.FP32DA, 1.5)));
        assertNotNull(check(Protocol.ETHERNET_IP, "REAL@TAG1", policy(Policy.STATIC, DataType.SI32DA, 1.0)));
        assertNotNull(check(Protocol.ETHERNET_IP, "BOOL@FLAG", policy(Policy.STATIC, null, 2.0)));
    }

    @Test
    void severalPoliciesNameTheFailingOne() {
        SimulationPolicy ok = policy(Policy.STATIC, DataType.INTEGER, 1.0);
        SimulationPolicy bad = policy(Policy.STATIC, DataType.INTEGER, 1.5);
        bad.setId("v2");
        String e = validator.validate(device(Protocol.DB), sim("UPDATE t SET a=${v1}, b=${v2}", ok, bad));
        assertNotNull(e);
        assertTrue(e.startsWith("Policy v2:"), e);
    }

    @Test
    void noPoliciesChecksTheTargetOnly() {
        Simulation s = sim("bad oid");
        s.setPolicies(Collections.emptyList());
        assertNotNull(validator.validate(device(Protocol.SNMP), s));
    }
}
