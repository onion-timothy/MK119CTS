package com.onionsoftware.mk119.commtestsuite.db.bean.converter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.persistence.AttributeConverter;
import java.lang.reflect.Type;
import java.util.Map;

public class JsonStringConfigMapConverter implements AttributeConverter<Map<Object, Object>, String> {
    static Gson parser = new Gson();
    static Type map = new TypeToken<Map<Object, Object>>() {}.getType();

    @Override
    public String convertToDatabaseColumn(Map<Object, Object> objectObjectMap) {
        return parser.toJson(objectObjectMap);
    }

    @Override
    public Map<Object, Object> convertToEntityAttribute(String s) {
        return parser.fromJson(s, map);
    }
}
