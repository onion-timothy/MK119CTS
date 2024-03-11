package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import lombok.Getter;
import lombok.Setter;

import java.net.InetSocketAddress;

@Getter
@Setter
public class SnmpPdu extends com.ireasoning.protocol.snmp.SnmpPdu {
    private InetSocketAddress sender;

    public SnmpPdu(com.ireasoning.protocol.snmp.SnmpPdu snmpPdu) {
        super(snmpPdu);
    }

    public SnmpPdu(int type) {
        super(type);
    }

}
