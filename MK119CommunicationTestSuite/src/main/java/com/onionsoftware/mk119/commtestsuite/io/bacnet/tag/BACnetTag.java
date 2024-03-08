package com.onionsoftware.mk119.commtestsuite.io.bacnet.tag;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BACnetTag {
    private byte tagClass;
    private byte tagNumber;
    private byte length;
    private byte extendedLengthValue;
}
