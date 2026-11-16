package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;

import java.util.Locale;

/**
 * dataType 과 값의 규칙. 타입에 맞지 않는 값은 거절한다.
 * API 쓰기(REST, Test Plan)와 Simulation 등록(정책의 값·범위)이 같이 쓴다.
 * <p>
 * 쓰기 경로는 맞지 않는 값을 조용히 바꿔 써 왔다 — 정수형의 소수는 0 쪽으로 잘리고,
 * 범위를 넘는 값은 비트가 넘쳐 엉뚱한 값이 되고, dataType 없는 Modbus 레지스터 쓰기는
 * 아무것도 하지 않은 채 성공으로 나갔다. 그래서 받는 쪽에서 막는다.
 */
public final class ServerDataValueRules {
    private ServerDataValueRules() {
    }

    /**
     * dataType 이 target 에 맞는지. 맞는 타입은 {@link DataTypeRules} 가 정한다.
     * 쓰기 요청은 dataType 을 비워도 되지만, 폭과 바이트 순서를 dataType 이 정하는
     * Modbus 레지스터와 XGT 는 있어야 한다.
     *
     * @return 맞지 않으면 오류 메시지, 맞으면 null
     */
    public static String dataTypeError(Protocol protocol, String target, DataType dataType) {
        if (protocol == null || target == null) return null;
        if (dataType == null) {
            if (protocol == Protocol.MODBUS && (target.startsWith("3_") || target.startsWith("4_"))) {
                return "Modbus register target \"" + target + "\" needs a data type (e.g. UI16AB).";
            }
            if (protocol == Protocol.XGT_PLC) {
                return "XGT target \"" + target.toUpperCase(Locale.ROOT) + "\" needs a data type. Use "
                        + DataTypeRules.join(DataTypeRules.allowed(protocol, target)) + ".";
            }
            return null;
        }
        return DataTypeRules.mismatch(protocol, target, dataType);
    }

    /**
     * 값이 target·dataType 에 맞는지.
     * <ul>
     *   <li>유한한 숫자여야 한다.</li>
     *   <li>BINARY 는 0 또는 1.</li>
     *   <li>정수형은 소수가 없어야 하고 그 폭의 범위 안이어야 한다. INTEGER 의 범위는 프로토콜이 정한다
     *       ({@link #integerRange}).</li>
     *   <li>실수형(FP32/FP64/FLOAT)은 보지 않는다.</li>
     *   <li>dataType 이 비어 있으면 target 이 받는 타입이 하나뿐일 때 그 타입으로 본다
     *       (Modbus 비트, BACnet binary·multi-state, Daeyun 채널).</li>
     * </ul>
     *
     * @return 맞지 않으면 오류 메시지, 맞으면 null
     */
    public static String valueError(Protocol protocol, String target, DataType dataType, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "Value must be a finite number.";
        if (protocol == null || target == null) return null;

        DataType type = dataType != null ? dataType : DataTypeRules.implied(protocol, target);
        if (type == null || type.isReal()) return null;
        if (type == DataType.BINARY) {
            return (value == 0 || value == 1) ? null
                    : "Target \"" + target + "\" is BINARY. Value must be 0 or 1 (got " + fmt(value) + ").";
        }

        String n = type.name();
        double min, max;
        if (type == DataType.INTEGER) {
            double[] r = integerRange(protocol, target);
            min = r[0];
            max = r[1];
        }
        else if (n.startsWith("UI8")) { min = 0; max = 255; }
        else if (n.startsWith("SI8")) { min = -128; max = 127; }
        else if (n.startsWith("UI16")) { min = 0; max = 65535; }
        else if (n.startsWith("SI16")) { min = -32768; max = 32767; }
        else if (n.startsWith("UI32")) { min = 0; max = 4294967295d; }
        else if (n.startsWith("SI32")) { min = Integer.MIN_VALUE; max = Integer.MAX_VALUE; }
        else if (n.startsWith("SI64")) { min = Long.MIN_VALUE; max = Long.MAX_VALUE; }
        else return null;

        if (value != Math.rint(value)) {
            return "Value " + fmt(value) + " is not a whole number. " + n + " takes whole numbers only.";
        }
        if (value < min || value > max) {
            return "Value " + fmt(value) + " is out of range for " + n + " on " + protocol
                    + " target \"" + target + "\" (" + fmt(min) + " to " + fmt(max) + ").";
        }
        return null;
    }

    /**
     * INTEGER 가 담을 수 있는 범위. 값이 나가는 모양이 정한다.
     * <pre>
     *   SNMP                 Integer32 (항상 부호 있음)
     *   BACnet multi_state   Unsigned, 상태 번호는 1 부터
     *   Daeyun @RCUAI        16비트 부호
     *   그 밖                double 이 정확히 담는 정수 (±2^53)
     * </pre>
     */
    static double[] integerRange(Protocol protocol, String target) {
        if (protocol == Protocol.SNMP) return new double[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
        if (protocol == Protocol.BACNET && target.toLowerCase(Locale.ROOT).startsWith("multi_state_")) {
            return new double[]{1, 4294967295d};
        }
        if (protocol == Protocol.DAEYUN_RCU && target.startsWith("@RCUAI_")) return new double[]{-32768, 32767};
        double exact = 9007199254740992d;
        return new double[]{-exact, exact};
    }

    static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e18 ? String.valueOf((long) v) : String.valueOf(v);
    }
}
