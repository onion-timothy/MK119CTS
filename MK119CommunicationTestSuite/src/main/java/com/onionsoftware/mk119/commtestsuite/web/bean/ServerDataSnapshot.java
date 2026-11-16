package com.onionsoftware.mk119.commtestsuite.web.bean;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
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
    /**
     * 이 장비에 등록된 target 목록. 값 맵에는 없는 것(등록만 되고 아직 한 번도 안 쓰인 target)까지
     * 화면이 행으로 그릴 수 있게 하려는 것이다. 값 맵만 보면 그런 target 은 아예 보이지 않아,
     * 등록이 잘못된 것인지 아직 주기가 안 돈 것인지 구분할 수 없었다.
     * <p>
     * 값을 격자로 그리는 프로토콜(MODBUS·XGT)에는 싣지 않는다 — 화면이 쓰지 않는데 장비 하나에
     * 수천 건이 30초마다 오간다. BACnet 도 싣지 않는다 — {@code payload.objects} 가 이미 같은 일을 한다.
     */
    public List<TargetInfo> registry;

    /** registry 한 줄. 값이 아니라 '이 target 이 무엇인가' 를 담는다. */
    public static class TargetInfo {
        public String target;
        public Integer simulationId;
        public String name;
        /** Simulation 의 첫 정책이 정한 CTS DataType. 정책이 없으면 null. */
        public String dataType;
        /** 프로토콜이 실제로 내보내는 타입. EtherNet/IP 의 CIP 타입만 채운다. */
        public String wireType;
        public boolean enabled;
    }

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
            snap.registry = registryOf(sd, false);
        } else if (sd instanceof DaeyunData) {
            DaeyunData d = (DaeyunData) sd;
            snap.protocol = Protocol.DAEYUN_RCU;
            int[] digital = new int[32];
            for (int i = 0; i < 32; i++) digital[i] = d.getDigitalChannelValue(i) ? 1 : 0;
            snap.payload.put("analogChannels", d.getAnalogChannelValues(32));
            snap.payload.put("digitalChannels", digital);
            snap.payload.put("txFields", new LinkedHashMap<>(d.getTxFieldsIdValueMap()));
            snap.registry = registryOf(sd, false);
        } else if (sd instanceof SnmpData) {
            SnmpData s = (SnmpData) sd;
            snap.protocol = Protocol.SNMP;
            snap.payload.put("oids", new LinkedHashMap<>(s.getOidValueMap()));
            snap.registry = registryOf(sd, false);
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
            snap.registry = registryOf(sd, true);
        } else if (sd instanceof GenericData) {
            GenericData g = (GenericData) sd;
            snap.protocol = Protocol.GENERIC;
            snap.payload.put("fields", genericFields(g));
            snap.registry = registryOf(sd, false);
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

    /**
     * 이 장비의 Simulation 을 target 목록으로 낸다. {@link ServerData#indexedSimulations()} 를 쓰므로
     * DB 를 다시 보지 않는다.
     *
     * @param withCipType EtherNet/IP 일 때 true. target 에서 옛 타입 접두어를 떼고 CIP 타입을 채운다
     */
    private static List<TargetInfo> registryOf(ServerData sd, boolean withCipType) {
        List<TargetInfo> out = new ArrayList<>();
        for (Simulation s : sd.indexedSimulations()) {
            if (s == null || s.getTarget() == null) continue;
            TargetInfo info = new TargetInfo();
            info.target = withCipType ? EthernetIPTypes.tagOf(s.getTarget()) : s.getTarget();
            info.simulationId = s.getId();
            info.name = s.getName();
            info.enabled = s.getEnabled() == Simulation.ENABLED;
            DataType dataType = firstDataType(s);
            info.dataType = dataType == null ? null : dataType.name();
            if (withCipType) {
                CIPData.Type cip = EthernetIPTypes.cipTypeOf(dataType);
                info.wireType = cip == null ? null : cip.name();
            }
            out.add(info);
        }
        return out;
    }

    /** Simulation 의 첫 정책이 정한 DataType. 정책이 없으면 null. */
    private static DataType firstDataType(Simulation s) {
        List<SimulationPolicy> policies = s.getPolicies();
        return (policies == null || policies.isEmpty()) ? null : policies.get(0).getData_type();
    }

    private static int[] toUnsigned(byte[] b) {
        int[] out = new int[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[i] & 0xFF;
        return out;
    }

    /**
     * GENERIC 의 값: 장비의 프로토콜 정의에 선언된 타겟을 그 순서대로 모두(값이 없으면 0), 그 뒤에 목록 밖의 키
     * (docs/PROTOCOL_ENGINE_DESIGN.md 9.3).
     */
    private static Map<String, Object> genericFields(GenericData g) {
        Map<String, Object> out = new LinkedHashMap<>();
        com.onionsoftware.mk119.commtestsuite.protocol.DefinitionRegistry registry =
                com.onionsoftware.mk119.commtestsuite.protocol.DefinitionRegistry.current();
        com.onionsoftware.mk119.commtestsuite.protocol.Definition def = registry == null || g.getIoServer() == null
                ? null : registry.forDevice(g.getIoServer().getDevice());
        if (def != null) {
            for (String t : def.catalog.keySet()) {
                Double v = g.getTxFieldsIdValueMap().get(t);
                out.put(t, v == null ? 0.0 : v);
            }
        }
        for (Map.Entry<String, Double> e : g.getTxFieldsIdValueMap().entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
        return out;
    }
}
