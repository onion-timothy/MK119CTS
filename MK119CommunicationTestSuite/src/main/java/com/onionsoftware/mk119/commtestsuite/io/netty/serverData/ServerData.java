package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import etherip.types.CIPData;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ServerData {
    /** 이 ServerData가 속한 IOServer. 팩토리/세터에서 자동 wire된다. 리스너 발화 창구로만 사용. */
    @Getter
    @Setter
    protected IOServer ioServer;

    /**
     * 쓰기 보호 중인 target 집합. 값은 만료 시각.
     * <p>
     * 거는 쪽은 둘이다. 사용자의 API/WS 편집(API_WRITE)과, Error/제어 수용의 정지(simulation_stop,
     * stopOnControl). 둘을 구분해 담지 않는다 — 누가 걸었든 SIMULATION 계열 쓰기를 똑같이 막고,
     * 사용자의 unlock/세션 종료는 똑같이 전부 푼다. 구분을 두면 화면에 "내가 못 푸는 잠금"이
     * 생겨 사용자가 상태를 설명할 수 없게 된다.
     */
    private final Map<String, Long> lockedTargets = new ConcurrentHashMap<>();

    /** 만료 없는 lock(WS 편집 세션)에 쓰는 표식. 세션 종료 시 clearAllLocks 로 해제된다. */
    private static final long LOCK_NO_EXPIRY = Long.MAX_VALUE;

    /** lockSeconds 로 이 값(또는 아무 음수)을 보내면 만료 없는 lock 이 된다. */
    public static final int PERMANENT_LOCK_SECONDS = -1;

    /** XGT 에서 쓸 수 있는 블록 문자. 리플렉션으로 get<X>block 을 찾기 전에 이걸로 검증한다. */
    private static final String XGT_BLOCK_NAMES = "MDPKSU";

    /** Daeyun 저장 구조의 채널 수. analogChannelValues[32] / BitSet(32) 와 맞춘다. */
    private static final int DAEYUN_CHANNELS = 32;

    // ── target -> Simulation 색인 ───────────────────────────────────────
    /**
     * 이 장비의 Simulation 을 target 으로 찾기 위한 색인.
     * <p>
     * 핸들러는 패킷을 받을 때마다 "이 주소에 걸린 Simulation 이 있나" 를 물어야 한다.
     * 예전에는 그때마다 DB 를 조회했다. 패킷 경로에 DB 왕복이 들어가는 것도 문제지만,
     * 제어 수용처럼 조회 결과로 곧바로 잠금을 거는 자리에서는 그 사이에 시뮬레이션이
     * 끼어들어 방금 쓴 값을 덮는 일까지 생겼다.
     * <p>
     * 그래서 ServerData 가 들고 있는다. 값이 사는 곳과 색인이 같은 자리에 있어야
     * 모든 핸들러와 API 가 같은 것을 본다.
     */
    private volatile Map<String, Simulation> indexExact = Collections.emptyMap();
    /** 한 Simulation 이 차지하는 모든 target (예: UI32 는 레지스터 두 칸). */
    private volatile Map<String, Simulation> indexCovered = Collections.emptyMap();
    private volatile List<Simulation> indexed = Collections.emptyList();

    /**
     * 색인을 통째로 갈아 끼운다. Simulation 이 바뀔 때마다 SimulationService 가 부른다.
     * 부분 갱신을 두지 않는 이유는, 한 장비의 Simulation 수가 많아야 수천이고
     * 바뀌는 빈도는 낮아서 통째로 다시 만드는 편이 틀릴 여지가 없기 때문이다.
     */
    public void indexSimulations(List<Simulation> simulations) {
        if (simulations == null) simulations = Collections.emptyList();
        Map<String, Simulation> exact = new ConcurrentHashMap<>();
        Map<String, Simulation> covered = new ConcurrentHashMap<>();
        for (Simulation s : simulations) {
            String target = s.getTarget();
            if (target == null || target.isEmpty()) continue;
            exact.putIfAbsent(target, s);
            List<SimulationPolicy> policies = s.getPolicies();
            DataType dataType = (policies == null || policies.isEmpty())
                    ? null : policies.get(0).getData_type();
            try {
                for (String t : computeAffectedTargets(target, dataType)) {
                    covered.putIfAbsent(t, s);
                }
            } catch (RuntimeException e) {
                // target 형식이 깨진 과거 데이터. 색인만 건너뛰고 나머지는 살린다.
                covered.putIfAbsent(target, s);
            }
            for (String alias : aliasesOf(s)) {
                if (alias != null && !alias.isEmpty()) exact.putIfAbsent(alias, s);
            }
        }
        this.indexExact = exact;
        this.indexCovered = covered;
        this.indexed = Collections.unmodifiableList(new ArrayList<>(simulations));
    }

    /**
     * target 으로 Simulation 을 찾는다. 정확히 일치하는 것을 먼저 보고,
     * 없으면 그 target 을 차지하고 있는 Simulation 을 본다 (다중 레지스터 등).
     *
     * @return 없으면 null
     */
    public Simulation simulationFor(String target) {
        if (target == null) return null;
        Simulation hit = indexExact.get(target);
        return hit != null ? hit : indexCovered.get(target);
    }

    /** 색인에 들어 있는 Simulation 목록. 구조가 특수해 target 으로 못 찾는 쪽이 쓴다. */
    public List<Simulation> indexedSimulations() {
        return indexed;
    }

    /**
     * 프로토콜별 보조 키. target 말고 다른 이름으로도 찾아야 하는 경우 하위 클래스가 채운다.
     * 예를 들어 EtherNet/IP 는 패킷에 태그 이름만 실려 와서 {@code REAL@TAG1} 의 {@code TAG1}
     * 로도 찾을 수 있어야 한다.
     */
    protected Collection<String> aliasesOf(Simulation simulation) {
        return Collections.emptyList();
    }

    public abstract void resetServerData();

    protected int extractNumbers(String input) {
        StringBuilder numbers = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isDigit(ch)) {
                numbers.append(ch);
            }
        }

        return Integer.parseInt(numbers.toString());
    }

    /**
     * Simulation 컨텍스트가 있는 세팅 (VALUE_SIMULATION / ERROR_SIMULATION, 또는 매칭된 PROTOCOL_WRITE).
     *
     * @return 실제로 썼으면 true, lock 에 막혀 아무것도 안 했으면 false
     */
    public boolean setServerData(Simulation simulation, double value, ServerDataChangeSource source) throws Exception {
        String target = simulation.getTarget();
        if (isBlockedByLock(target, source)) return false;

        List<SimulationPolicy> policies = simulation.getPolicies();
        DataType dataType = (policies == null || policies.isEmpty()) ? null : policies.get(0).getData_type();
        applySetServerData(target, value, dataType);
        List<String> affected = computeAffectedTargets(target, dataType);
        if (source == ServerDataChangeSource.API_WRITE) {
            for (String t : affected) lock(t);
        }
        fireChangeEvent(source, target, value, affected, simulation);
        return true;
    }

    /**
     * 프로토콜 제어 수용 등 Simulation 컨텍스트 없이 target/dataType 만으로 세팅.
     * API_WRITE 는 만료 없는 lock 을 건다 (WS 편집 세션이 세션 종료 시 해제).
     *
     * @return 실제로 썼으면 true, lock 에 막혀 아무것도 안 했으면 false
     */
    public boolean setServerData(String target, double value, DataType dataType, ServerDataChangeSource source) throws Exception {
        if (isBlockedByLock(target, source)) return false;
        applySetServerData(target, value, dataType);
        List<String> affected = computeAffectedTargets(target, dataType);
        if (source == ServerDataChangeSource.API_WRITE) {
            for (String t : affected) lock(t);
        }
        fireChangeEvent(source, target, value, affected, null);
        return true;
    }

    /**
     * lock 만료 시간을 지정하는 세팅. 세션이 없어 lock 을 풀 수단이 없는 REST 호출용이다.
     * lockSeconds 가 null 이거나 0 이하면 lock 하지 않으므로 simulation 이 계속 값을 갱신한다.
     */
    public boolean setServerData(String target, double value, DataType dataType,
                                 ServerDataChangeSource source, Integer lockSeconds) throws Exception {
        if (isBlockedByLock(target, source)) return false;
        applySetServerData(target, value, dataType);
        List<String> affected = computeAffectedTargets(target, dataType);
        if (source == ServerDataChangeSource.API_WRITE) {
            for (String t : affected) lock(t, lockSeconds);
        }
        fireChangeEvent(source, target, value, affected, null);
        return true;
    }

    /**
     * Simulation 정지(Error 의 simulation_stop, Simulation 의 stopOnControl)를 건다.
     * 쓰기 보호와 같은 수단을 쓰므로 정지 중에는 값이 멈출 뿐 아니라
     * ACCUMULATE 누적이나 SEQUENCE 위치도 나아가지 않는다.
     * 0 이하는 "정지 없음" 으로 본다 — 음수 동작은 정의하지 않는다.
     */
    public void stopSimulationFor(String target, Integer seconds, DataType dataType) {
        if (seconds == null || seconds <= 0) return;
        for (String t : computeAffectedTargets(target, dataType)) {
            lock(t, seconds);
        }
    }

    /**
     * 하나의 쓰기 동작이 실제로 건드리는 전체 target 목록을 계산한다.
     * 예: Modbus FC3/FC4 UI32 는 2개 레지스터, SI64 는 4개 레지스터를 함께 쓴다.
     * 인식되지 않으면 기본으로 baseTarget 한 개만 반환.
     */
    /**
     * 주어진 target 의 현재 값을 반환한다. Modbus/XGT 는 register/byte 단위 raw 값.
     * 다른 프로토콜은 저장된 double 값 그대로. 인식 못하면 null.
     */
    public Number readValueAt(String target) {
        if (target == null) return null;
        if (this instanceof ModbusData && target.contains("_")) {
            ModbusData m = (ModbusData) this;
            String[] tk = target.split("_");
            int fc = Integer.parseInt(tk[0]);
            int addr = Integer.parseInt(tk[1]);
            if (fc == 1) return m.getCoil_status().get(addr) ? 1 : 0;
            if (fc == 2) return m.getInput_status().get(addr) ? 1 : 0;
            if (fc == 3) return m.getHolding_registers()[addr] & 0xFFFF;
            if (fc == 4) return m.getInput_registers()[addr] & 0xFFFF;
            return null;
        }
        if (this instanceof XGTData && target.length() >= 3) {
            XGTData x = (XGTData) this;
            char blockChar = target.charAt(1);
            int addr = extractNumbers(target);
            byte[] block = null;
            if (blockChar == 'M') block = x.getMblock();
            else if (blockChar == 'D') block = x.getDblock();
            else if (blockChar == 'P') block = x.getPblock();
            else if (blockChar == 'K') block = x.getKblock();
            else if (blockChar == 'S') block = x.getSblock();
            else if (blockChar == 'U') block = x.getUblock();
            if (block != null && addr < block.length) return block[addr] & 0xFF;
            return null;
        }
        if (this instanceof OPCAgentData) {
            try { return ((OPCAgentData) this).getValues()[Integer.parseInt(target)]; } catch (Exception e) { return null; }
        }
        if (this instanceof DaeyunData) {
            DaeyunData d = (DaeyunData) this;
            if (target.startsWith("@RCUDI_")) {
                int ch = Integer.parseInt(target.split("_")[1]) - 1;
                return d.getDigitalChannelValue(ch) ? 1 : 0;
            }
            if (target.startsWith("@RCUAI_")) {
                int ch = Integer.parseInt(target.split("_")[1]) - 1;
                return d.getAnalogChannelValues(32)[ch];
            }
            Double v = d.getTxFieldsIdValueMap().get(target);
            return v;
        }
        if (this instanceof SnmpData) return ((SnmpData) this).getOidValueMap().get(target);
        if (this instanceof GenericData) return ((GenericData) this).getTxFieldsIdValueMap().get(target);
        if (this instanceof BACnetData) {
            // BACnet 은 simulation ID 로 저장하므로 target → simulation 을 먼저 찾는다.
            return ((BACnetData) this).readValue(target);
        }
        if (this instanceof EthernetIPData) {
            // 저장 키는 type prefix 를 떼어낸 tag 다. target 은 "<CIPType>@<Tag>" 로 올 수도 있다.
            String tag = target.contains("@") ? target.substring(target.indexOf('@') + 1) : target;
            CIPData data = ((EthernetIPData) this).getTagValue(tag);
            if (data == null || !data.isNumeric()) return null;
            try {
                return data.getNumber(0);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public List<String> computeAffectedTargets(String baseTarget, DataType dataType) {
        List<String> out = new ArrayList<>();
        if (this instanceof ModbusData && baseTarget != null && baseTarget.contains("_")) {
            String[] tk = baseTarget.split("_");
            int fc = Integer.parseInt(tk[0]);
            int reg = Integer.parseInt(tk[1]);
            int span = 1;
            if ((fc == 3 || fc == 4) && dataType != null) {
                // 각 register = 2 bytes
                span = Math.max(1, dataType.getByteCount() / 2);
            }
            for (int i = 0; i < span; i++) out.add(fc + "_" + (reg + i));
            return out;
        }
        if (this instanceof XGTData && baseTarget != null && baseTarget.length() >= 3) {
            char blockChar = baseTarget.charAt(1);           // M, D, P, K, S, U
            char dataTypeChar = baseTarget.charAt(2);        // B, W, D
            int addr = extractNumbers(baseTarget);
            int byteOffset;
            int span;
            if (dataTypeChar == 'B') { byteOffset = addr; span = 1; }
            else if (dataTypeChar == 'W') { byteOffset = addr * 2; span = 2; }
            else if (dataTypeChar == 'D') { byteOffset = addr * 4; span = 4; }
            else { out.add(baseTarget); return out; }
            // 실제 스냅샷/UI 는 byte 단위 (%<block>B<idx>) 로 표현하므로 그 형식으로 반환
            for (int i = 0; i < span; i++) out.add("%" + blockChar + "B" + (byteOffset + i));
            return out;
        }
        out.add(baseTarget);
        return out;
    }

    /** SIMULATION 계열 소스에서 lock 대상 target 을 만나면 조용히 skip. */
    protected boolean isBlockedByLock(String target, ServerDataChangeSource source) {
        return (source == ServerDataChangeSource.VALUE_SIMULATION
                || source == ServerDataChangeSource.ERROR_SIMULATION)
                && isLocked(target);   // 만료된 lock 은 여기서 자동으로 걸러진다
    }

    /** 만료 없는 lock. WS 편집 세션이 쓰며, 세션 종료 시 {@link #clearAllLocks()} 로 풀린다. */
    public void lock(String target) {
        lockedTargets.put(target, LOCK_NO_EXPIRY);
    }

    /**
     * 초 단위 만료가 있는 lock.
     * <ul>
     *   <li>{@code seconds > 0} : 해당 초 후 자동 해제. 세션이 없는 외부 REST 호출용.</li>
     *   <li>{@code seconds < 0} ({@link #PERMANENT_LOCK_SECONDS} 가 표준값) : 만료 없는 lock.
     *       호출자에게 살아있는 편집 세션이 있을 때만 접수되며, 세션 종료 시
     *       {@link #clearAllLocks()} 로 함께 풀린다.
     *       음수 전체를 같게 보는 이유는 {@code -2} 같은 값이 조용히 "lock 안 함" 으로
     *       떨어져 호출자 의도와 어긋나는 걸 막기 위한 것이다.</li>
     *   <li>{@code null} 또는 {@code 0} : lock 하지 않는다 (기존 lock 이 있으면 해제한다).</li>
     * </ul>
     */
    public void lock(String target, Integer seconds) {
        if (isPermanentLockRequest(seconds)) {
            lockedTargets.put(target, LOCK_NO_EXPIRY);
            return;
        }
        if (seconds == null || seconds <= 0) {
            lockedTargets.remove(target);
            return;
        }
        lockedTargets.put(target, System.currentTimeMillis() + seconds * 1000L);
    }

    /** 만료 없는 lock 요청인지. 음수는 모두 영구 lock 요청으로 본다. */
    public static boolean isPermanentLockRequest(Integer seconds) {
        return seconds != null && seconds < 0;
    }

    /** {@link #lock(String, Integer)} 가 실제로 lock 을 거는 요청인지. */
    public static boolean isLockRequest(Integer seconds) {
        return seconds != null && seconds != 0;
    }

    public void unlock(String target) {
        lockedTargets.remove(target);
    }

    public boolean isLocked(String target) {
        Long until = lockedTargets.get(target);
        if (until == null) return false;
        if (until != LOCK_NO_EXPIRY && System.currentTimeMillis() >= until) {
            lockedTargets.remove(target);   // 만료분은 조회 시점에 치운다
            return false;
        }
        return true;
    }

    public void clearAllLocks() {
        lockedTargets.clear();
    }

    /** 만료된 항목을 제외한 현재 lock 목록. */
    public Set<String> getLockedTargets() {
        long now = System.currentTimeMillis();
        Set<String> live = new HashSet<>();
        Iterator<Map.Entry<String, Long>> it = lockedTargets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() != LOCK_NO_EXPIRY && now >= e.getValue()) {
                it.remove();
            } else {
                live.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(live);
    }

    private void applySetServerData(String target, double value, DataType dataType) throws Exception {
        if (this instanceof GenericData) {
            GenericData genericData = (GenericData) this;
            genericData.getTxFieldsIdValueMap().put(target, value);
        } else if (this instanceof ModbusData) {
            ModbusData modbusData = (ModbusData) this;
            String[] targetTokens = target.split("_");
            int function = Integer.parseInt(targetTokens[0]);
            int register = Integer.parseInt(targetTokens[1]);

            // 범위를 먼저 본다. 그냥 쓰면 ArrayIndexOutOfBounds 가 올라가 원인이 불분명해진다.
            int registerCount = modbusData.getHolding_registers().length;
            if (register < 0 || register >= registerCount) {
                throw new IllegalArgumentException(String.format(
                        "Modbus address %d is out of range. This device has %d registers (0..%d). target=%s",
                        register, registerCount, registerCount - 1, target));
            }

            if (function == Modbus.FC_01) {
                modbusData.setCoil_status(value == 1, register);
            } else if (function == Modbus.FC_02) {
                modbusData.setInput_status(value == 1, register);
            } else if (function == Modbus.FC_03) {
                modbusData.setHolding_registers(value, dataType, register);
            } else if (function == Modbus.FC_04) {
                modbusData.setInput_registers(value, dataType, register);
            } else {
                // 쓰기 가능한 FC 는 1/2/3/4 뿐이다. 조용히 통과시키면 호출자가 성공으로 오해한다.
                throw new UnsupportedOperationException(String.format(
                        "Modbus function code %d is not writable. target=%s", function, target));
            }
        } else if (this instanceof XGTData) {
            XGTData xgtData = (XGTData) this;
            int addr = extractNumbers(target);
            String block = String.valueOf(target.charAt(1));
            char dataTypeChar = target.charAt(2);

            // 블록 문자를 리플렉션 전에 검증한다. 예전에는 getMethod("getZblock") 이
            // NoSuchMethodException 을 던져 500 이 됐다.
            if (XGT_BLOCK_NAMES.indexOf(Character.toUpperCase(block.charAt(0))) < 0) {
                throw new UnsupportedOperationException(String.format(
                        "XGTPLC unknown block '%s'. target=%s (expected one of M, D, P, K, S, U)",
                        block, target));
            }

            if (dataTypeChar == 'B') {
                if (dataType == null || !dataType.name().contains("8")) {
                    throw new RuntimeException(String.format(
                            "XGTPLC wrong data type. target=%s, dataType=%s, dataTypeChar=%s",
                            target, dataType == null ? null : dataType.name(), dataTypeChar));
                }
            } else if (dataTypeChar == 'W') {
                if (dataType == null || !dataType.name().contains("16")) {
                    throw new RuntimeException(String.format(
                            "XGTPLC wrong data type. target=%s, dataType=%s, dataTypeChar=%s",
                            target, dataType == null ? null : dataType.name(), dataTypeChar));
                }
                addr *= 2;
            } else if (dataTypeChar == 'D') {
                if (dataType == null || !dataType.name().contains("32")) {
                    throw new RuntimeException(String.format(
                            "XGTPLC wrong data type. target=%s, dataType=%s, dataTypeChar=%s",
                            target, dataType == null ? null : dataType.name(), dataTypeChar));
                }
                addr *= 4;
            } else {
                // B/W/D 외의 size char 는 주소 배수도 결정할 수 없다. 그대로 쓰면 엉뚱한 주소에 기록된다.
                throw new UnsupportedOperationException(String.format(
                        "XGTPLC unknown size char '%s'. target=%s (expected B, W or D)",
                        dataTypeChar, target));
            }

            xgtData.setBlockData(block, dataType, value, addr);
        } else if (this instanceof DaeyunData) {
            DaeyunData daeyunData = (DaeyunData) this;
            if (target.startsWith("@RCUDI_") || target.startsWith("@RCUAI_")) {
                boolean digital = target.startsWith("@RCUDI_");
                int channel = Integer.parseInt(target.split("_")[1]);
                // 저장 구조가 32채널 고정이다. 아날로그는 범위를 넘으면 예외가 났지만
                // 디지털은 BitSet 이 자동 확장되어 유령 비트에 조용히 기록됐다.
                if (channel < 1 || channel > DAEYUN_CHANNELS) {
                    throw new IllegalArgumentException(String.format(
                            "Daeyun %s channel %d is out of range (1..%d). target=%s",
                            digital ? "digital" : "analog", channel, DAEYUN_CHANNELS, target));
                }
                if (digital) {
                    daeyunData.setDigitalChannelValues(channel - 1, value != 0);
                } else {
                    daeyunData.setAnalogChannelValues(channel - 1, (short) value);
                }
            } else {
                daeyunData.getTxFieldsIdValueMap().put(target, value);
            }
        } else if (this instanceof OPCAgentData) {
            OPCAgentData opcAgentData = (OPCAgentData) this;
            int index = Integer.parseInt(target);
            int size = opcAgentData.getValues().length;
            if (index < 0 || index >= size) {
                throw new IllegalArgumentException(String.format(
                        "OPC index %d is out of range (0..%d). target=%s", index, size - 1, target));
            }
            opcAgentData.setValue(index, value);
        } else if (this instanceof SnmpData) {
            SnmpData snmpData = (SnmpData) this;
            snmpData.getOidValueMap().put(target, value);
        } else if (this instanceof EthernetIPData) {
            EthernetIPData ethernetIPData = (EthernetIPData) this;
            ethernetIPData.putTagValue(target, value);
        } else {
            // 분기가 없는 프로토콜(BACNET 등)은 여기서 멈춘다.
            // 예전에는 조용히 통과해서 호출자가 ok 를 받고, 그 뒤 lock 까지 걸려 simulation 이 멈췄다.
            throw new UnsupportedOperationException(
                    getClass().getSimpleName() + " does not support target-based write. target=" + target);
        }
    }

    protected void fireChangeEvent(ServerDataChangeSource source, String target, double value, Simulation simulation) {
        fireChangeEvent(source, target, value, Collections.singletonList(target), simulation);
    }

    protected void fireChangeEvent(ServerDataChangeSource source, String target, double value,
                                   List<String> affectedTargets, Simulation simulation) {
        if (ioServer == null) return;
        // 각 target 의 실제 반영된 값을 함께 실어 보낸다 (Modbus/XGT 다중 register 정확 표시)
        java.util.Map<String, Number> targetValues = new java.util.LinkedHashMap<>();
        for (String t : affectedTargets) {
            Number v = readValueAt(t);
            if (v != null) targetValues.put(t, v);
        }
        ioServer.fireServerDataChanged(
                new ServerDataEvent(ioServer, source, target, value, affectedTargets, targetValues, simulation));
    }
}
