package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXField;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.GenericData;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
public class GenericTCPUDPPacket extends Packet {
    private String rx_id;
    private List<RXField> fields;
    private GenericData genericData;

    public GenericTCPUDPPacket(String rx_id) {
        setRx_id(rx_id);
        setFields(new LinkedList<>());
    }
}
