package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
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
     * EtherNet/IP 의 칸은 태그다. 예전 target 의 CIP 타입 접두어({@code REAL@TAG1})는 떼고 태그 이름으로 모은다.
     * 쓰기·색인·lock·값 읽기가 모두 이걸 거치므로, 패킷에 태그 이름만 실려 와도 같은 칸으로 판정된다.
     */
    @Override
    protected String canonicalTarget(String target) {
        return EthernetIPTypes.tagOf(target);
    }

    /** 태그의 CTS DataType. 그 태그로 등록된 Simulation 의 첫 정책이 정한다. 없으면 null. */
    public DataType dataTypeOf(String tag) {
        Simulation s = simulationFor(tag);
        if (s == null || s.getPolicies() == null || s.getPolicies().isEmpty()) return null;
        return s.getPolicies().get(0).getData_type();
    }

    /** 태그가 나갈 CIP 타입. 등록되지 않았거나 담을 수 없는 타입이면 null. */
    public CIPData.Type cipTypeOf(String tag) {
        return EthernetIPTypes.cipTypeOf(dataTypeOf(tag));
    }

    public EthernetIPData(Map<Object, Object> communicationSettings) {
        implicit_o_t_port = Short.parseShort(communicationSettings.get("syn_port").toString());
        String[] token = communicationSettings.get("implicit_t2o_addr").toString().split("\\.");
        implicit_t_o_addr = Integer.parseInt(token[0]) << 24 + Integer.parseInt(token[1]) << 16 + Integer.parseInt(token[2]) << 8 + Integer.parseInt(token[3]);
    }

    /**
     * 태그에 값을 넣는다. CTS DataType 을 CIP 타입으로 바꿔 담는다.
     * dataType 이 없으면 그 태그로 등록된 Simulation 의 data_type 을 쓴다.
     * <p>
     * 실패를 삼키지 않고 호출자에게 던진다. 예전에는 로그만 남겨, 쓰지도 못한 REST 쓰기에
     * 200 이 나가고 lock 까지 걸렸다.
     *
     * @param target 태그 이름. 예전 형식의 접두어가 붙어 있으면 뗀다
     * @throws IllegalArgumentException 태그가 비었거나, 타입을 정할 수 없거나, CIP 로 담을 수 없는 타입일 때
     */
    public void putTagValue(String target, Double value, DataType dataType) {
        String tag = EthernetIPTypes.tagOf(target);
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("EtherNet/IP target must be a tag name. target=" + target);
        }
        DataType dt = dataType != null ? dataType : dataTypeOf(tag);
        CIPData.Type type = EthernetIPTypes.cipTypeOf(dt);
        if (type == null) {
            throw new IllegalArgumentException(dt == null
                    ? "No data type for EtherNet/IP tag \"" + tag + "\". Register a simulation for the tag first."
                    : "Data type " + dt + " cannot be sent over EtherNet/IP. Use " + EthernetIPTypes.SUPPORTED + ".");
        }
        try {
            CIPData data = new CIPData(type, 1);
            // UI16 은 DINT, BITS 는 32비트로 나간다. 부호 없는 값이 int 범위를 넘을 수 있어 long 을 거친다
            Number n = (type == CIPData.Type.REAL) ? (Number) value.floatValue() : (Number) (int) value.longValue();
            data.set(0, n);
            tagValueMap.put(tag, data);
        } catch (Exception e) {
            LoggerFactory.getLogger("simulation").error("Error while set ethernet/ip simulation value {}", tag, e);
            throw new IllegalArgumentException(
                    "Failed to encode EthernetIP value for tag=" + tag + ": " + e.getMessage(), e);
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
