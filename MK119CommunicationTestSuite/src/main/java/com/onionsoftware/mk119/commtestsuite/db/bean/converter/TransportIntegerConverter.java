package com.onionsoftware.mk119.commtestsuite.db.bean.converter;

import com.onionsoftware.mk119.commtestsuite.enums.Transport;

import javax.persistence.AttributeConverter;

public class TransportIntegerConverter implements AttributeConverter<Transport, Integer> {

    @Override
    public Integer convertToDatabaseColumn(Transport transport) {
        return transport.value();
    }

    @Override
    public Transport convertToEntityAttribute(Integer integer) {
        return Transport.values()[integer - 1];
    }
}
