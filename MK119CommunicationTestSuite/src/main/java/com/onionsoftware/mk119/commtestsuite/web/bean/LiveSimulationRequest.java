package com.onionsoftware.mk119.commtestsuite.web.bean;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LiveSimulationRequest {
    private String target;
    private DataType dataType;
    private double value;
}
