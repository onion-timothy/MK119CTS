package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 프로토콜과 target 이 받는 DataType. Simulation 등록, Test Plan, REST 쓰기가 모두 이걸 본다.
 * 화면(static/js/cts-datatypes.js)도 같은 표를 쓴다 — 고칠 때 함께 고친다.
 * <pre>
 *   MODBUS      1_, 2_ (비트)          BINARY
 *               3_, 4_ (레지스터)      16·32·64비트 정수·실수, 바이트 순서별
 *   XGT_PLC     B / W / D             8 / 16 / 32비트 (D 는 실수 포함)
 *   ETHERNET_IP 태그                   SI8, SI16BA, SI32DA, FP32DA, BINARY  (CIP 는 little-endian)
 *   BACNET      analog_*              FLOAT, INTEGER   (REAL 로 나간다)
 *               binary_*              BINARY
 *               multi_state_*         INTEGER          (1 부터인 상태 번호)
 *   DAEYUN_RCU  @RCUDI_n              BINARY
 *               @RCUAI_n              INTEGER          (16비트 부호)
 *               그 밖 (TX 필드)        INTEGER, FLOAT, BINARY
 *   SNMP, GENERIC, OPC_AGENT, DB      INTEGER, FLOAT, BINARY
 * </pre>
 * 목록의 첫 타입이 화면의 기본값이다.
 */
public final class DataTypeRules {
    private DataTypeRules() {
    }

    /** 값을 그대로 담는 프로토콜의 타입. */
    public static final List<DataType> PLAIN = Collections.unmodifiableList(
            Arrays.asList(DataType.INTEGER, DataType.FLOAT, DataType.BINARY));

    public static final List<DataType> ETHERNET_IP = Collections.unmodifiableList(
            Arrays.asList(DataType.SI8, DataType.SI16BA, DataType.SI32DA, DataType.FP32DA, DataType.BINARY));

    private static final List<DataType> BINARY_ONLY = Collections.singletonList(DataType.BINARY);
    private static final List<DataType> INTEGER_ONLY = Collections.singletonList(DataType.INTEGER);
    private static final List<DataType> BACNET_ANALOG = Collections.unmodifiableList(
            Arrays.asList(DataType.FLOAT, DataType.INTEGER));

    /** 바이트 순서가 있는 타입 전부. 레지스터 프로토콜이 이 안에서 고른다. */
    private static final List<DataType> SIZED;
    private static final List<DataType> MODBUS_REGISTER;
    private static final List<DataType> XGT_B;
    private static final List<DataType> XGT_W;
    private static final List<DataType> XGT_D;

    static {
        List<DataType> sized = new ArrayList<>();
        for (DataType t : DataType.values()) if (t.getByteCount() > 0) sized.add(t);
        SIZED = Collections.unmodifiableList(sized);
        // 레지스터 한 칸이 2바이트라 8비트 타입은 쓸 곳이 없다
        MODBUS_REGISTER = widths(2, 4, 8);
        XGT_B = widths(1);
        XGT_W = widths(2);
        XGT_D = widths(4);
    }

    private static List<DataType> widths(int... bytes) {
        List<DataType> out = new ArrayList<>();
        for (DataType t : SIZED) {
            for (int b : bytes) if (t.getByteCount() == b) out.add(t);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 이 target 이 받는 타입. 형식을 읽을 수 없는 target 은 그 프로토콜에서 쓸 수 있는 타입 전부를 낸다 —
     * 형식 오류는 target 검증이 따로 알린다.
     */
    public static List<DataType> allowed(Protocol protocol, String target) {
        if (protocol == null) return Arrays.asList(DataType.values());
        String t = target == null ? "" : target.trim();
        switch (protocol) {
            case MODBUS:
                if (t.startsWith("1_") || t.startsWith("2_")) return BINARY_ONLY;
                if (t.startsWith("3_") || t.startsWith("4_")) return MODBUS_REGISTER;
                return union(BINARY_ONLY, MODBUS_REGISTER);
            case XGT_PLC: {
                char size = t.length() >= 3 ? Character.toUpperCase(t.charAt(2)) : 0;
                if (size == 'B') return XGT_B;
                if (size == 'W') return XGT_W;
                if (size == 'D') return XGT_D;
                return union(XGT_B, union(XGT_W, XGT_D));
            }
            case ETHERNET_IP:
                return ETHERNET_IP;
            case BACNET: {
                String lower = t.toLowerCase(Locale.ROOT);
                if (lower.startsWith("binary_")) return BINARY_ONLY;
                if (lower.startsWith("multi_state_")) return INTEGER_ONLY;
                if (lower.startsWith("analog_")) return BACNET_ANALOG;
                return PLAIN;
            }
            case DAEYUN_RCU:
                if (t.startsWith("@RCUDI_")) return BINARY_ONLY;
                if (t.startsWith("@RCUAI_")) return INTEGER_ONLY;
                return PLAIN;
            default:
                return PLAIN;
        }
    }

    /**
     * 장비를 보고 정한다. GENERIC 과 MK RCU 의 채널 밖 타겟은 장비의 프로토콜 정의가 그 타겟의 타입을 정한다
     * (docs/PROTOCOL_ENGINE_DESIGN.md 9.3). 정의나 타입을 모르면 프로토콜만 보고 정한다.
     */
    public static List<DataType> allowed(com.onionsoftware.mk119.commtestsuite.db.bean.Device device, String target) {
        Protocol protocol = device == null ? null : device.getProtocol();
        List<DataType> byDefinition = fromDefinition(device, target);
        return byDefinition != null ? byDefinition : allowed(protocol, target);
    }

    public static String mismatch(com.onionsoftware.mk119.commtestsuite.db.bean.Device device, String target, DataType dataType) {
        if (dataType == null) return null;
        List<DataType> types = allowed(device, target);
        if (types.contains(dataType)) return null;
        return "Data type " + dataType + " is not allowed for " + (device == null ? null : device.getProtocol())
                + " target \"" + target + "\". Use " + join(types) + ".";
    }

    private static List<DataType> fromDefinition(com.onionsoftware.mk119.commtestsuite.db.bean.Device device, String target) {
        if (device == null || target == null) return null;
        Protocol p = device.getProtocol();
        String t = target.trim();
        if (p != Protocol.GENERIC && !(p == Protocol.DAEYUN_RCU && !t.startsWith("@RCUDI_") && !t.startsWith("@RCUAI_"))) return null;
        com.onionsoftware.mk119.commtestsuite.protocol.DefinitionRegistry r = com.onionsoftware.mk119.commtestsuite.protocol.DefinitionRegistry.current();
        com.onionsoftware.mk119.commtestsuite.protocol.Definition def = r == null ? null : r.forDevice(device);
        com.onionsoftware.mk119.commtestsuite.protocol.Definition.TargetInfo info = def == null ? null : def.targetInfo(t);
        return info == null ? null : info.dataTypes();
    }

    private static List<DataType> union(List<DataType> a, List<DataType> b) {
        List<DataType> out = new ArrayList<>(a);
        for (DataType t : b) if (!out.contains(t)) out.add(t);
        return out;
    }

    /** 이 target 이 받는 타입이 하나뿐이면 그 타입, 아니면 null. 쓰기 요청에 타입이 없을 때 값 검사에 쓴다. */
    public static DataType implied(Protocol protocol, String target) {
        List<DataType> types = allowed(protocol, target);
        return types.size() == 1 ? types.get(0) : null;
    }

    /**
     * 타입이 target 에 맞지 않으면 오류 메시지.
     *
     * @return 맞으면 null. dataType 이 null 이어도 null — 필요한지는 호출자가 정한다
     */
    public static String mismatch(Protocol protocol, String target, DataType dataType) {
        if (dataType == null) return null;
        List<DataType> types = allowed(protocol, target);
        if (types.contains(dataType)) return null;
        return "Data type " + dataType + " is not allowed for " + protocol + " target \"" + target + "\". Use "
                + join(types) + ".";
    }

    /** 오류 메시지용 목록. "A, B or C". */
    public static String join(List<DataType> types) {
        if (types.size() == 1) return types.get(0).name();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(i == types.size() - 1 ? " or " : ", ");
            sb.append(types.get(i).name());
        }
        return sb.toString();
    }
}
