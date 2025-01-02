package com.onionsoftware.mk119.commtestsuite.db.bean.converter;

import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;

import javax.persistence.AttributeConverter;

public class ErrorModeIntegerConverter implements AttributeConverter<ErrorMode, Integer> {
    @Override
    public Integer convertToDatabaseColumn(ErrorMode errorMode) {
        return errorMode.value();
    }

    @Override
    public ErrorMode convertToEntityAttribute(Integer integer) {
        return ErrorMode.values()[integer];
    }
}