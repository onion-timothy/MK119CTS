package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ModbusTCP extends Modbus {
    private short tid;
    private short pid;
    private short tcpLength;

    public static final short PROTOCOL_ID = 0x0000;
}
