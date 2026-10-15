package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.*;

import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;
import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.writeBACnetBVLC;

@Getter
@Setter
public class BACnetReadPropertyMultiple extends BACnetPacket {
    static Logger logger = LoggerFactory.getLogger("io");
    List<BACnetObjectIdentifier> oids;
    Map<BACnetObjectIdentifier, List<Byte>> propertyIdentifiers;

    @Autowired
    BACnetService bacnetService;

    public BACnetReadPropertyMultiple(BACnetPacket packet) {
        this.setBvlc_type(packet.getBvlc_type());
        this.setBvlc_func(packet.getBvlc_func());
        this.setBvlc_len(packet.getBvlc_len());
        this.setBacnetNPDU(packet.getBacnetNPDU());
        this.setBacnetAPDU(packet.getBacnetAPDU());
        this.oids = new ArrayList<>();
        this.propertyIdentifiers = new LinkedHashMap<>();
    }

    public ByteBuf encodeBACnetReadPropertyMultipleResponse(Device device) {
        int deviceInstanceNumber = Integer.parseInt(device.getCommunicationSettings().get("deviceInstanceId").toString());

        ByteBuf buf = Unpooled.buffer();
        ByteBuf buf_bvlc = Unpooled.buffer();
        ByteBuf buf_npdu = Unpooled.buffer();
        ByteBuf buf_apdu = Unpooled.buffer();

        writeBACnetBVLC(buf_bvlc);
        writeBACnetNPDU(buf_npdu, deviceInstanceNumber);

        buf_apdu.writeByte(BACNET_APDU_TYPE_COMPLEX_ACK << 4);
        buf_apdu.writeByte(this.getBacnetAPDU().getInvoke_id());
        buf_apdu.writeByte(BACNET_APDU_SERVICE_READ_PROPERTY_MULTIPLE);

        for (BACnetObjectIdentifier oid : this.getOids()) {
            oid.setTagClass(BACNET_TAG_CLASS_CONTEXT);
            oid.setTagNumber((byte) 0);
            oid.setLength((byte) 4);

            writeBACnetTag(buf_apdu, oid);
            List<Byte> props = this.getPropertyIdentifiers().get(oid);
            writeOpeningTag(buf_apdu, (byte) 1);
            if (props.size() == 1 && props.get(0) == BACNET_PROPERTY_IDENTIFIER_ALL && oid.getObject_type() == BACNET_OBJECT_TYPE_device) {
                writePropIdAllCommonProps(buf_apdu, oid);

                BACnetCharacterString oname = new BACnetCharacterString(device.getName(), BACNET_CHARACTER_STRING_ENCODING_UTF8);
                oname.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                oname.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
                oname.setLength((byte) 5);
                oname.setExtendedLengthValue((byte) (device.getName().getBytes().length + 1));
                writeOpeningTag(buf_apdu, (byte) 4);
                writeBACnetTag(buf_apdu, oname);
                writeClosingTag(buf_apdu, (byte) 4);

                BACnetUnsignedInteger olist = new BACnetUnsignedInteger(BACNET_PROPERTY_IDENTIFIER_OBJ_LST);
                olist.setTagClass(BACNET_TAG_CLASS_CONTEXT);
                olist.setTagNumber((byte) 2);
                olist.setLength((byte) 1);
                writeBACnetTag(buf_apdu, olist);

                writeOpeningTag(buf_apdu, (byte) 4);
                // BACnet 스펙(ASHRAE 135): Device.Object_List 는 device object 자신을 반드시 첫 엔트리로 포함해야 한다.
                oid.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                oid.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_OBJECT_IDENTIFIER);
                oid.setLength((byte) 4);
                writeBACnetTag(buf_apdu, oid);
                List<Simulation> simulationList = bacnetService.getSimulationMap().get(device.getId());
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
                writeClosingTag(buf_apdu, (byte) 4);

                for (Byte key : SIMULATION_DEVICE_PROPERTIES.keySet()) {
                    BACnetTag tag = SIMULATION_DEVICE_PROPERTIES.get(key);
                    BACnetUnsignedInteger prop = new BACnetUnsignedInteger(key);
                    prop.setTagClass(BACNET_TAG_CLASS_CONTEXT);
                    prop.setTagNumber((byte) 2);
                    prop.setLength((byte) 1);
                    writeBACnetTag(buf_apdu, prop);
                    writeOpeningTag(buf_apdu, (byte) 4);
                    if (tag != null)
                        writeBACnetTag(buf_apdu, tag);
                    writeClosingTag(buf_apdu, (byte) 4);
                }
            } else if (props.size() == 1 && props.get(0) == BACNET_PROPERTY_IDENTIFIER_ALL && BACNET_OBJECT_TYPE_SUPPORTED.contains((byte) oid.getObject_type())) {
                writePropIdAllCommonProps(buf_apdu, oid);

                Optional<Simulation> simulationOpt = bacnetService.getSimulationMap().get(device.getId()).stream().filter(e -> Objects.equals(BACnetObjectIdentifier.parseSimulationTarget(e.getTarget()), oid)).findFirst();
                if (simulationOpt.isPresent()) {
                    Simulation simulation = simulationOpt.get();

                    BACnetCharacterString oname = new BACnetCharacterString(oid.toString(), BACNET_CHARACTER_STRING_ENCODING_UTF8);
                    oname.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                    oname.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
                    oname.setLength((byte) 5);
                    oname.setExtendedLengthValue((byte) (oid.toString().getBytes().length + 1));
                    writeOpeningTag(buf_apdu, (byte) 4);
                    writeBACnetTag(buf_apdu, oname);
                    writeClosingTag(buf_apdu, (byte) 4);

                    for (Byte key : SIMULATION_OBJECT_PROPERTIES.keySet()) {
                        BACnetTag tag = SIMULATION_OBJECT_PROPERTIES.get(key);
                        BACnetUnsignedInteger prop = new BACnetUnsignedInteger(key);
                        prop.setTagClass(BACNET_TAG_CLASS_CONTEXT);
                        prop.setTagNumber((byte) 2);
                        prop.setLength((byte) 1);
                        writeBACnetTag(buf_apdu, prop);
                        writeOpeningTag(buf_apdu, (byte) 4);
                        if (tag != null)
                            writeBACnetTag(buf_apdu, tag);
                        writeClosingTag(buf_apdu, (byte) 4);
                    }

                    BACnetUnsignedInteger prop_ovalue = new BACnetUnsignedInteger(BACNET_PROPERTY_IDENTIFIER_PRSNT_VAL);
                    prop_ovalue.setTagClass(BACNET_TAG_CLASS_CONTEXT);
                    prop_ovalue.setTagNumber((byte) 2);
                    prop_ovalue.setLength((byte) 1);
                    writeBACnetTag(buf_apdu, prop_ovalue);

                    writeOpeningTag(buf_apdu, (byte) 4);
                    Float value = bacnetService.getSimulationValues().get(simulation.getId());
                    if (value == null) {
                        value = Float.NaN;
                    }
                    BACnetReal real = new BACnetReal(value);
                    real.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                    real.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_REAL);
                    real.setLength((byte) 4);
                    writeBACnetTag(buf_apdu, real);
                    writeClosingTag(buf_apdu, (byte) 4);
                }
            } else {
                for (Byte propertyIdentifier : props) {
                    BACnetTag tag = SIMULATION_DEVICE_PROPERTIES.get(propertyIdentifier);

                    BACnetUnsignedInteger prop = new BACnetUnsignedInteger(propertyIdentifier);
                    prop.setTagClass(BACNET_TAG_CLASS_CONTEXT);
                    prop.setTagNumber((byte) 2);
                    prop.setLength((byte) 1);
                    writeBACnetTag(buf_apdu, prop);

                    writeOpeningTag(buf_apdu, (byte) 4);
                    if (SIMULATION_DEVICE_PROPERTIES.containsKey(propertyIdentifier)) {
                        writeBACnetTag(buf_apdu, tag);
                    } else if (propertyIdentifier == BACNET_PROPERTY_IDENTIFIER_OBJ_NME) {
                        BACnetCharacterString name = new BACnetCharacterString(device.getName(), BACNET_CHARACTER_STRING_ENCODING_UTF8);
                        name.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                        name.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
                        name.setLength(BACNET_NAMED_TAG_EXTENDED_VALUE);
                        name.setExtendedLengthValue((byte) (device.getName().getBytes().length + 1));
                        writeBACnetTag(buf_apdu, name);
                    } else if (propertyIdentifier == BACNET_PROPERTY_IDENTIFIER_PRSNT_VAL) {
                        if (bacnetService.getSimulationMap().get(device.getId()) == null) {
                            return null;
                        }
                        Optional<Simulation> simulationOpt = bacnetService.getSimulationMap().get(device.getId()).stream().filter(e -> Objects.equals(BACnetObjectIdentifier.parseSimulationTarget(e.getTarget()), oid)).findFirst();
                        if (simulationOpt.isPresent()) {
                            Float value = bacnetService.getSimulationValues().get(simulationOpt.get().getId());
                            if (value == null) {
                                value = Float.NaN;
                            }
                            BACnetReal real = new BACnetReal(value);
                            real.setTagClass(BACNET_TAG_CLASS_APPLICATION);
                            real.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_REAL);
                            real.setLength((byte) 4);
                            writeBACnetTag(buf_apdu, real);
                        }
                    }
                    writeClosingTag(buf_apdu, (byte) 4);
                }
            }
            writeClosingTag(buf_apdu, (byte) 1);
        }
        mergeBACnetBuffers(buf, buf_bvlc, buf_npdu, buf_apdu);

        return buf;
    }

    private static void writePropIdAllCommonProps(ByteBuf buf_apdu, BACnetObjectIdentifier oid) {
        BACnetUnsignedInteger prop_oid = new BACnetUnsignedInteger(BACNET_PROPERTY_IDENTIFIER_OBJ_ID);
        prop_oid.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        prop_oid.setTagNumber((byte) 2);
        prop_oid.setLength((byte) 1);
        writeBACnetTag(buf_apdu, prop_oid);

        writeOpeningTag(buf_apdu, (byte) 4);
        oid.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        oid.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_OBJECT_IDENTIFIER);
        oid.setLength((byte) 4);
        writeBACnetTag(buf_apdu, oid);
        writeClosingTag(buf_apdu, (byte) 4);

        BACnetUnsignedInteger prop_otype = new BACnetUnsignedInteger(BACNET_PROPERTY_IDENTIFIER_OBJ_TYP);
        prop_otype.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        prop_otype.setTagNumber((byte) 2);
        prop_otype.setLength((byte) 1);
        writeBACnetTag(buf_apdu, prop_otype);

        BACnetUnsignedInteger otype = new BACnetUnsignedInteger(oid.getObject_type());
        otype.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        otype.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
        otype.setLength((byte) 1);
        writeOpeningTag(buf_apdu, (byte) 4);
        writeBACnetTag(buf_apdu, otype);
        writeClosingTag(buf_apdu, (byte) 4);

        BACnetUnsignedInteger prop_oname = new BACnetUnsignedInteger(BACNET_PROPERTY_IDENTIFIER_OBJ_NME);
        prop_oname.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        prop_oname.setTagNumber((byte) 2);
        prop_oname.setLength((byte) 1);
        writeBACnetTag(buf_apdu, prop_oname);
    }
}
