package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Policy;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SimulationPolicy {
    private String id;
    private Policy policy;
    private DataType data_type;
    private Double value;
    private List<Double> range;
    private Boolean accept_control;
}
