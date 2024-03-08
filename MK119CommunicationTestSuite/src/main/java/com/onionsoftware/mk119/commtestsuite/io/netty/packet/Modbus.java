package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Modbus extends Packet {
    protected byte uid;
    protected byte fc;
    protected short register;
    protected short requestSize;

    public static final int FC_01 = 1;
    public static final int FC_02 = 2;
    public static final int FC_03 = 3;
    public static final int FC_04 = 4;

    public static final int MODBUS_RTU = 0;
    public static final int MODBUS_TCP = 1;
}
