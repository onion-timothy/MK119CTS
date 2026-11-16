package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Policy;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.DataTypeRules;
import com.onionsoftware.mk119.commtestsuite.protocol.Definition;
import com.onionsoftware.mk119.commtestsuite.protocol.DefinitionRegistry;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.EthernetIPTypes;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataValueRules;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 프로토콜별 simulation target 형식·범위 검증.
 * <p>
 * 등록 경로가 여러 곳이라(REST {@code /simulation/addSimulation}, batch validate,
 * 에디터 페이지의 {@code /saveSimulation}) 한 곳에만 넣으면 구멍이 생긴다. 모든 경로가 이걸 쓴다.
 * <p>
 * 쓰기 시점에도 각 프로토콜이 다시 검사하지만, 등록 때 막아야 사용자가 즉시 알 수 있고
 * 잘못된 target 이 DB 에 남아 런타임 경로를 계속 건드리는 일을 피할 수 있다.
 */
@Component
public class SimulationTargetValidator {

    private static final Pattern SNMP_OID_RE = Pattern.compile("^\\.?\\d+(\\.\\d+)+$");
    private static final String XGT_BLOCKS = "MDPKSU";

    /** Daeyun 저장 구조의 채널 수. analogChannelValues[32] / BitSet(32) 와 맞춘다. */
    private static final int DAEYUN_CHANNELS = 32;
    /** BACnet object instance 는 22비트다. 4194303 은 '아무 instance' 를 뜻하는 예약값이라 쓸 수 없다. */
    static final int BACNET_INSTANCE_MAX = 4194302;

    /** communicationSettings 의 정수 값. 없거나 숫자가 아니면 fallback. 값이 "1.0" 인 경우도 받는다. */
    private static int csInt(Device device, String key, int fallback) {
        if (device == null || device.getCommunicationSettings() == null) return fallback;
        Object v = device.getCommunicationSettings().get(key);
        if (v == null) return fallback;
        try {
            return (int) Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Simulation 하나를 통째로 본다. 정책이 여러 개면 모두 검사한다.
     * 하나라도 target 과 맞지 않으면 그 Simulation 은 등록되면 안 된다.
     * <p>
     * 정책마다 policy 와 data_type 이 있어야 하고, 그 타입이 target 에 맞아야 하고({@link DataTypeRules}),
     * 정책의 값이 그 타입에 맞아야 한다({@link #policyValueError}).
     *
     * @return 첫 번째 오류 메시지, 또는 문제없으면 null
     */
    public String validate(Device device, Simulation simulation) {
        if (simulation == null) return null;
        String target = simulation.getTarget();
        if (simulation.getPolicies() == null || simulation.getPolicies().isEmpty()) {
            return validate(device, target, null);
        }
        boolean several = simulation.getPolicies().size() > 1;
        // 정책이 여럿인 건 DB 뿐이다. DB 의 target 은 ${정책 ID} 자리를 가진 SQL 이라
        // 정책 하나가 자리 하나를 채운다. 다른 프로토콜은 target 하나에 값 하나를 쓰므로
        // 정책을 더 달아 봐야 같은 자리를 번갈아 덮어쓸 뿐이다.
        if (several && protocolOf(device) != Protocol.DB) {
            return "Only a DB device can have more than one policy."
                    + " Other protocols write a single value to the target.";
        }
        for (SimulationPolicy policy : simulation.getPolicies()) {
            if (policy == null) continue;
            String error = validate(device, target, policy.getData_type());
            // 예전 EtherNet/IP 형식(REAL@TAG1)은 접두어가 타입이다. 저장할 때 data_type 으로 옮겨진다
            DataType type = policy.getData_type() != null ? policy.getData_type() : legacyPrefixType(device, target);
            // 정책이 비면 policyValueError 가 통째로 건너뛴다 — BINARY 에 ACCUMULATE 를 쓰는 것조차 걸리지 않는다.
            // 화면이 고를 수 없는 정책을 disabled 로 막으면 jQuery .val() 이 undefined 를 주고,
            // 그 undefined 가 여기까지 그대로 온다. 그래서 타입과 똑같이 필수로 받는다
            if (error == null && policy.getPolicy() == null) {
                error = "Policy is required. Use " + joinPolicies() + ".";
            }
            if (error == null && type == null) {
                error = "Data type is required. Use "
                        + DataTypeRules.join(DataTypeRules.allowed(device, target)) + ".";
            }
            if (error == null) error = policyValueError(device, target, policy, type);
            if (error != null) return several && policy.getId() != null ? "Policy " + policy.getId() + ": " + error : error;
        }
        return null;
    }

    private static Protocol protocolOf(Device device) {
        return device == null ? null : device.getProtocol();
    }

    /** "STATIC, RANDOM, ACCUMULATE or SEQUENCE" — DataTypeRules.join 과 같은 말투로 적는다. */
    private static String joinPolicies() {
        Policy[] all = Policy.values();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) sb.append(i == all.length - 1 ? " or " : ", ");
            sb.append(all[i]);
        }
        return sb.toString();
    }

    private static DataType legacyPrefixType(Device device, String target) {
        if (protocolOf(device) != Protocol.ETHERNET_IP) return null;
        return EthernetIPTypes.dataTypeForLegacyPrefix(EthernetIPTypes.legacyPrefixOf(target));
    }

    /**
     * 정책의 값이 data_type 에 맞는지. 화면에 넣은 값이 실행 중에 잘리거나 넘치지 않게 등록 때 막는다.
     * <ul>
     *   <li>STATIC 값, SEQUENCE 의 각 값, RANDOM 의 범위 양 끝, ACCUMULATE 의 시작값은 그 타입이 담을 수 있어야 한다</li>
     *   <li>ACCUMULATE 의 증가량은 정수형이면 정수여야 한다</li>
     *   <li>BINARY 에는 ACCUMULATE 를 쓸 수 없다 — 0 과 1 사이를 누적할 수 없다</li>
     * </ul>
     */
    private String policyValueError(Device device, String target, SimulationPolicy policy, DataType type) {
        Protocol protocol = protocolOf(device);
        if (protocol == null || type == null || policy.getPolicy() == null) return null;
        String t = protocol == Protocol.ETHERNET_IP ? EthernetIPTypes.tagOf(target) : target.trim();
        switch (policy.getPolicy()) {
            case STATIC: {
                Double v = policy.numericValue();
                return v == null ? null : ServerDataValueRules.valueError(protocol, t, type, v);
            }
            case SEQUENCE: {
                List<Double> values = policy.sequenceValues();
                if (values == null) return null;
                for (Double v : values) {
                    String e = ServerDataValueRules.valueError(protocol, t, type, v);
                    if (e != null) return e;
                }
                return null;
            }
            case RANDOM: {
                List<Double> range = policy.getRange();
                if (range == null || range.size() < 2) return null;
                for (int i = 0; i < 2; i++) {
                    if (range.get(i) == null) continue;
                    String e = ServerDataValueRules.valueError(protocol, t, type, range.get(i));
                    if (e != null) return "Range " + (i == 0 ? "minimum" : "maximum") + ": " + e;
                }
                return null;
            }
            case ACCUMULATE: {
                if (type == DataType.BINARY) return "ACCUMULATE cannot be used with BINARY. Use STATIC, SEQUENCE or RANDOM.";
                List<Double> range = policy.getRange();
                if (range == null || range.size() < 2) return null;
                if (range.get(0) != null) {
                    String e = ServerDataValueRules.valueError(protocol, t, type, range.get(0));
                    if (e != null) return "Start: " + e;
                }
                Double step = range.get(1);
                if (step != null && !type.isReal() && step != Math.rint(step)) {
                    return "Step " + step + " is not a whole number. " + type + " takes whole numbers only.";
                }
                return null;
            }
            default:
                return null;
        }
    }

    /**
     * target 형식만 본다. data_type 까지 맞춰야 하는 프로토콜은
     * {@link #validate(Device, String, DataType)} 를 쓴다.
     *
     * @return 오류 메시지, 또는 문제없으면 null
     */
    public String validate(Device device, String target) {
        return validate(device, target, null);
    }

    /**
     * target 형식과 함께, data_type 이 그 target 에 맞는지 본다({@link DataTypeRules}).
     * 예를 들어 BACnet binary_value 에는 BINARY 만 맞다.
     *
     * @param dataType 정책의 data_type. null 이면 타입 검사를 건너뛴다.
     * @return 오류 메시지, 또는 문제없으면 null
     */
    public String validate(Device device, String target, DataType dataType) {
        String error = validateFormat(device, target, dataType);
        if (error != null || device == null || device.getProtocol() == null) return error;
        // 예전 EtherNet/IP 형식(REAL@TAG1)은 접두어가 타입을 말한다. 그 대조는 위에서 했다
        if (device.getProtocol() == Protocol.ETHERNET_IP && EthernetIPTypes.legacyPrefixOf(target) != null) return null;
        return DataTypeRules.mismatch(device, target.trim(), dataType);
    }

    /**
     * target 형식·주소 범위만 본다. data_type 은 예전 EtherNet/IP 접두어와의 대조에만 쓴다.
     * 타입이 target 에 맞는지는 {@link DataTypeRules} 로 따로 본다.
     */
    public String validateFormat(Device device, String target, DataType dataType) {
        if (device == null || device.getProtocol() == null) return null;
        if (target == null || target.trim().isEmpty()) return "Missing target.";
        String t = target.trim();

        switch (device.getProtocol()) {
            case BACNET:
                if (!BACnetObjectIdentifier.isValidSimulationTarget(t)) {
                    return "Invalid BACnet target \"" + t + "\". Expected \"<object_type> <instance>\""
                            + " (e.g. \"analog_input 1\"). Supported types: analog_input, analog_output,"
                            + " analog_value, binary_input, binary_output, binary_value,"
                            + " multi_state_input, multi_state_output, multi_state_value.";
                }
                int instance = BACnetObjectIdentifier.parseSimulationTarget(t).getInstance_number();
                if (instance < 0 || instance > BACNET_INSTANCE_MAX) {
                    return "BACnet instance " + instance + " is out of range (0.." + BACNET_INSTANCE_MAX + ").";
                }
                return null;

            case MODBUS: {
                // "<fc>_<address>", fc 는 쓰기 가능한 1/2/3/4 만
                int us = t.indexOf('_');
                if (us <= 0 || us == t.length() - 1) {
                    return "Invalid Modbus target \"" + t + "\". Expected \"<fc>_<address>\" (e.g. \"3_10\").";
                }
                int fc, addr;
                try {
                    fc = Integer.parseInt(t.substring(0, us));
                    addr = Integer.parseInt(t.substring(us + 1));
                } catch (NumberFormatException e) {
                    return "Invalid Modbus target \"" + t + "\". Both parts must be numbers (e.g. \"3_10\").";
                }
                if (fc < 1 || fc > 4) {
                    return "Modbus function code " + fc + " is not writable. Use 1 (coil), 2 (discrete input),"
                            + " 3 (holding register) or 4 (input register).";
                }
                int registers = csInt(device, "registers", 65536);
                if (addr < 0 || addr >= registers) {
                    return "Modbus address " + addr + " is out of range. This device has "
                            + registers + " registers (0.." + (registers - 1) + ").";
                }
                return null;
            }

            case XGT_PLC: {
                // "%<block><size><address>" 예: %MW10
                if (t.length() < 4 || t.charAt(0) != '%') {
                    return "Invalid XGT target \"" + t + "\". Expected \"%<block><size><address>\""
                            + " (e.g. \"%MW10\").";
                }
                char block = Character.toUpperCase(t.charAt(1));
                char size = Character.toUpperCase(t.charAt(2));
                if (XGT_BLOCKS.indexOf(block) < 0) {
                    return "Unknown XGT block '" + t.charAt(1) + "' in \"" + t
                            + "\". Supported blocks: M, D, P, K, S, U.";
                }
                if (size != 'B' && size != 'W' && size != 'D') {
                    return "Unknown XGT size char '" + t.charAt(2) + "' in \"" + t
                            + "\". Expected B (byte), W (word) or D (double word).";
                }
                int addr;
                try {
                    addr = Integer.parseInt(t.substring(3));
                } catch (NumberFormatException e) {
                    return "Invalid XGT address in \"" + t + "\". Expected a number after the size char.";
                }
                int blockSize = csInt(device, "registers", 65536);
                int width = (size == 'B') ? 1 : (size == 'W' ? 2 : 4);
                long lastByte = (long) addr * width + width - 1;
                if (addr < 0 || lastByte >= blockSize) {
                    return "XGT address " + addr + " (" + size + ") is out of range. Block size is "
                            + blockSize + " bytes.";
                }
                return null;
            }

            case GENERIC:
                return definitionTargetError(device, t);

            case DAEYUN_RCU: {
                // @RCUDI_<n> / @RCUAI_<n> 는 채널 번호가 1-base. 그 밖은 RS 본문의 프로토콜 정의가 정한 타겟이다
                if (t.startsWith("@RCUDI_") || t.startsWith("@RCUAI_")) {
                    boolean digital = t.startsWith("@RCUDI_");
                    int ch;
                    try {
                        ch = Integer.parseInt(t.substring(7));
                    } catch (NumberFormatException e) {
                        return "Invalid Daeyun channel in \"" + t + "\". Expected a number (e.g. \""
                                + (digital ? "@RCUDI_1" : "@RCUAI_1") + "\").";
                    }
                    int count = csInt(device, digital ? "diCount" : "aiCount", DAEYUN_CHANNELS);
                    // 저장 배열은 32칸 고정이므로 설정값이 더 커도 32를 넘을 수 없다
                    int limit = Math.min(count, DAEYUN_CHANNELS);
                    if (ch < 1 || ch > limit) {
                        return "Daeyun " + (digital ? "digital" : "analog") + " channel " + ch
                                + " is out of range (1.." + limit + ").";
                    }
                    return null;
                }
                return definitionTargetError(device, t);
            }

            case OPC_AGENT: {
                int index;
                try {
                    index = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    return "Invalid OPC target \"" + t + "\". Expected a numeric index (e.g. \"5\").";
                }
                int registers = csInt(device, "registers", 65536);
                if (index < 0 || index >= registers) {
                    return "OPC index " + index + " is out of range (0.." + (registers - 1) + ").";
                }
                return null;
            }

            case SNMP:
                if (!SNMP_OID_RE.matcher(t).matches()) {
                    return "Invalid SNMP OID \"" + t + "\". Expected dotted decimal"
                            + " (e.g. \"1.3.6.1.2.1.1.1.0\").";
                }
                return null;

            case ETHERNET_IP: {
                // target 은 태그 이름이다. 타입은 data_type 이 정하고 패킷이 나갈 때 CIP 타입으로 바뀐다.
                // 예전 형식(REAL@TAG1)도 받는다 — 저장할 때 접두어를 떼고 그 타입을 data_type 으로 옮긴다
                String prefix = EthernetIPTypes.legacyPrefixOf(t);
                String tag = EthernetIPTypes.tagOf(t);
                if (tag.isEmpty() || tag.indexOf('@') >= 0 || tag.indexOf(' ') >= 0) {
                    return "Invalid EtherNet/IP target \"" + t + "\". Use the tag name"
                            + " (e.g. \"TAG1\" or \"sys_runtime_acc[0]\").";
                }
                if (prefix != null) return cipTypeMismatch(prefix, dataType, t);
                return null;
            }

            // GENERIC: tx 필드명은 자유 형식이라 등록을 막지 않는다 (API 쓰기 시점에 존재 확인).
            // DB: target 은 ${policyId} 치환이 들어가는 SQL 템플릿이라 형식 검증 대상이 아니다.
            default:
                return null;
        }
    }

    /**
     * GENERIC·MK RCU(채널 밖): 장비의 프로토콜 정의의 타겟 목록에 있거나 패턴에 맞아야 한다
     * (docs/PROTOCOL_ENGINE_DESIGN.md 9.3). 정의를 볼 수 없으면(앱 밖) 막지 않는다.
     */
    private static String definitionTargetError(Device device, String t) {
        DefinitionRegistry registry = DefinitionRegistry.current();
        if (registry == null) return null;
        if (ProtocolDefinitionService.definitionIdOf(device) == null) {
            return device.getProtocol() == Protocol.GENERIC
                    ? "Device \"" + device.getName() + "\" has no protocol definition. Choose one in the device editor first."
                    : "Target \"" + t + "\" needs a protocol definition on this device (otherwise only @RCUDI_n and @RCUAI_n).";
        }
        Definition def = registry.forDevice(device);
        if (def == null) return "The protocol definition of device \"" + device.getName() + "\" does not exist or does not compile.";
        if (def.targetInfo(t) == null) return "No target \"" + t + "\" in protocol definition \"" + def.name + "\".";
        return null;
    }

    /**
     * 예전 형식 target 의 CIP 타입 접두어와 data_type 이 같은 타입인지 본다.
     * 저장할 때 접두어를 떼고 그 타입을 data_type 으로 옮기므로({@link EthernetIPTypes#dataTypeForLegacyPrefix}),
     * 다른 data_type 을 함께 보내면 둘 중 하나가 버려진다.
     *
     * @return 어긋나면 오류 메시지, 맞거나 data_type 이 없으면 null
     */
    private String cipTypeMismatch(String cipType, DataType dataType, String target) {
        DataType expected = EthernetIPTypes.dataTypeForLegacyPrefix(cipType);
        if (dataType == null || expected == null || dataType == expected) return null;
        return "EtherNet/IP target \"" + target + "\" declares CIP type " + cipType
                + ", but data_type is " + dataType + ". Use " + expected
                + ", or write the tag name without the prefix.";
    }
}
