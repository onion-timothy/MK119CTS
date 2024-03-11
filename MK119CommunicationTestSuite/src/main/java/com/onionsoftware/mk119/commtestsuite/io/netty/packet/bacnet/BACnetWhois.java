package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;


import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetEnumerated;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetUnsignedInteger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;

import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.*;
import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;

@Getter
@Setter
public class BACnetWhois extends BACnetPacket {
    BACnetUnsignedInteger deviceInstanceRangeLowLimit = new BACnetUnsignedInteger(-1);
    BACnetUnsignedInteger deviceInstanceRangeHighLimit = new BACnetUnsignedInteger(-1);

    public BACnetWhois(BACnetPacket packet) {
        this.setBvlc_type(packet.getBvlc_type());
        this.setBvlc_func(packet.getBvlc_func());
        this.setBvlc_len(packet.getBvlc_len());
        this.setBacnetNPDU(packet.getBacnetNPDU());
        this.setBacnetAPDU(packet.getBacnetAPDU());
    }

    public ByteBuf encodeBACnetIAmDeviceResponse(Device device) {
        int deviceInstanceNumber = Integer.parseInt(device.getCommunicationSettings().get("deviceInstanceId").toString());
        if ((this.getDeviceInstanceRangeLowLimit().getValue() == -1 && this.getDeviceInstanceRangeHighLimit().getValue() == -1)
                || (deviceInstanceNumber >= this.getDeviceInstanceRangeLowLimit().getValue() && deviceInstanceNumber < this.getDeviceInstanceRangeHighLimit().getValue())) {
            ByteBuf buf = Unpooled.buffer();
            ByteBuf buf_bvlc = Unpooled.buffer();
            ByteBuf buf_npdu = Unpooled.buffer();
            ByteBuf buf_apdu = Unpooled.buffer();

            writeBACnetBVLC(buf_bvlc);
            writeBACnetNPDU(buf_npdu, deviceInstanceNumber);

            buf_apdu.writeByte(BACNET_APDU_TYPE_UNCONFIRMED_REQ << 4);
            buf_apdu.writeByte(BACNET_APDU_SERVICE_I_AM);

            // i-Am device OID(4bytes)
            BACnetObjectIdentifier oid = new BACnetObjectIdentifier(BACNET_OBJECT_TYPE_device, deviceInstanceNumber);
            oid.setTagClass(BACNET_TAG_CLASS_APPLICATION);
            oid.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_OBJECT_IDENTIFIER);
            oid.setLength((byte) 0x04);
            writeBACnetTag(buf_apdu, oid);

            // Maximum APDU length accepted(1476)
            BACnetUnsignedInteger max_apdu_length_accepted = new BACnetUnsignedInteger(1476);
            max_apdu_length_accepted.setTagClass(BACNET_TAG_CLASS_APPLICATION);
            max_apdu_length_accepted.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
            max_apdu_length_accepted.setLength((byte) 0x02);
            writeBACnetTag(buf_apdu, max_apdu_length_accepted);

            // Segmentation Supported(0 : both)
            BACnetEnumerated segmentation_supported = new BACnetEnumerated(0);
            segmentation_supported.setTagClass(BACNET_TAG_CLASS_APPLICATION);
            segmentation_supported.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
            segmentation_supported.setLength((byte) 0x01);
            writeBACnetTag(buf_apdu, segmentation_supported);

            // Vendor Id
            BACnetUnsignedInteger venderId = new BACnetUnsignedInteger(0x0278);
            venderId.setTagClass(BACNET_TAG_CLASS_APPLICATION);
            venderId.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
            venderId.setLength((byte) 0x02);
            writeBACnetTag(buf_apdu, venderId);
            mergeBACnetBuffers(buf, buf_bvlc, buf_npdu, buf_apdu);
            return buf;
        } else return null;
    }
}
