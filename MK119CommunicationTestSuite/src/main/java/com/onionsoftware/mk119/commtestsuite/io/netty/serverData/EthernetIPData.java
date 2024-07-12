package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import etherip.types.CIPData;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class EthernetIPData implements ServerData {
    Map<String, CIPData> tagValueMap = new HashMap<>();

    /**
     * Simulation에서 값을 넣는다. Tag의 Type Prefix와 value로 CIPData를 Wrapping한다.
     * @param tag Type prefix@Ethernet/IP Tag
     * @param value 시뮬레이션 값
     */
    public void putTagValue(String tag, Double value) {
        String[] tokens = tag.split("@");

        try {
            CIPData.Type type = CIPData.Type.forName(tokens[0]);
            CIPData data = new CIPData(type, 1);
            data.set(0, value);

            tagValueMap.put(tokens[1], data);
        } catch (Exception e) {
            MkLogger.log("simulation", e, "Error while set ethernet/ip simulation value {0}", tag);
        }
    }

    /**
     * EthernetIPHandler에서 Tag로 값을 찾는다. Type Prefix가 없어야 한다.
     * @param tag Ethernet/IP Tag, Type prefix 없음.
     * @return
     */
    public CIPData getTagValue(String tag) {
        return tagValueMap.get(tag);
    }
}
