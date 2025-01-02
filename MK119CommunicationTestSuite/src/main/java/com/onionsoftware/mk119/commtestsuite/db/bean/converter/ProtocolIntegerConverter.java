package com.onionsoftware.mk119.commtestsuite.db.bean.converter;

import com.onionsoftware.mk119.commtestsuite.enums.Protocol;

import javax.persistence.AttributeConverter;

public class ProtocolIntegerConverter implements AttributeConverter<Protocol, Integer> {


    @Override
    public Integer convertToDatabaseColumn(Protocol protocol) {
        return protocol.value();
    }

    @Override
    public Protocol convertToEntityAttribute(Integer integer) {
        return Protocol.values()[integer - 1];
    }
}
