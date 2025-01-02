package com.onionsoftware.mk119.commtestsuite.db.bean.converter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;

import javax.persistence.AttributeConverter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public class JsonStringPolicyListConverter implements AttributeConverter<List<SimulationPolicy>, String> {
    static Gson parser = new Gson();
    static Type list = new TypeToken<List<SimulationPolicy>>() {
    }.getType();

    @Override
    public String convertToDatabaseColumn(List<SimulationPolicy> simulationPolicies) {
        return parser.toJson(simulationPolicies);
    }

    @Override
    public List<SimulationPolicy> convertToEntityAttribute(String s) {
        return parser.fromJson(s, list);
    }
}
