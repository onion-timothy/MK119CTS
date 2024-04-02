package com.onionsoftware.mk119.commtestsuite.io.netty.tool;

import com.google.common.primitives.Longs;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.*;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

public class BACnetByteBufUtils {
    private static void writeObjectIdentifier(ByteBuf out, BACnetObjectIdentifier oid, int length) {
        int bitSpace = length * 8 - 10;
        int base = (int) Math.pow(2, bitSpace + 1) - 1;

        out.writeInt((oid.getObject_type() << bitSpace) + (oid.getInstance_number() & base));
    }

    private static BACnetObjectIdentifier readObjectIdentifier(ByteBuf in, int length) {
        int input = in.readInt();

        int bitSpace = length * 8 - 10;
        int base = (int) Math.pow(2, bitSpace) - 1;

        return new BACnetObjectIdentifier((short) (input >> bitSpace), input & base);
    }

    private static BACnetUnsignedInteger readUnsignedInteger(ByteBuf in, int length) {
        BACnetUnsignedInteger bui = new BACnetUnsignedInteger(-1);
        if (length == 1) {
            bui.setValue(in.readUnsignedByte());
        } else if (length == 2) {
            bui.setValue(in.readUnsignedShort());
        } else if (length == 4) {
            bui.setValue(in.readUnsignedInt());
        } else if (length == 6 || length == 7) {
            bui.setValue(length);
        }
        return bui;
    }

    public static BACnetTag readBACnetTag(ByteBuf in, int serviceChoice) throws BACnetParseException {
        BACnetTag ret = null;
        byte class_tag_length = in.readByte();
        byte tagClass = (byte) ((class_tag_length >> 3) & 1);
        byte tagNumber = (byte) ((class_tag_length >> 4) & 0xf);
        byte length = (byte) (class_tag_length & 7);

        if (tagClass == BACNET_TAG_CLASS_APPLICATION) {
            if (tagNumber == BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER) {
                ret = readUnsignedInteger(in, length);
            } else if (tagNumber == BACNET_APPLICATION_TAG_NUMBER_ENUMERATED) {
                BACnetEnumerated be = new BACnetEnumerated();
                if (length == 1) {
                    be.setEnumeratedValue(in.readByte());
                } else throw new BACnetParseException("Length of BACnet Emum must be 1.");
                ret = be;
            } else if (tagNumber == BACNET_APPLICATION_TAG_NUMBER_OBJECT_IDENTIFIER) {
                if (length >= 2) {
                    ret = readObjectIdentifier(in, length);
                } else throw new BACnetParseException("Length of BACnet OID must bigger than 2.");
            } else if (tagNumber == BACNET_APPLICATION_TAG_NUMBER_REAL) {
                ret = new BACnetReal(in.readFloat());
            }
        } else {
            if (serviceChoice == BACNET_APDU_SERVICE_WHO_IS) {
                ret = readUnsignedInteger(in, length);
            } else if (serviceChoice == BACNET_APDU_SERVICE_READ_PROPERTY || serviceChoice == BACNET_APDU_SERVICE_WRITE_PROPERTY) {
                if (tagNumber == 0) {
                    ret = readObjectIdentifier(in, length);
                } else if (tagNumber == 1) {
                    ret = readUnsignedInteger(in, length);
                } else if (tagNumber == 3) {
                    // Closing, Opeing Tag
                } else if (tagNumber == 4) {
                    ret = readUnsignedInteger(in, length);
                }
            } else if (serviceChoice == BACNET_APDU_SERVICE_READ_PROPERTY_MULTIPLE) {
                if (tagNumber == 0 && length == 4) { // Context Tag, Tag 0, Length 4 -> OID
                    ret = readObjectIdentifier(in, length);
                } else if (tagNumber == 1 || tagNumber == 0 && length == 1) {
                    // Context Tag, Tag 1, Length 1 -> Opening, Closing Tag
                    // Context Tag, Tag 0, Length 1 -> Property Identifier
                    ret = readUnsignedInteger(in, length);
                    ret.setTagClass(tagClass);
                    ret.setTagNumber(tagNumber);
                    ret.setLength(length);
                }
            }

        }
        return ret;
    }

    public static void writeBACnetTag(ByteBuf out, BACnetTag tag) {
        byte length_value_type = 0;
        length_value_type += (byte) (tag.getTagClass() << 3);
        length_value_type += (byte) (tag.getTagNumber() << 4);
        length_value_type += (byte) (tag.getLength() & 0x7);

        if (!(tag instanceof BACnetBoolean))
            out.writeByte(length_value_type);
        if (tag.getLength() == BACNET_NAMED_TAG_EXTENDED_VALUE) {
            out.writeByte(tag.getExtendedLengthValue());
        }
        if (tag instanceof BACnetUnsignedInteger) {
            BACnetUnsignedInteger bui = (BACnetUnsignedInteger) tag;
            if (tag.getLength() == 1) {
                out.writeByte((byte) bui.getValue());
            } else if (tag.getLength() == 2) {
                out.writeShort((short) bui.getValue());
            } else if (tag.getLength() == 4) {
                out.writeInt((int) bui.getValue());
            }
        } else if (tag instanceof BACnetEnumerated) {
            BACnetEnumerated enumerated = (BACnetEnumerated) tag;
            out.writeByte(enumerated.getEnumeratedValue());
        } else if (tag instanceof BACnetObjectIdentifier) {
            BACnetObjectIdentifier oid = (BACnetObjectIdentifier) tag;
            writeObjectIdentifier(out, oid, tag.getLength());
        } else if (tag instanceof BACnetCharacterString) {
            BACnetCharacterString string = (BACnetCharacterString) tag;
            out.writeByte(string.getEncoding());
            out.writeBytes(string.getCharacterString().getBytes(StandardCharsets.UTF_8));
        } else if (tag instanceof BACnetBitString) {
            BACnetBitString bitString = (BACnetBitString) tag;
            out.writeByte(bitString.getUnusedBits());
            byte[] bytes = Longs.toByteArray(bitString.getBitString());
            for (int i = 8 - bitString.getExtendedLengthValue() + 1; i < bytes.length; i++) {
                out.writeByte(bytes[i]);
            }
        } else if (tag instanceof BACnetReal) {
            BACnetReal real = (BACnetReal) tag;
            out.writeFloat(real.getValue());
        } else if (tag instanceof BACnetBoolean) {
            BACnetBoolean bool = (BACnetBoolean) tag;
            length_value_type += (byte) (bool.isValue() ? 1 : 0);
            out.writeByte(length_value_type);
        }
    }

    public static void writeBACnetBVLC(ByteBuf buf_bvlc) {
        buf_bvlc.writeByte(BACNET_BVLC_TYPE_BACNET_IP_ANNEX_J);
        buf_bvlc.writeByte(BACNET_BVLC_FUNCTION_ORIGINAL_UNICAST_NPDU);
    }

    public static void writeBACnetNPDU(ByteBuf buf_npdu, int deviceInstanceNumber) {
        buf_npdu.writeByte(BACNET_NPDU_VERSION_ASHRAE_135_1995);
        buf_npdu.writeByte(BACNET_NPDU_CONTROL_SRC_SPEC);
        buf_npdu.writeShort(2);
        buf_npdu.writeByte(6);
        for (int i = 0; i < 5; i++) {
            buf_npdu.writeByte(0);
        }
        buf_npdu.writeByte(deviceInstanceNumber);
    }

    public static void mergeBACnetBuffers(ByteBuf buf, ByteBuf buf_bvlc, ByteBuf buf_npdu, ByteBuf buf_apdu) {
        buf_bvlc.writeShort(4 + buf_npdu.readableBytes() + buf_apdu.readableBytes());

        buf.writeBytes(buf_bvlc);
        buf.writeBytes(buf_npdu);
        buf.writeBytes(buf_apdu);
    }

    public static void writeOpeningTag(ByteBuf out, byte tagNumber) {
        BACnetUnsignedInteger openingTag = new BACnetUnsignedInteger(0);
        openingTag.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        openingTag.setTagNumber(tagNumber);
        openingTag.setLength(BACNET_NAMED_TAG_OPENING_TAG);
        writeBACnetTag(out, openingTag);
    }

    public static void writeClosingTag(ByteBuf out, byte tagNumber) {
        BACnetUnsignedInteger closingTag = new BACnetUnsignedInteger(0);
        closingTag.setTagClass(BACNET_TAG_CLASS_CONTEXT);
        closingTag.setTagNumber(tagNumber);
        closingTag.setLength(BACNET_NAMED_TAG_CLOSING_TAG);
        writeBACnetTag(out, closingTag);
    }
}
