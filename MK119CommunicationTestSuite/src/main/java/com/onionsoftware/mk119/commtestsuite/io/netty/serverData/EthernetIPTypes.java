package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import etherip.types.CIPData;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * EtherNet/IP 의 태그와 타입.
 * <p>
 * CTS 안에서는 태그 이름과 CTS DataType 으로만 다루고, 패킷이 나갈 때 CIP 타입으로 바꾼다.
 * 실제 Logix 처럼 태그 하나는 타입 하나다 — 그 타입은 등록된 Simulation 의 data_type 이 정한다.
 * <pre>
 *   SI8      SINT
 *   SI16BA   INT
 *   SI32DA   DINT
 *   FP32DA   REAL
 *   BINARY   BOOL
 * </pre>
 * CIP 는 little-endian 으로 고정이라 바이트 순서도 그 하나(BA, DA)만 둔다.
 * <p>
 * 예전 target 은 {@code REAL@TAG1} 처럼 CIP 타입 접두어를 달았다. 그 형식도 받되 접두어를 떼고,
 * 접두어가 뜻하던 CIP 타입은 {@link #dataTypeForLegacyPrefix} 로 data_type 에 옮긴다.
 */
public final class EthernetIPTypes {
    private EthernetIPTypes() {
    }

    /** 예전 target 의 접두어로 쓰이던 CIP 수치 타입. */
    private static final List<String> LEGACY_PREFIXES = Collections.unmodifiableList(
            Arrays.asList("BOOL", "SINT", "INT", "DINT", "REAL", "BITS"));

    /** EtherNet/IP 가 받는 DataType 을 사람이 읽는 목록으로. 오류 메시지용. */
    public static final String SUPPORTED = DataTypeRules.join(DataTypeRules.ETHERNET_IP);

    /** CTS DataType 이 나갈 CIP 타입. EtherNet/IP 가 받지 않는 타입이면 null. */
    public static CIPData.Type cipTypeOf(DataType dataType) {
        if (dataType == null) return null;
        switch (dataType) {
            case BINARY: return CIPData.Type.BOOL;
            case SI8: return CIPData.Type.SINT;
            case SI16BA: return CIPData.Type.INT;
            case SI32DA: return CIPData.Type.DINT;
            case FP32DA: return CIPData.Type.REAL;
            default: return null;
        }
    }

    /** {@code REAL@TAG1} 이면 {@code REAL}, 접두어가 없거나 CIP 수치 타입이 아니면 null. */
    public static String legacyPrefixOf(String target) {
        if (target == null) return null;
        int at = target.indexOf('@');
        if (at <= 0 || at == target.length() - 1) return null;
        String prefix = target.substring(0, at).trim().toUpperCase(java.util.Locale.ROOT);
        return LEGACY_PREFIXES.contains(prefix) ? prefix : null;
    }

    /** 태그 이름. 예전 접두어가 있으면 뗀다. */
    public static String tagOf(String target) {
        if (target == null) return null;
        String prefix = legacyPrefixOf(target);
        return prefix == null ? target.trim() : target.substring(target.indexOf('@') + 1).trim();
    }

    /**
     * 예전 접두어가 뜻하던 CIP 타입의 data_type. BITS 는 더 이상 내보내지 않아 같은 폭의 DINT 로 옮긴다.
     *
     * @return 접두어가 아니면 null
     */
    public static DataType dataTypeForLegacyPrefix(String prefix) {
        if (prefix == null) return null;
        switch (prefix) {
            case "BOOL": return DataType.BINARY;
            case "SINT": return DataType.SI8;
            case "INT": return DataType.SI16BA;
            case "DINT":
            case "BITS": return DataType.SI32DA;
            case "REAL": return DataType.FP32DA;
            default: return null;
        }
    }
}
