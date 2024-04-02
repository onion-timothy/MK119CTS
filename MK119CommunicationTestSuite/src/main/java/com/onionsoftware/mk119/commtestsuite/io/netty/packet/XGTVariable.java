package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class XGTVariable {
    private short varname_length;
    private String varname;
    private short value;
}
