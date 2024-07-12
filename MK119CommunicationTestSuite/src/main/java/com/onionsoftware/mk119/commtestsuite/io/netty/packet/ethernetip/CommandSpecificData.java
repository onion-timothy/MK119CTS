package com.onionsoftware.mk119.commtestsuite.io.netty.packet.ethernetip;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class CommandSpecificData {
    int interfaceHandle;
    short timeout;
    short itemCount;
    Map<Integer, Integer> items;
}
