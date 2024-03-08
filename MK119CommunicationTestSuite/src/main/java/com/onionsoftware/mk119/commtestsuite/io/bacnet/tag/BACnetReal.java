package com.onionsoftware.mk119.commtestsuite.io.bacnet.tag;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class BACnetReal extends BACnetTag {
    private float value;
}
