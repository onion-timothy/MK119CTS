package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

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
    public static final int FC_05 = 5;
    public static final int FC_06 = 6;
    public static final int FC_16 = 16;

    public static final List<Integer> SUPPORTED_FUNCTIONS;

    static {
        SUPPORTED_FUNCTIONS = Arrays.asList(new Integer[]{FC_01, FC_02, FC_03, FC_04, FC_05, FC_06, FC_16});
    }

    public static final int MODBUS_RTU = 0;
    public static final int MODBUS_TCP = 1;
}
