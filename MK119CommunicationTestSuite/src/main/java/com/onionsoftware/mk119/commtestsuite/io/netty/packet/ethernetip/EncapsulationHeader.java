package com.onionsoftware.mk119.commtestsuite.io.netty.packet.ethernetip;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EncapsulationHeader {
    short command;
    int session;
    int status;
    String senderContext;
    int options;
}
