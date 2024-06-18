package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BACnetPacket {
    private byte bvlc_type;
    private byte bvlc_func;
    private short bvlc_len;

    private BACnetNPDU bacnetNPDU;
    private BACnetAPDU bacnetAPDU;
}
