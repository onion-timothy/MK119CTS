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

    public static BACnetObjectIdentifier parseSimulationTarget(String target) {
        String[] objectTokens = target.split(" ");
        String type = objectTokens[0];
        int instanceNumber = Integer.parseInt(objectTokens[1]);
        byte typeByte = -1;

        try {
            Field field = BACnetConstants.class.getField("BACNET_OBJECT_TYPE_" + type);
            typeByte = field.getByte(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
        return new BACnetObjectIdentifier(typeByte, instanceNumber);
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