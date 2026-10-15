package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class SnmpData extends ServerData {
    private final Map<String, Double> oidValueMap = new HashMap<>();
    @Override
    public void resetServerData() {
        oidValueMap.clear();
    }
}
