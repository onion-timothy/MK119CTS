package com.onionsoftware.mk119.commtestsuite.io.bacnet;

import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.*;

import java.util.*;

public class BACnetConstants {
    public static final byte BACNET_BVLC_TYPE_BACNET_IP_ANNEX_J = (byte) 0x81;
    public static final byte BACNET_BVLC_FUNCTION_ORIGINAL_BROADCAST_NPDU = 0x0b;
    public static final byte BACNET_BVLC_FUNCTION_ORIGINAL_UNICAST_NPDU = 0x0a;
    public static final int BACNET_NPDU_VERSION_ASHRAE_135_1995 = 0x01;
    public static final int BACNET_NPDU_CONTROL_DEST_SPEC = 0x20;
    public static final int BACNET_NPDU_CONTROL_DEST_SPEC_EXPT_RPLY = 0x24;
    public static final int BACNET_NPDU_CONTROL_DEST_SRC_SPEC = 0x28;
    public static final int BACNET_NPDU_CONTROL_SRC_SPEC = 0x08;
    // NPDU control 바이트의 개별 비트 (ASHRAE 135 6.2.2). 표준 비트플래그 기반 파싱에 사용한다.
    public static final int BACNET_NPDU_CONTROL_DEST_PRESENT = 0x20; // 목적지 지정(DNET/DLEN/DADR) 존재
    public static final int BACNET_NPDU_CONTROL_SRC_PRESENT = 0x08;  // 소스 지정(SNET/SLEN/SADR) 존재
    public static final int BACNET_NPDU_CONTROL_NET_MSG = 0x80;      // APDU가 아닌 네트워크 계층 메시지
    public static final byte BACNET_APDU_TYPE_UNCONFIRMED_REQ = 0x01;
    public static final byte BACNET_APDU_TYPE_CONFIRMED_REQ = 0x00;
    public static final byte BACNET_APDU_TYPE_COMPLEX_ACK = 0x03;
    public static final byte BACNET_APDU_TYPE_SIMPLE_ACK = 0x02;
    public static final byte BACNET_APDU_SERVICE_WHO_IS = 0x08;
    public static final byte BACNET_APDU_SERVICE_I_AM = 0x00;
    public static final byte BACNET_APDU_SERVICE_READ_PROPERTY = 0x0c;
    public static final byte BACNET_APDU_SERVICE_READ_PROPERTY_MULTIPLE = 0x0e;
    public static final byte BACNET_APDU_SERVICE_WRITE_PROPERTY = 0x0f;
    public static final byte BACNET_TAG_CLASS_APPLICATION = 0x00;
    public static final byte BACNET_TAG_CLASS_CONTEXT = 0x01;
    public static final byte BACNET_APPLICATION_TAG_NUMBER_OBJECT_IDENTIFIER = 0x0c;
    public static final byte BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER = 0x02;
    public static final byte BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING = 0x07;
    public static final byte BACNET_APPLICATION_TAG_NUMBER_BIT_STRING = 0x08;
    public static final byte BACNET_APPLICATION_TAG_NUMBER_BOOLEAN = 0x01;
    public static final byte BACNET_APPLICATION_TAG_NUMBER_REAL = 0x04;
    public static final byte BACNET_APPLICATION_TAG_NUMBER_ENUMERATED = 0x09;
    public static final short BACNET_OBJECT_TYPE_device = 0x08;
    public static final byte BACNET_PROPERTY_IDENTIFIER_ALL = 0x08;
    public static final byte BACNET_PROPERTY_IDENTIFIER_OBJ_LST = 0x4c;
    public static final byte BACNET_PROPERTY_IDENTIFIER_OBJ_NME = 0x4d;
    public static final byte BACNET_PROPERTY_IDENTIFIER_OBJ_ID = 0x4b;
    public static final byte BACNET_PROPERTY_IDENTIFIER_OBJ_TYP = 0x4f;
    public static final byte BACNET_PROPERTY_IDENTIFIER_SYS_STAT = 0x70;
    public static final byte BACNET_PROPERTY_IDENTIFIER_VNDR_NME = 0x79;
    public static final byte BACNET_PROPERTY_IDENTIFIER_VNDR_ID = 0x78;
    public static final byte BACNET_PROPERTY_IDENTIFIER_MDL_NME = 0x46;
    public static final byte BACNET_PROPERTY_IDENTIFIER_REV = 0x2c;
    public static final byte BACNET_PROPERTY_IDENTIFIER_APP_SFTWR_VER = 0x0c;
    public static final byte BACNET_PROPERTY_IDENTIFIER_PRTCL_VER = 0x62;
    public static final byte BACNET_PROPERTY_IDENTIFIER_PRTCL_REV = (byte) 0x8b;
    public static final byte BACNET_PROPERTY_IDENTIFIER_PRTCL_SRVCS_SPRT = 0x61;
    public static final byte BACNET_PROPERTY_IDENTIFIER_PRTCL_OBJ_TYP_SPRT = 0x60;
    public static final byte BACNET_PROPERTY_IDENTIFIER_MAX_ADPU_LEN_ACPT = 0x3e;
    public static final byte BACNET_PROPERTY_IDENTIFIER_SGMT_SPRT = 0x6b;
    public static final byte BACNET_PROPERTY_IDENTIFIER_APDU_TIME = 0x0b;
    public static final byte BACNET_PROPERTY_IDENTIFIER_APDU_RETRY = 0x49;
    public static final byte BACNET_PROPERTY_IDENTIFIER_DEV_ADDR_BIND = 0x1e;
    public static final byte BACNET_PROPERTY_IDENTIFIER_DB_REV = (byte) 0x9b;
    public static final byte BACNET_PROPERTY_IDENTIFIER_DESC = 0x1c;
    public static final byte BACNET_PROPERTY_IDENTIFIER_LOC = 0x3a;
    public static final byte BACNET_PROPERTY_IDENTIFIER_EVT_STAT = 0x24;
    public static final byte BACNET_PROPERTY_IDENTIFIER_OOS = 0x51;
    public static final byte BACNET_PROPERTY_IDENTIFIER_PRSNT_VAL = 0x55;
    public static final byte BACNET_PROPERTY_IDENTIFIER_RELBT = 0x67;
    public static final byte BACNET_PROPERTY_IDENTIFIER_STAT_FLGS = 0x6f;
    public static final byte BACNET_PROPERTY_IDENTIFIER_MX_SEG_ACPT = (byte) 0xa7;
    public static final byte BACNET_PROPERTY_IDENTIFIER_UNITS = 0x75;
    public static final byte BACNET_OBJECT_TYPE_analog_input = 0x00;
    public static final byte BACNET_OBJECT_TYPE_analog_output = 0x01;
    public static final byte BACNET_OBJECT_TYPE_analog_value = 0x02;
    public static final byte BACNET_OBJECT_TYPE_binary_input = 0x03;
    public static final byte BACNET_OBJECT_TYPE_binary_output = 0x04;
    public static final byte BACNET_OBJECT_TYPE_binary_value = 0x05;

    public static final byte BACNET_OBJECT_TYPE_multi_state_input = 0x0D;
    public static final byte BACNET_OBJECT_TYPE_multi_state_output = 0x0E;
    public static final byte BACNET_OBJECT_TYPE_multi_state_value = 0x13;
    public static final byte BACNET_NAMED_TAG_EXTENDED_VALUE = 0x05;
    public static final byte BACNET_NAMED_TAG_OPENING_TAG = 0x06;
    public static final byte BACNET_NAMED_TAG_CLOSING_TAG = 0x07;

    public static final List<Byte> BACNET_OBJECT_TYPE_SUPPORTED = Arrays.asList(
            BACNET_OBJECT_TYPE_analog_input,
            BACNET_OBJECT_TYPE_analog_output,
            BACNET_OBJECT_TYPE_analog_value,
            BACNET_OBJECT_TYPE_binary_input,
            BACNET_OBJECT_TYPE_binary_output,
            BACNET_OBJECT_TYPE_binary_value,
            BACNET_OBJECT_TYPE_multi_state_input,
            BACNET_OBJECT_TYPE_multi_state_output,
            BACNET_OBJECT_TYPE_multi_state_value);

    public static byte BACNET_CHARACTER_STRING_ENCODING_UTF8 = 0;

    public static final Map<Byte, BACnetTag> SIMULATION_DEVICE_PROPERTIES = new LinkedHashMap<>();

    static {
        BACnetUnsignedInteger system_status = new BACnetUnsignedInteger(0);
        system_status.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        system_status.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
        system_status.setLength((byte) 1);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_SYS_STAT, system_status);

        BACnetCharacterString vender_name = new BACnetCharacterString("TIMOTHY_STS", BACNET_CHARACTER_STRING_ENCODING_UTF8);
        vender_name.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        vender_name.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
        vender_name.setLength((byte) 5);
        vender_name.setExtendedLengthValue((byte) ("TIMOTHY_STS".getBytes().length + 1));
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_VNDR_NME, vender_name);

        BACnetUnsignedInteger vendor_id = new BACnetUnsignedInteger(61440);
        vendor_id.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        vendor_id.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
        vendor_id.setLength((byte) 2);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_VNDR_ID, vendor_id);

        BACnetCharacterString model_name = new BACnetCharacterString("SIMULATION_DEVICE", BACNET_CHARACTER_STRING_ENCODING_UTF8);
        model_name.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        model_name.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
        model_name.setLength((byte) 5);
        model_name.setExtendedLengthValue((byte) ("SIMULATION_DEVICE".getBytes().length + 1));
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_MDL_NME, model_name);

        BACnetCharacterString firmware_version = new BACnetCharacterString("1.0.0", BACNET_CHARACTER_STRING_ENCODING_UTF8);
        firmware_version.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        firmware_version.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
        firmware_version.setLength((byte) 5);
        firmware_version.setExtendedLengthValue((byte) ("1.0.0".getBytes().length + 1));
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_REV, firmware_version);

        BACnetCharacterString application_software_version = new BACnetCharacterString("1.0.0", BACNET_CHARACTER_STRING_ENCODING_UTF8);
        application_software_version.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        application_software_version.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
        application_software_version.setLength((byte) 5);
        application_software_version.setExtendedLengthValue((byte) ("1.0.0".getBytes().length + 1));
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_APP_SFTWR_VER, application_software_version);

        BACnetUnsignedInteger protocol_version = new BACnetUnsignedInteger(1);
        protocol_version.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        protocol_version.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
        protocol_version.setLength((byte) 1);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_PRTCL_VER, protocol_version);

        BACnetUnsignedInteger protocol_revision = new BACnetUnsignedInteger(14);
        protocol_revision.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        protocol_revision.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
        protocol_revision.setLength((byte) 1);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_PRTCL_REV, protocol_revision);

        BACnetBitString protocol_services_supported = new BACnetBitString(0x44_0b_48_28_aa_00L, (byte) 6);
        protocol_services_supported.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        protocol_services_supported.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_BIT_STRING);
        protocol_services_supported.setLength((byte) 5);
        protocol_services_supported.setExtendedLengthValue((byte) 7);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_PRTCL_SRVCS_SPRT, protocol_services_supported);

        BACnetBitString protocol_object_type = new BACnetBitString(0xa4_80_10_00_00_80_00_00L, (byte) 3);
        protocol_object_type.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        protocol_object_type.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_BIT_STRING);
        protocol_object_type.setLength((byte) 5);
        protocol_object_type.setExtendedLengthValue((byte) 9);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_PRTCL_OBJ_TYP_SPRT, protocol_object_type);

        BACnetUnsignedInteger max_apdu_length_supported = new BACnetUnsignedInteger(1476);
        max_apdu_length_supported.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        max_apdu_length_supported.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
        max_apdu_length_supported.setLength((byte) 2);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_MAX_ADPU_LEN_ACPT, max_apdu_length_supported);

        BACnetUnsignedInteger segmentation_supported = new BACnetUnsignedInteger(0);
        segmentation_supported.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        segmentation_supported.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
        segmentation_supported.setLength((byte) 1);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_SGMT_SPRT, segmentation_supported);

        BACnetUnsignedInteger apdu_timeout = new BACnetUnsignedInteger(3000);
        apdu_timeout.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        apdu_timeout.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
        apdu_timeout.setLength((byte) 2);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_APDU_TIME, apdu_timeout);

        BACnetUnsignedInteger apdu_retries = new BACnetUnsignedInteger(3);
        apdu_retries.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        apdu_retries.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
        apdu_retries.setLength((byte) 2);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_APDU_RETRY, apdu_retries);

        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_DEV_ADDR_BIND, null);

        BACnetUnsignedInteger database_revision = new BACnetUnsignedInteger(0);
        database_revision.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        database_revision.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
        database_revision.setLength((byte) 1);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_DB_REV, database_revision);

        BACnetCharacterString description = new BACnetCharacterString("MK119 CTS BACNET SIMULATOR DEVICE", BACNET_CHARACTER_STRING_ENCODING_UTF8);
        description.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        description.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
        description.setLength((byte) 5);
        description.setExtendedLengthValue((byte) ("MK119 CTS BACNET SIMULATOR DEVICE".getBytes().length + 1));
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_DESC, description);

        BACnetCharacterString location = new BACnetCharacterString("KR", BACNET_CHARACTER_STRING_ENCODING_UTF8);
        location.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        location.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
        location.setLength((byte) 5);
        location.setExtendedLengthValue((byte) ("KR".getBytes().length + 1));
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_LOC, location);

        BACnetUnsignedInteger max_segments_accpeted = new BACnetUnsignedInteger(32);
        max_segments_accpeted.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        max_segments_accpeted.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_UNSIGNED_INTEGER);
        max_segments_accpeted.setLength((byte) 1);
        SIMULATION_DEVICE_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_MX_SEG_ACPT, max_segments_accpeted);
    }

    public static final Map<Byte, BACnetTag> SIMULATION_OBJECT_PROPERTIES = new LinkedHashMap<>();

    static {
        BACnetCharacterString description = new BACnetCharacterString("MK119 CTS BACNET SIMULATOR OBJECT", BACNET_CHARACTER_STRING_ENCODING_UTF8);
        description.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        description.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_CHARACTER_STRING);
        description.setLength((byte) 5);
        description.setExtendedLengthValue((byte) ("MK119 CTS BACNET SIMULATOR OBJECT".getBytes().length + 1));
        SIMULATION_OBJECT_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_DESC, description);

        BACnetUnsignedInteger eventState = new BACnetUnsignedInteger(0);
        eventState.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        eventState.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
        eventState.setLength((byte) 1);
        SIMULATION_OBJECT_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_EVT_STAT, eventState);

        BACnetBoolean out_of_service = new BACnetBoolean(false);
        out_of_service.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        out_of_service.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_BOOLEAN);
        out_of_service.setLength((byte) 0);
        SIMULATION_OBJECT_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_OOS, out_of_service);

        BACnetUnsignedInteger reliability = new BACnetUnsignedInteger(0);
        reliability.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        reliability.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
        reliability.setLength((byte) 1);
        SIMULATION_OBJECT_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_RELBT, reliability);

        BACnetBitString status_flags = new BACnetBitString(0x0000L, (byte) 4);
        status_flags.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        status_flags.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_BIT_STRING);
        status_flags.setLength((byte) 5);
        status_flags.setExtendedLengthValue((byte) 2);
        SIMULATION_OBJECT_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_STAT_FLGS, status_flags);

        BACnetUnsignedInteger units = new BACnetUnsignedInteger((byte) 0x5f);
        units.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        units.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
        units.setLength((byte) 1);
        SIMULATION_OBJECT_PROPERTIES.put(BACNET_PROPERTY_IDENTIFIER_UNITS, units);
    }
}
