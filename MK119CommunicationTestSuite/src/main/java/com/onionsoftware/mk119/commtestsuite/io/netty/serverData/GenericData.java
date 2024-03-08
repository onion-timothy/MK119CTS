package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import java.util.HashMap;
import java.util.Map;

public class GenericData implements ServerData {
    private final Map<String, Double> txFieldsIdValueMap;

    public Map<String, Double> getTxFieldsIdValueMap() {
        return this.txFieldsIdValueMap;
    }

    public GenericData(Map<String, Double> txFieldsIdValueMap){
        this.txFieldsIdValueMap = txFieldsIdValueMap;
    }

    public GenericData(){
        this.txFieldsIdValueMap = new HashMap<>();
    }
}
