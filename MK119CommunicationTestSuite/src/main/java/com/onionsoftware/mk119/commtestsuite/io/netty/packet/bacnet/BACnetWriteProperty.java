package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;
import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.*;

@Getter
@Setter
public class BACnetWriteProperty extends BACnetPacket {
    private BACnetObjectIdentifier oid;
    private BACnetUnsignedInteger propertyIdentifier;
    private BACnetReal value;
    private BACnetUnsignedInteger priority;

    @Autowired
    BACnetService bacnetService;

    public BACnetWriteProperty(BACnetPacket packet) {
        this.setBvlc_type(packet.getBvlc_type());
        this.setBvlc_func(packet.getBvlc_func());
        this.setBvlc_len(packet.getBvlc_len());
        this.setBacnetNPDU(packet.getBacnetNPDU());
        this.setBacnetAPDU(packet.getBacnetAPDU());
    }

    public ByteBuf encodeBACnetWritePropertyResponse(Device device) {
        int deviceInstanceNumber = Integer.parseInt(device.getCommunicationSettings().get("deviceInstanceId").toString());
        ByteBuf buf = Unpooled.buffer();
        ByteBuf buf_bvlc = Unpooled.buffer();
        ByteBuf buf_npdu = Unpooled.buffer();
        ByteBuf buf_apdu = Unpooled.buffer();

        writeBACnetBVLC(buf_bvlc);
        writeBACnetNPDU(buf_npdu, deviceInstanceNumber);
        buf_apdu.writeByte(BACNET_APDU_TYPE_SIMPLE_ACK << 4);
        buf_apdu.writeByte(this.getBacnetAPDU().getInvoke_id());
        buf_apdu.writeByte(BACNET_APDU_SERVICE_WRITE_PROPERTY);

        mergeBACnetBuffers(buf, buf_bvlc, buf_npdu, buf_apdu);
        return buf;
    }
}
