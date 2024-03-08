package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OPCAgent extends Packet {
    private int protocolHeader;
    private int start;
}
