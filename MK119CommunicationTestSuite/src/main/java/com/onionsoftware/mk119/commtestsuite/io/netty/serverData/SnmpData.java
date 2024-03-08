package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import java.util.HashMap;
import java.util.Map;

public class SnmpData implements ServerData {
    private final Map<String, Double> oidValueMap = new HashMap<>();

    public Map<String, Double> getOidValueMap() {
        return this.oidValueMap;
    }
}
