package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import etherip.types.CIPData;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.LoggerFactory;

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
     * 쓰기 보호 중인 target 과 그 lock 의 authority·만료 시각.
     * <p>
     * 거는 쪽은 셋이다. 사용자의 API 편집(API_WRITE — REST, 편집 세션, Test Plan),
     * 제어 수용의 정지(stopOnControl, PROTOCOL_WRITE), Error 의 정지(simulation_stop, ERROR_SIMULATION).
     * 누가 걸었는지에 따라 막는 쓰기가 다르다 — {@link ServerDataChangeSource#isBlockedBy}.
     */
    private final Map<String, LockEntry> lockedTargets = new ConcurrentHashMap<>();

    /** 만료 없는 lock(편집 세션)에 쓰는 표식. 세션 종료 시 clearPermanentLocks 로 해제된다. */
    private static final long LOCK_NO_EXPIRY = Long.MAX_VALUE;

    private static final class LockEntry {
        final ServerDataChangeSource authority;
        final long until;

        LockEntry(ServerDataChangeSource authority, long until) {
            this.authority = authority;
            this.until = until;
        }
    }

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
            String target = canonicalTarget(s.getTarget());
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
        target = canonicalTarget(target);
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

    /**
     * target 의 내부 표기. 같은 번지를 가리키는 표기가 여럿이면 하나로 모은다.
     * 쓰기·색인·lock·값 읽기가 모두 이걸 거치므로, 들어오는 경로(REST, 시뮬레이션,
     * 프로토콜, Test Plan)가 어떤 표기를 쓰든 같은 칸으로 판정된다. 기본은 그대로다.
     */
    protected String canonicalTarget(String target) {
        return target;
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
        String target = canonicalTarget(simulation.getTarget());
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
        target = canonicalTarget(target);
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
        target = canonicalTarget(target);
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
     *
     * @param authority 정지를 건 경로. stopOnControl 은 PROTOCOL_WRITE, simulation_stop 은 ERROR_SIMULATION.
     *                  더 높은 authority 의 lock 이 걸린 칸은 덮지 않는다.
     */
    public void stopSimulationFor(String target, Integer seconds, DataType dataType,
                                  ServerDataChangeSource authority) {
        if (seconds == null || seconds <= 0) return;
        long until = System.currentTimeMillis() + seconds * 1000L;
        for (String t : computeAffectedTargets(target, dataType)) {
            putLock(t, until, authority);
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
        target = canonicalTarget(target);
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
        baseTarget = canonicalTarget(baseTarget);
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

    /**
     * 쓰기가 lock 에 막히는지. 규칙은 {@link ServerDataChangeSource#isBlockedBy} 가 정한다.
     * <p>
     * 시뮬레이션 계열은 조용히 skip 한다. PROTOCOL_WRITE 는 핸들러가 응답을 평소대로 보내므로
     * 값을 쓰지 않았다는 사실이 여기 로그로만 남는다.
     */
    protected boolean isBlockedByLock(String target, ServerDataChangeSource source) {
        for (String key : lockKeysOf(target)) {
            LockEntry entry = liveLock(key);   // 만료된 lock 은 여기서 자동으로 걸러진다
            if (entry == null || !source.isBlockedBy(entry.authority)) continue;
            if (source == ServerDataChangeSource.PROTOCOL_WRITE) {
                LoggerFactory.getLogger("serverdata").info(
                        "PROTOCOL_WRITE ignored on device {}: target {} is locked by {}.",
                        deviceLabel(), key, entry.authority);
            }
            return true;
        }
        return false;
    }

    /**
     * target 의 lock 을 찾을 때 볼 키. lock 은 값이 실제로 사는 칸에 걸리므로 그 칸으로 찾는다.
     * <p>
     * XGT 는 byte 번지가 그 칸이다. 쓰기와 정지가 {@link #computeAffectedTargets} 로
     * {@code %DB100}, {@code %DB101} 처럼 byte 단위로 lock 을 거는데, 예전에는 판정을
     * {@code %DW50} 그대로 해서 lock 을 못 찾고 시뮬레이션이 잠긴 값을 덮었다.
     * 그래서 W/D target 은 차지하는 byte 를 모두 보고, 하나라도 잠겨 있으면 잠긴 것으로 본다.
     * 다른 프로토콜은 target 그대로다.
     */
    private List<String> lockKeysOf(String target) {
        if (this instanceof XGTData && target != null) {
            try {
                return computeAffectedTargets(target, null);
            } catch (RuntimeException e) {
                // 번지를 읽을 수 없는 target. 쓰기 쪽이 형식 오류로 따로 거절한다.
            }
        }
        return Collections.singletonList(target);
    }

    /** 만료 없는 API_WRITE lock. 편집 세션이 끝날 때 {@link #clearPermanentLocks()} 로 풀린다. */
    public void lock(String target) {
        putLock(target, LOCK_NO_EXPIRY, ServerDataChangeSource.API_WRITE);
    }

    /**
     * API_WRITE 의 lock. 초 단위 만료를 지정한다.
     * <ul>
     *   <li>{@code seconds > 0} : 해당 초 후 자동 해제. 세션이 없는 외부 REST 호출과 Test Plan 용.</li>
     *   <li>{@code seconds < 0} ({@link #PERMANENT_LOCK_SECONDS} 가 표준값) : 만료 없는 lock.
     *       호출자에게 살아있는 편집 세션이 있을 때만 접수되며, 세션 종료 시
     *       {@link #clearPermanentLocks()} 로 풀린다.
     *       음수 전체를 같게 보는 이유는 {@code -2} 같은 값이 조용히 "lock 안 함" 으로
     *       떨어져 호출자 의도와 어긋나는 걸 막기 위한 것이다.</li>
     *   <li>{@code null} 또는 {@code 0} : lock 하지 않는다 (기존 lock 이 있으면 해제한다).
     *       API_WRITE 가 최상위 authority 라서 누가 건 lock 이든 풀린다.</li>
     * </ul>
     */
    public void lock(String target, Integer seconds) {
        if (isPermanentLockRequest(seconds)) {
            putLock(target, LOCK_NO_EXPIRY, ServerDataChangeSource.API_WRITE);
            return;
        }
        if (seconds == null || seconds <= 0) {
            lockedTargets.remove(target);
            return;
        }
        putLock(target, System.currentTimeMillis() + seconds * 1000L, ServerDataChangeSource.API_WRITE);
    }

    /**
     * lock 을 건다. 살아 있는 lock 의 authority 가 더 높으면 덮지 않는다 —
     * 낮은 쪽이 높은 쪽의 lock 을 줄이거나 바꾸지 못하게 하기 위해서다.
     * 같은 authority 면 나중 것으로 갱신한다(만료 시각 포함).
     */
    private void putLock(String target, long until, ServerDataChangeSource authority) {
        long now = System.currentTimeMillis();
        lockedTargets.compute(target, (k, cur) -> {
            if (cur != null && (cur.until == LOCK_NO_EXPIRY || now < cur.until)
                    && cur.authority.authority() > authority.authority()) {
                return cur;
            }
            return new LockEntry(authority, until);
        });
    }

    /** 로그용 장비 표기. ioServer 가 아직 wire 되지 않았으면 "?" . */
    private String deviceLabel() {
        if (ioServer == null || ioServer.getDevice() == null) return "?";
        return ioServer.getDevice().getName() + "(" + ioServer.getDevice().getId() + ")";
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
        lockedTargets.remove(canonicalTarget(target));
    }

    public boolean isLocked(String target) {
        for (String key : lockKeysOf(target)) {
            if (liveLock(key) != null) return true;
        }
        return false;
    }

    /** 살아 있는 lock. 없거나 만료됐으면 null 이고, 만료분은 조회 시점에 치운다. */
    private LockEntry liveLock(String target) {
        LockEntry entry = lockedTargets.get(target);
        if (entry == null) return null;
        if (entry.until != LOCK_NO_EXPIRY && System.currentTimeMillis() >= entry.until) {
            lockedTargets.remove(target, entry);
            return null;
        }
        return entry;
    }

    /**
     * 만료 없는 lock 만 푼다. 편집 세션이 끝날 때 부른다.
     * 만료 없는 lock 은 편집 세션만 걸 수 있으므로 곧 세션 자신의 lock 이다.
     * 초 단위 lock 은 누가 걸었든 — 세션 자신이라도 — 만료될 때까지 둔다.
     */
    public void clearPermanentLocks() {
        lockedTargets.values().removeIf(e -> e.until == LOCK_NO_EXPIRY);
    }

    /** 만료된 항목을 제외한 현재 lock 목록. */
    public Set<String> getLockedTargets() {
        long now = System.currentTimeMillis();
        Set<String> live = new HashSet<>();
        Iterator<Map.Entry<String, LockEntry>> it = lockedTargets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LockEntry> e = it.next();
            if (e.getValue().until != LOCK_NO_EXPIRY && now >= e.getValue().until) {
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
