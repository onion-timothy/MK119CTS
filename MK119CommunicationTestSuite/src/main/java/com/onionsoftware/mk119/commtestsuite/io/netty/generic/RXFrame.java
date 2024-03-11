package com.onionsoftware.mk119.commtestsuite.io.netty.generic;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RXFrame {
    private String rx_id;
    private Integer length;
    private List<RXField> fields;
}
