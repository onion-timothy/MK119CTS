package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetUnsignedInteger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.*;

@Getter
@Setter
@AllArgsConstructor
public class BACnetError extends BACnetPacket {
    private BACnetUnsignedInteger errorClass;
    private BACnetUnsignedInteger errorCode;

    public ByteBuf encodeBACnetError(Device device, byte invokeId, byte service) {
        int deviceInstanceNumber = Integer.parseInt(device.getCommunicationSettings().get("deviceInstanceId").toString());

        ByteBuf buf = Unpooled.buffer();
        ByteBuf buf_bvlc = Unpooled.buffer();
        ByteBuf buf_npdu = Unpooled.buffer();
        ByteBuf buf_apdu = Unpooled.buffer();

        writeBACnetBVLC(buf_bvlc);
        writeBACnetNPDU(buf_npdu, deviceInstanceNumber);

        buf_apdu.writeByte(0x50);
        buf_apdu.writeByte(invokeId);
        buf_apdu.writeByte(service);

        writeBACnetTag(buf_apdu, errorClass);
        writeBACnetTag(buf_apdu, errorCode);

        mergeBACnetBuffers(buf, buf_bvlc, buf_npdu, buf_apdu);

        return buf;
    }
}
