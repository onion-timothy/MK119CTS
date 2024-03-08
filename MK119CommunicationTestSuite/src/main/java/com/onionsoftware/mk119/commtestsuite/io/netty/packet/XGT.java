package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class XGT extends Packet {
    private int source;
    private int invokeId;
    private int length;
    private int code;
    private int dataType;
    private String variable;
    private int blockSize;
}
