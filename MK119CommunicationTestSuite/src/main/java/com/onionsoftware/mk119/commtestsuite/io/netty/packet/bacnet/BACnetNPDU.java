package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BACnetNPDU {
    private byte version;
    private byte control;
    private short dest_net_addr;
    private byte dest_mac_layer_addr_len;
    private byte[] dadr;
    private int hop_count;

    private short src_net_addr;
    private byte src_mac_layer_addr_len = 6;
    private byte[] sadr;
}
