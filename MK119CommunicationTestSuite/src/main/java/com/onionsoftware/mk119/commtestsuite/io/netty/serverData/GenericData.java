package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class GenericData extends ServerData {
    private final Map<String, Double> txFieldsIdValueMap;

    public GenericData(Map<String, Double> txFieldsIdValueMap) {
        this.txFieldsIdValueMap = txFieldsIdValueMap;
    }

    public GenericData() {
        this.txFieldsIdValueMap = new java.util.concurrent.ConcurrentHashMap<>();   // Value Simulation 과 패킷 처리가 함께 쓴다
    }

    @Override
    public void resetServerData() {
        txFieldsIdValueMap.clear();
    }
}
