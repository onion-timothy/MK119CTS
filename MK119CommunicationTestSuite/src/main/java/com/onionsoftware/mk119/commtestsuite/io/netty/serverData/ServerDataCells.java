package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * target 이 실제로 차지하는 저장 칸. 프로토콜만으로 계산한다.
 * <p>
 * lock 판정(ServerData)과 Test Plan 편집기가 같은 함수를 쓴다. 장비가 떠 있지 않아도
 * 편집기는 칸을 그려야 하므로 ServerData 인스턴스 없이 계산할 수 있어야 한다.
 * <pre>
 *   MODBUS      레지스터 / 비트 한 칸. FC3/FC4 는 dataType 폭만큼 (UI32 → 3_10, 3_11)
 *   XGT_PLC     byte 번지. W 는 2칸, D 는 4칸 (%DW10 → %DB20, %DB21)
 *   ETHERNET_IP 태그 이름 (REAL@TAG1 → TAG1)
 *   그 밖       target 그대로
 * </pre>
 */
public final class ServerDataCells {
    private ServerDataCells() {
    }

    /** target 의 내부 표기. XGT 는 대문자, EtherNet/IP 는 태그 이름. */
    public static String canonical(Protocol protocol, String target) {
        if (target == null) return null;
        if (protocol == Protocol.XGT_PLC) return target.toUpperCase(Locale.ROOT);
        if (protocol == Protocol.ETHERNET_IP) return EthernetIPTypes.tagOf(target);
        return target;
    }

    /**
     * 칸 목록. 형식을 읽을 수 없으면 target 한 칸으로 본다 — 형식 오류는 target 검증이 따로 알린다.
     */
    public static List<String> of(Protocol protocol, String target, DataType dataType) {
        String t = canonical(protocol, target);
        if (t == null) return Collections.emptyList();
        try {
            if (protocol == Protocol.MODBUS && t.contains("_")) {
                String[] tk = t.split("_");
                int fc = Integer.parseInt(tk[0]);
                int reg = Integer.parseInt(tk[1]);
                int span = 1;
                if ((fc == 3 || fc == 4) && dataType != null) {
                    span = Math.max(1, dataType.getByteCount() / 2);   // 레지스터 한 칸 = 2 bytes
                }
                List<String> out = new ArrayList<>(span);
                for (int i = 0; i < span; i++) out.add(fc + "_" + (reg + i));
                return out;
            }
            if (protocol == Protocol.XGT_PLC && t.length() >= 3) {
                char block = t.charAt(1);          // M, D, P, K, S, U
                char size = t.charAt(2);           // B, W, D
                int addr = Integer.parseInt(t.substring(3));
                int offset, span;
                if (size == 'B') { offset = addr; span = 1; }
                else if (size == 'W') { offset = addr * 2; span = 2; }
                else if (size == 'D') { offset = addr * 4; span = 4; }
                else return Collections.singletonList(t);
                // 스냅샷·화면이 byte 단위(%<block>B<idx>)로 보여 주므로 그 형식으로 낸다
                List<String> out = new ArrayList<>(span);
                for (int i = 0; i < span; i++) out.add("%" + block + "B" + (offset + i));
                return out;
            }
        } catch (RuntimeException e) {
            // 번지를 읽을 수 없는 target
        }
        return Collections.singletonList(t);
    }

    /** ServerData 종류가 쓰는 프로토콜. */
    static Protocol protocolOf(ServerData sd) {
        if (sd instanceof ModbusData) return Protocol.MODBUS;
        if (sd instanceof XGTData) return Protocol.XGT_PLC;
        if (sd instanceof EthernetIPData) return Protocol.ETHERNET_IP;
        if (sd instanceof DaeyunData) return Protocol.DAEYUN_RCU;
        if (sd instanceof SnmpData) return Protocol.SNMP;
        if (sd instanceof OPCAgentData) return Protocol.OPC_AGENT;
        if (sd instanceof BACnetData) return Protocol.BACNET;
        if (sd instanceof GenericData) return Protocol.GENERIC;
        return null;
    }
}
