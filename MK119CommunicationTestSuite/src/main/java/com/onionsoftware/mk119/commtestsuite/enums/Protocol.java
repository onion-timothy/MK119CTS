package com.onionsoftware.mk119.commtestsuite.enums;

import java.util.Arrays;
import java.util.List;

public enum Protocol {
    GENERIC(1),
    MODBUS(2),
    XGT_PLC(3),
    DAEYUN_RCU(4),
    SNMP(5),
    BACNET(6),
    OPC_AGENT(7),
    DB(8),
    ETHERNET_IP(9);
    final private int value;

    Protocol(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }

    public static final List<Protocol> NETTY_PROTOCOLS = Arrays.asList(GENERIC, MODBUS, XGT_PLC, DAEYUN_RCU, SNMP, OPC_AGENT, ETHERNET_IP);
}
