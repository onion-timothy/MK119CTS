package com.onionsoftware.mk119.commtestsuite.io.bacnet.tag;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.BitSet;

@Getter
@Setter
@AllArgsConstructor
public class BACnetBitString extends BACnetTag {
    private long bitString;
    private byte unusedBits;
}
