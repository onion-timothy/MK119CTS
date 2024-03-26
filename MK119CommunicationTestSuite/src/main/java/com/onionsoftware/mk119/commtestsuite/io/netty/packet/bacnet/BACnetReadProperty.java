package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;
import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.*;

@Getter
@Setter
public class BACnetReadProperty extends BACnetPacket {
    private BACnetObjectIdentifier oid;
    private BACnetUnsignedInteger propertyIdentifier;

    @Autowired
    BACnetService bacnetService;

    public BACnetReadProperty(BACnetPacket packet) {
        this.setBvlc_type(packet.getBvlc_type());
        this.setBvlc_func(packet.getBvlc_func());
        this.setBvlc_len(packet.getBvlc_len());
        this.setBacnetNPDU(packet.getBacnetNPDU());
        this.setBacnetAPDU(packet.getBacnetAPDU());
        this.setSender(packet.getSender());
    }

    public ByteBuf encodeBACnetReadPropertyResponse(Device device) {
        int deviceInstanceNumber = Integer.parseInt(device.getCommunicationSettings().get("deviceInstanceId").toString());

        ByteBuf buf = Unpooled.buffer();
        ByteBuf buf_bvlc = Unpooled.buffer();
        ByteBuf buf_npdu = Unpooled.buffer();
        ByteBuf buf_apdu = Unpooled.buffer();

        writeBACnetBVLC(buf_bvlc);
        writeBACnetNPDU(buf_npdu, deviceInstanceNumber);

        buf_apdu.writeByte(BACNET_APDU_TYPE_COMPLEX_ACK << 4);
        buf_apdu.writeByte(this.getBacnetAPDU().getInvoke_id());
        buf_apdu.writeByte(BACNET_APDU_SERVICE_READ_PROPERTY);

        byte tagNumber = 0;

        BACnetObjectIdentifier oid = this.getOid();
        oid.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        oid.setTagNumber(tagNumber++);
        oid.setLength((byte) 4);
        writeBACnetTag(buf_apdu, oid);

        BACnetUnsignedInteger propertyIdentifierObjectList = new BACnetUnsignedInteger(this.getPropertyIdentifier().getValue());
        propertyIdentifierObjectList.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        propertyIdentifierObjectList.setTagNumber(tagNumber++);
        propertyIdentifierObjectList.setLength((byte) 1);
        writeBACnetTag(buf_apdu, propertyIdentifierObjectList);

        byte bracketTagNumber = ++tagNumber;
        BACnetUnsignedInteger openingTag = new BACnetUnsignedInteger(0);
        openingTag.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        openingTag.setTagNumber(bracketTagNumber);
        openingTag.setLength(BACNET_NAMED_TAG_OPENING_TAG);
        writeBACnetTag(buf_apdu, openingTag);
        List<Simulation> simulationList = bacnetService.getSimulationMap().get(device.getId());

        if (this.getPropertyIdentifier().getValue() == BACNET_PROPERTY_IDENTIFIER_OBJ_LST) {
            oid.setTagClass(BACNET_TAG_CLASS_APPLICATION);
            oid.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_OBJECT_IDENTIFIER);
            oid.setLength((byte) 4);
            writeBACnetTag(buf_apdu, oid);

            if (simulationList != null) {
                for (Simulation simulation : simulationList) {
                    BACnetObjectIdentifier _oid = BACnetObjectIdentifier.parseSimulationTarget(simulation.getTarget());
                    if (_oid != null) {
                        _oid.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                        _oid.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_OBJECT_IDENTIFIER);
                        _oid.setLength((byte) 4);
                        writeBACnetTag(buf_apdu, _oid);
                    }
                }
            }
        } else if (this.getPropertyIdentifier().getValue() == BACNET_PROPERTY_IDENTIFIER_OBJ_NME) {
            String write = "";
            if (oid.getObject_type() == BACNET_OBJECT_TYPE_device) {
                write = device.getName();
            } else if (BACNET_OBJECT_TYPE_SUPPORTED.contains((byte) oid.getObject_type())) {
                Optional<Simulation> simulationOpt = simulationList.stream().filter(e -> Objects.requireNonNull(BACnetObjectIdentifier.parseSimulationTarget(e.getTarget())).equals(oid)).findAny();
                if (simulationOpt.isPresent()) {
                    write = simulationOpt.get().getTarget();
                }
            }
            if (!write.isEmpty()) {
                BACnetCharacterString name = new BACnetCharacterString(write, BACNET_CHARACTER_STRING_ENCODING_UTF8);
                name.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                name.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
                name.setLength(BACNET_NAMED_TAG_EXTENDED_VALUE);
                name.setExtendedLengthValue((byte) (write.getBytes().length + 1));
                writeBACnetTag(buf_apdu, name);
            }
        } else {
            if (oid.getObject_type() == BACNET_OBJECT_TYPE_device && SIMULATION_DEVICE_PROPERTIES.containsKey((byte) this.getPropertyIdentifier().getValue())) {
                BACnetTag tag = SIMULATION_DEVICE_PROPERTIES.get((byte) this.getPropertyIdentifier().getValue());
                if (tag != null)
                    writeBACnetTag(buf_apdu, tag);
                else return null;
            } else if (BACNET_OBJECT_TYPE_SUPPORTED.contains((byte) oid.getObject_type()) && this.getPropertyIdentifier().getValue() == BACNET_PROPERTY_IDENTIFIER_PRSNT_VAL) {
                if(simulationList == null){
                    return null;
                }
                Optional<Simulation> simulationOpt = simulationList.stream().filter(e -> Objects.requireNonNull(BACnetObjectIdentifier.parseSimulationTarget(e.getTarget())).equals(oid)).findAny();
                if (simulationOpt.isPresent()) {
                    Simulation simulation = simulationOpt.get();
                    Float value = bacnetService.getSimulationValues().get(simulation.getId());
                    if (value == null) {
                        value = Float.NaN;
                    }
                    BACnetReal real = new BACnetReal(value);
                    real.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                    real.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_REAL);
                    real.setLength((byte) 4);
                    writeBACnetTag(buf_apdu, real);
                } else return null;
            } else return null;
        }

        BACnetUnsignedInteger closingTag = new BACnetUnsignedInteger(0);
        closingTag.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        closingTag.setTagNumber(bracketTagNumber);
        closingTag.setLength(BACNET_NAMED_TAG_CLOSING_TAG);
        writeBACnetTag(buf_apdu, closingTag);
        mergeBACnetBuffers(buf, buf_bvlc, buf_npdu, buf_apdu);
        return buf;
    }
}
