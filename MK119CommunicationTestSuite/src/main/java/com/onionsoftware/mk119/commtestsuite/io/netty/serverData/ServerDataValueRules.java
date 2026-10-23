package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;

import java.util.Locale;

/**
 * API 쓰기(REST, Test Plan)가 받는 dataType 과 값의 규칙. 타입에 맞지 않는 값은 거절한다.
 * <p>
 * 쓰기 경로는 맞지 않는 값을 조용히 바꿔 써 왔다 — 정수형의 소수는 0 쪽으로 잘리고,
 * 범위를 넘는 값은 비트가 넘쳐 엉뚱한 값이 되고, dataType 없는 Modbus 레지스터 쓰기는
 * 아무것도 하지 않은 채 성공으로 나갔다. 그래서 받는 쪽에서 막는다.
 * 시뮬레이션이 만드는 값(RANDOM 의 실수 등)은 여기를 거치지 않는다.
 */
public final class ServerDataValueRules {
    private ServerDataValueRules() {
    }

    /**
     * dataType 이 target 에 맞는지.
     * <ul>
     *   <li>Modbus FC3/FC4 (레지스터) 는 dataType 이 있어야 한다. 폭과 바이트 순서를 dataType 이 정한다.</li>
     *   <li>XGT 는 dataType 이 있어야 하고, target 의 크기 문자와 폭이 같아야 한다 (B 8비트, W 16비트, D 32비트).</li>
     * </ul>
     *
     * @return 맞지 않으면 오류 메시지, 맞으면 null
     */
    public static String dataTypeError(Protocol protocol, String target, DataType dataType) {
        if (protocol == null || target == null) return null;
        if ((dataType == DataType.BOOL || dataType == DataType.BITS) && protocol != Protocol.ETHERNET_IP) {
            return "Data type " + dataType + " is for EtherNet/IP only.";
        }
        if (protocol == Protocol.ETHERNET_IP) {
            if (dataType != null && EthernetIPTypes.cipTypeOf(dataType) == null) {
                return "Data type " + dataType + " cannot be sent over EtherNet/IP. Use " + EthernetIPTypes.SUPPORTED + ".";
            }
            return null;
        }
        if (protocol == Protocol.MODBUS) {
            if ((target.startsWith("3_") || target.startsWith("4_")) && dataType == null) {
                return "Modbus register target \"" + target + "\" needs a data type (e.g. UI16AB).";
            }
            return null;
        }
        if (protocol == Protocol.XGT_PLC && target.length() >= 3) {
            char size = Character.toUpperCase(target.charAt(2));
            String need = size == 'B' ? "8" : size == 'W' ? "16" : size == 'D' ? "32" : null;
            if (need == null) return null;   // 크기 문자 오류는 target 검증이 따로 알린다
            if (dataType == null || !dataType.name().contains(need)) {
                return "XGT target \"" + target.toUpperCase(Locale.ROOT) + "\" (" + size + ") needs a "
                        + need + "-bit data type" + (dataType == null ? "." : ", not " + dataType + ".");
            }
        }
        return null;
    }

    /**
     * 값이 target·dataType 에 맞는지.
     * <ul>
     *   <li>유한한 숫자여야 한다.</li>
     *   <li>비트는 0 또는 1. Modbus FC1/FC2, Daeyun 디지털 입력, dataType BOOL.</li>
     *   <li>정수형은 소수가 없어야 하고 그 폭의 범위 안이어야 한다. dataType 으로 정하거나,
     *       target 이 정한다 (Daeyun 아날로그 입력은 16비트 부호). BITS 는 32비트 부호 없는 범위.</li>
     *   <li>실수형(FP32/FP64)과 타입을 정할 수 없는 쪽은 보지 않는다.</li>
     *   <li>EtherNet/IP 는 호출자가 태그의 dataType 을 채워서 넘긴다(target 에는 타입이 없다).</li>
     * </ul>
     *
     * @return 맞지 않으면 오류 메시지, 맞으면 null
     */
    public static String valueError(Protocol protocol, String target, DataType dataType, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "Value must be a finite number.";
        if (protocol == null || target == null) return null;

        if (isBitTarget(protocol, target) || dataType == DataType.BOOL) {
            return (value == 0 || value == 1) ? null
                    : "Target \"" + target + "\" is a bit. Value must be 0 or 1 (got " + fmt(value) + ").";
        }
        String type;
        double min, max;
        if (dataType == DataType.BITS) {
            type = "UI32";
        } else if (dataType != null) {
            type = dataType.name();
        } else if (protocol == Protocol.DAEYUN_RCU && target.startsWith("@RCUAI_")) {
            type = "SI16";
        } else {
            return null;
        }
        if (type.startsWith("UI8")) { min = 0; max = 255; }
        else if (type.startsWith("SI8")) { min = -128; max = 127; }
        else if (type.startsWith("UI16")) { min = 0; max = 65535; }
        else if (type.startsWith("SI16")) { min = -32768; max = 32767; }
        else if (type.startsWith("UI32")) { min = 0; max = 4294967295d; }
        else if (type.startsWith("SI32")) { min = Integer.MIN_VALUE; max = Integer.MAX_VALUE; }
        else if (type.startsWith("SI64")) { min = Long.MIN_VALUE; max = Long.MAX_VALUE; }
        else return null;   // FP32 / FP64

        String label = dataType != null ? dataType.name() : type;
        if (value != Math.rint(value)) {
            return "Value " + fmt(value) + " is not a whole number. " + label + " takes whole numbers only.";
        }
        if (value < min || value > max) {
            return "Value " + fmt(value) + " is out of range for " + label + " (" + fmt(min) + " to " + fmt(max) + ").";
        }
        return null;
    }

    private static boolean isBitTarget(Protocol protocol, String target) {
        switch (protocol) {
            case MODBUS:
                return target.startsWith("1_") || target.startsWith("2_");
            case DAEYUN_RCU:
                return target.startsWith("@RCUDI_");
            default:
                return false;
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e18 ? String.valueOf((long) v) : String.valueOf(v);
    }
}
