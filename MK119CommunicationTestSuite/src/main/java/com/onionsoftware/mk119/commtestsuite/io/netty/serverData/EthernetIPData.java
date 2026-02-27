package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import etherip.types.CIPData;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
public class EthernetIPData implements ServerData {
    Map<String, CIPData> tagValueMap = new HashMap<>();
    private final Random session_generator = new Random(System.currentTimeMillis());

    private final int o2t_incarnation_id = session_generator.nextInt(Integer.MAX_VALUE);
    private final int t2o_incarnation_id = session_generator.nextInt(o2t_incarnation_id);
    private final List<Integer> sessions = new ArrayList<>();
    private int session_counter;

    private short implicit_o_t_port;
    private int implicit_t_o_addr;

    public EthernetIPData(Map<Object, Object> communicationSettings) {
        implicit_o_t_port = Short.parseShort(communicationSettings.get("syn_port").toString());
        String[] token = communicationSettings.get("implicit_t2o_addr").toString().split("\\.");
        implicit_t_o_addr = Integer.parseInt(token[0]) << 24 + Integer.parseInt(token[1]) << 16 + Integer.parseInt(token[2]) << 8 + Integer.parseInt(token[3]);
    }

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

    @Override
    public void resetServerData() {
        tagValueMap.clear();
    }

    public int createSession() {
        int session;
        do {
            session = session_generator.nextInt();
        } while (sessions.contains(session));
        sessions.add(session);
        session_counter++;
        return session;
    }

    public void invalidateSession(int session) {
        sessions.remove(Integer.valueOf(session));
    }


}
