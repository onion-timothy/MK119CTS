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
 *   SI8          SINT
 *   UI8, SI16    INT     부호 없는 CIP 타입이 라이브러리에 없어 한 단계 넓은 타입으로 보낸다
 *   UI16, SI32   DINT
 *   FP32         REAL
 *   BOOL         BOOL
 *   BITS         BITS
 *   UI32, SI64, FP64   — 담을 CIP 타입이 없다
 * </pre>
 * 바이트 순서(AB/BA/...)는 보지 않는다. CIP 는 little-endian 으로 고정이다.
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
    public static final String SUPPORTED = "SI8, UI8, SI16*, UI16*, SI32*, FP32*, BOOL or BITS";

    /** CTS DataType 이 나갈 CIP 타입. 담을 수 없으면 null. */
    public static CIPData.Type cipTypeOf(DataType dataType) {
        if (dataType == null) return null;
        if (dataType == DataType.BOOL) return CIPData.Type.BOOL;
        if (dataType == DataType.BITS) return CIPData.Type.BITS;
        String n = dataType.name();
        if (n.startsWith("SI8")) return CIPData.Type.SINT;
        if (n.startsWith("UI8") || n.startsWith("SI16")) return CIPData.Type.INT;
        if (n.startsWith("UI16") || n.startsWith("SI32")) return CIPData.Type.DINT;
        if (n.startsWith("FP32")) return CIPData.Type.REAL;
        return null;
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
     * 예전 접두어가 뜻하던 CIP 타입이 그대로 나가도록 data_type 을 고른다.
     * 지금 data_type 이 이미 그 CIP 타입으로 나가면 그대로 둔다(바이트 순서 표기를 지킨다).
     * 예: {@code INT@T} + UI16AB 는 예전에 INT 로 나갔지만 UI16 은 이제 DINT 로 나가므로 SI16AB 로 바꾼다.
     */
    public static DataType dataTypeForLegacyPrefix(String prefix, DataType current) {
        if (prefix == null) return current;
        CIPData.Type want = CIPData.Type.forName(prefix);
        if (want != null && cipTypeOf(current) == want) return current;
        switch (prefix) {
            case "BOOL": return DataType.BOOL;
            case "BITS": return DataType.BITS;
            case "SINT": return DataType.SI8;
            case "INT": return DataType.SI16AB;
            case "DINT": return DataType.SI32AD;
            case "REAL": return DataType.FP32AD;
            default: return current;
        }
    }
}
