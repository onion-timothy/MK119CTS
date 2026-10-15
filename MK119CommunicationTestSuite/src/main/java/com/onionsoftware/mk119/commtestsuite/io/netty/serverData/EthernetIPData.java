package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import etherip.types.CIPData;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
public class EthernetIPData extends ServerData {
    Map<String, CIPData> tagValueMap = new HashMap<>();
    private final Random session_generator = new Random(System.currentTimeMillis());

    private final int o2t_incarnation_id = session_generator.nextInt(Integer.MAX_VALUE);
    private final int t2o_incarnation_id = session_generator.nextInt(o2t_incarnation_id);
    private final List<Integer> sessions = new ArrayList<>();
    private int session_counter;

    private short implicit_o_t_port;
    private int implicit_t_o_addr;

    /**
     * EtherNet/IP 는 패킷에 태그 이름만 실려 온다. target 은 {@code REAL@TAG1} 형식이므로
     * {@code TAG1} 로도 찾을 수 있도록 보조 키를 단다.
     */
    @Override
    protected Collection<String> aliasesOf(
            com.onionsoftware.mk119.commtestsuite.db.bean.Simulation simulation) {
        String target = simulation.getTarget();
        int at = target == null ? -1 : target.indexOf('@');
        if (at < 0 || at + 1 >= target.length()) return Collections.emptyList();
        return Collections.singletonList(target.substring(at + 1));
    }

    public EthernetIPData(Map<Object, Object> communicationSettings) {
        implicit_o_t_port = Short.parseShort(communicationSettings.get("syn_port").toString());
        String[] token = communicationSettings.get("implicit_t2o_addr").toString().split("\\.");
        implicit_t_o_addr = Integer.parseInt(token[0]) << 24 + Integer.parseInt(token[1]) << 16 + Integer.parseInt(token[2]) << 8 + Integer.parseInt(token[3]);
    }

    /**
     * Simulation에서 값을 넣는다. Tag의 Type Prefix와 value로 CIPData를 Wrapping한다.
     * <p>
     * 실패를 삼키지 않고 호출자에게 던진다. 예전에는 로그만 남겼기 때문에,
     * {@code @} 가 없는 잘못된 target 을 REST 로 보내면 아무것도 쓰이지 않았는데도
     * 200 이 나가고 lock 까지 걸렸다.
     *
     * @param tag Type prefix@Ethernet/IP Tag
     * @param value 시뮬레이션 값
     * @throws IllegalArgumentException target 형식이 {@code <CIPType>@<Tag>} 가 아니거나 CIPType 이 없을 때
     */
    public void putTagValue(String tag, Double value) {
        String[] tokens = tag.split("@");
        if (tokens.length < 2 || tokens[0].isEmpty() || tokens[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "EthernetIP target must be \"<CIPType>@<Tag>\" (e.g. REAL@TAG1). target=" + tag);
        }

        CIPData.Type type = CIPData.Type.forName(tokens[0]);
        if (type == null) {
            throw new IllegalArgumentException("Unknown CIP type \"" + tokens[0] + "\". target=" + tag);
        }

        try {
            CIPData data = new CIPData(type, 1);
            data.set(0, value);
            tagValueMap.put(tokens[1], data);
        } catch (Exception e) {
            MkLogger.log("simulation", e, "Error while set ethernet/ip simulation value {0}", tag);
            throw new IllegalArgumentException(
                    "Failed to encode EthernetIP value for target=" + tag + ": " + e.getMessage(), e);
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
