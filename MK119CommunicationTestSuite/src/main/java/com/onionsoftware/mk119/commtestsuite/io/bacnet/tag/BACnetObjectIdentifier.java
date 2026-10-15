package com.onionsoftware.mk119.commtestsuite.io.bacnet.tag;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.lang.reflect.Field;

import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BACnetObjectIdentifier extends BACnetTag {
    private short object_type;
    private int instance_number;

    /**
     * 시뮬레이션 target 을 BACnet Object Identifier 로 해석한다.
     * 형식은 {@code "<object_type> <instance_number>"} (예: {@code "analog_input 1"}).
     * <p>
     * 형식이 틀리면 <b>예외 대신 null</b> 을 반환한다. 이 메서드는 장비의 전체 시뮬레이션을
     * 순회하는 읽기 응답 경로에서 호출되므로, 예외를 던지면 잘못된 target 하나가 그 장비의
     * 응답 전체를 무응답으로 만든다.
     *
     * @return 해석된 OID, 또는 target 이 형식에 맞지 않으면 null
     */
    public static BACnetObjectIdentifier parseSimulationTarget(String target) {
        if (target == null) {
            return null;
        }
        String[] objectTokens = target.trim().split("\\s+");
        if (objectTokens.length < 2) {
            return null;
        }

        int instanceNumber;
        try {
            instanceNumber = Integer.parseInt(objectTokens[1]);
        } catch (NumberFormatException e) {
            return null;
        }

        byte typeByte;
        try {
            Field field = BACnetConstants.class.getField("BACNET_OBJECT_TYPE_" + objectTokens[0]);
            // IllegalArgumentException: byte 가 아닌 상수(BACNET_OBJECT_TYPE_device 는 short)를 가리킨 경우
            typeByte = field.getByte(null);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            return null;
        }
        return new BACnetObjectIdentifier(typeByte, instanceNumber);
    }

    /** target 이 형식에 맞고 주어진 oid 와 같은지. 형식이 틀린 target 은 조용히 false. */
    public static boolean matches(String target, BACnetObjectIdentifier oid) {
        BACnetObjectIdentifier parsed = parseSimulationTarget(target);
        return parsed != null && parsed.equals(oid);
    }

    /** 시뮬레이션 target 으로 쓸 수 있는 형식인지. 등록 시 검증용. */
    public static boolean isValidSimulationTarget(String target) {
        return parseSimulationTarget(target) != null;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BACnetObjectIdentifier) {
            BACnetObjectIdentifier oid = (BACnetObjectIdentifier) o;
            return oid.getObject_type() == this.getObject_type() && oid.getInstance_number() == this.getInstance_number();
        }
        return false;
    }

    @Override
    public String toString() {
        return this.typeToString() + this.instance_number;
    }

    private String typeToString() {
        switch (this.getObject_type()) {
            case BACNET_OBJECT_TYPE_analog_input:
                return "Analog Input ";
            case BACNET_OBJECT_TYPE_analog_output:
                return "Analog Output ";
            case BACNET_OBJECT_TYPE_analog_value:
                return "Analog Value ";
            case BACNET_OBJECT_TYPE_binary_input:
                return "Binary Input ";
            case BACNET_OBJECT_TYPE_binary_output:
                return "Binary Output ";
            case BACNET_OBJECT_TYPE_binary_value:
                return "Binary Value ";
            case BACNET_OBJECT_TYPE_multi_state_input:
                return "Multi-state Input ";
            case BACNET_OBJECT_TYPE_multi_state_output:
                return "Multi-state Output ";
            case BACNET_OBJECT_TYPE_multi_state_value:
                return "Multi-state Value ";
        }
        return "BACnet Object ";
    }
}