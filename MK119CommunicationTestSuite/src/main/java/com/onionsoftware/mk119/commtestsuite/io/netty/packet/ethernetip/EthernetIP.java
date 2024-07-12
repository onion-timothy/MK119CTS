package com.onionsoftware.mk119.commtestsuite.io.netty.packet.ethernetip;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EthernetIP {
    EncapsulationHeader header;
    CommandSpecificData csData;

    // Common Industrial Protocol
    byte service;
    byte requestPathSize;
    byte pathClass;
    byte pathInstance;

}
