package com.onionsoftware.mk119.commtestsuite.web.bean;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
import etherip.types.CIPData;

import java.util.*;

/**
 * ServerData 전체 스냅샷 페이로드. WebSocket 주기 push / REST 초기 스냅샷에 공통 사용.
 * 필드 구성은 프로토콜별로 상이하다. 실제 값 데이터는 {@link #payload} 에 담고, 잠금 target 집합은
 * {@link #locks} 에 담는다.
 */
public class ServerDataSnapshot {
    public long seq;
    public long ts;
    public Protocol protocol;
    /** 프로토콜별 payload. 스키마는 {@link #of(long, ServerData)} 참조. */
    public Map<String, Object> payload;
    public Set<String> locks;

    public static ServerDataSnapshot of(long seq, ServerData sd) {
        ServerDataSnapshot snap = new ServerDataSnapshot();
        snap.seq = seq;
        snap.ts = System.currentTimeMillis();
        snap.locks = new HashSet<>(sd.getLockedTargets());
        snap.payload = new LinkedHashMap<>();

        if (sd instanceof ModbusData) {
            ModbusData m = (ModbusData) sd;
            snap.protocol = Protocol.MODBUS;
            int size = m.getHolding_registers().length;
            int[] coil = new int[size];
            int[] input = new int[size];
            for (int i = 0; i < size; i++) {
                coil[i] = m.getCoil_status().get(i) ? 1 : 0;
                input[i] = m.getInput_status().get(i) ? 1 : 0;
            }
            int[] hr = new int[size];
            int[] ir = new int[size];
            short[] hrs = m.getHolding_registers();
            short[] irs = m.getInput_registers();
            for (int i = 0; i < size; i++) {
                hr[i] = hrs[i] & 0xFFFF;
                ir[i] = irs[i] & 0xFFFF;
            }
            snap.payload.put("registers", size);
            snap.payload.put("coilStatus", coil);
            snap.payload.put("inputStatus", input);
            snap.payload.put("holdingRegisters", hr);
            snap.payload.put("inputRegisters", ir);
        } else if (sd instanceof XGTData) {
            XGTData x = (XGTData) sd;
            snap.protocol = Protocol.XGT_PLC;
            Map<String, int[]> blocks = new LinkedHashMap<>();
            blocks.put("M", toUnsigned(x.getMblock()));
            blocks.put("D", toUnsigned(x.getDblock()));
            blocks.put("P", toUnsigned(x.getPblock()));
            blocks.put("K", toUnsigned(x.getKblock()));
            blocks.put("S", toUnsigned(x.getSblock()));
            blocks.put("U", toUnsigned(x.getUblock()));
            snap.payload.put("blocks", blocks);
        } else if (sd instanceof OPCAgentData) {
            OPCAgentData o = (OPCAgentData) sd;
            snap.protocol = Protocol.OPC_AGENT;
            snap.payload.put("values", o.getValues());
        } else if (sd instanceof DaeyunData) {
            DaeyunData d = (DaeyunData) sd;
            snap.protocol = Protocol.DAEYUN_RCU;
            int[] digital = new int[32];
            for (int i = 0; i < 32; i++) digital[i] = d.getDigitalChannelValue(i) ? 1 : 0;
            snap.payload.put("analogChannels", d.getAnalogChannelValues(32));
            snap.payload.put("digitalChannels", digital);
            snap.payload.put("txFields", new LinkedHashMap<>(d.getTxFieldsIdValueMap()));
        } else if (sd instanceof SnmpData) {
            SnmpData s = (SnmpData) sd;
            snap.protocol = Protocol.SNMP;
            snap.payload.put("oids", new LinkedHashMap<>(s.getOidValueMap()));
        } else if (sd instanceof EthernetIPData) {
            EthernetIPData e = (EthernetIPData) sd;
            snap.protocol = Protocol.ETHERNET_IP;
            Map<String, Object> tags = new LinkedHashMap<>();
            for (Map.Entry<String, CIPData> en : e.getTagValueMap().entrySet()) {
                // CIPData.toString() 은 ByteBuffer.clear() 를 호출한다. 그 메서드의 반환형이
                // Java 8 은 Buffer, 9+ 는 ByteBuffer 라서, 9+ 로 컴파일된 etherip 을 Java 8 로
                // 돌리면 NoSuchMethodError(=Error) 가 난다. 값만 필요하므로 toString 을 피한다.
                try {
                    CIPData data = en.getValue();
                    if (data == null) {
                        tags.put(en.getKey(), null);
                    } else if (data.isNumeric()) {
                        tags.put(en.getKey(), data.getNumber(0));
                    } else {
                        tags.put(en.getKey(), data.getString());
                    }
                } catch (Throwable ex) {
                    // Error 까지 잡는다. 태그 하나가 페이지 전체를 500 으로 만들면 안 된다.
                    tags.put(en.getKey(), null);
                }
            }
            snap.payload.put("tags", tags);
        } else if (sd instanceof GenericData) {
            GenericData g = (GenericData) sd;
            snap.protocol = Protocol.GENERIC;
            snap.payload.put("fields", new LinkedHashMap<>(g.getTxFieldsIdValueMap()));
        } else if (sd instanceof BACnetData) {
            BACnetData b = (BACnetData) sd;
            snap.protocol = Protocol.BACNET;
            // BACnet 은 시뮬레이션이 곧 object 다. target(= object type + instance)과 현재 값을 싣는다.
            List<Map<String, Object>> objects = new ArrayList<>();
            for (Simulation s : b.getSimulations()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("target", s.getTarget());
                o.put("simulationId", s.getId());
                o.put("name", s.getName());
                BACnetObjectIdentifier oid = BACnetObjectIdentifier.parseSimulationTarget(s.getTarget());
                o.put("object", oid == null ? null : oid.toString());
                o.put("value", b.valueOfSimulation(s.getId()));
                objects.add(o);
            }
            snap.payload.put("objects", objects);
        }
        return snap;
    }

    private static int[] toUnsigned(byte[] b) {
        int[] out = new int[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[i] & 0xFF;
        return out;
    }
}
