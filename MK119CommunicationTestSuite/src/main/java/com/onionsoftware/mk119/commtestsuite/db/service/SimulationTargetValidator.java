package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import org.springframework.stereotype.Component;

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
     * @return 오류 메시지, 또는 문제없으면 null
     */
    public String validate(Device device, String target) {
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

            case DAEYUN_RCU: {
                // @RCUDI_<n> / @RCUAI_<n> 는 채널 번호가 1-base. 그 외 문자열은 txField 로 자유 형식.
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
                }
                return null;
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
                int at = t.indexOf('@');
                if (at <= 0 || at == t.length() - 1) {
                    return "Invalid EtherNet/IP target \"" + t + "\". Expected \"<CIPType>@<Tag>\""
                            + " (e.g. \"REAL@TAG1\").";
                }
                String type = t.substring(0, at);
                if (etherip.types.CIPData.Type.forName(type) == null) {
                    return "Unknown CIP type \"" + type + "\" in \"" + t
                            + "\". Supported types: BOOL, SINT, INT, DINT, REAL.";
                }
                return null;
            }

            // GENERIC: tx 필드명은 자유 형식이라 등록을 막지 않는다 (API 쓰기 시점에 존재 확인).
            // DB: target 은 ${policyId} 치환이 들어가는 SQL 템플릿이라 형식 검증 대상이 아니다.
            default:
                return null;
        }
    }
}
