package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;

import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetTag;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class BACnetAPDU {
    private byte type;
    private byte flags;
    private byte max_res_seg_acpt;
    private byte invoke_id;
    private byte service_choice;

    private List<BACnetTag> tags = new ArrayList<>();
}
