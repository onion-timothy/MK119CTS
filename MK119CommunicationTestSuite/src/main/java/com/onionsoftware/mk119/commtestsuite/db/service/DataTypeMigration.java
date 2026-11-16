package com.onionsoftware.mk119.commtestsuite.db.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.DataTypeRules;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 저장된 data_type 을 {@link DataTypeRules} 의 표에 맞게 옮긴다. 기동 시 한 번, 장비가 뜨기 전에 부른다.
 * 이미 맞는 값은 건드리지 않으므로 매번 불러도 된다.
 * <p>
 * 정책은 JSON 문자열을 직접 고친다. 엔티티로 읽으면 없어진 타입(BOOL, BITS)이 null 로 읽혀
 * 무엇이었는지 알 수 없기 때문이다.
 * <pre>
 *   EtherNet/IP          BOOL → BINARY, SI8 → SI8, UI8·SI16* → SI16BA, BITS·UI16*·32·64비트 정수 → SI32DA, FP* → FP32DA
 *                        (UI8·UI16 은 예전에 한 단계 넓은 INT·DINT 로 나갔다. 나가던 CIP 타입을 그대로 둔다)
 *   Modbus 1_, 2_        BINARY
 *   BACnet·SNMP·Generic·OPC·DB·Daeyun
 *                        target 이 타입 하나만 받으면 그 타입 (BACnet binary_* → BINARY 등)
 *                        아니면 FP* 와 미지정 → FLOAT, 정수 → INTEGER (값에 소수가 있으면 FLOAT)
 *   Modbus 레지스터·XGT   그대로
 * </pre>
 * Test Plan Action 도 같은 규칙이다. 다만 비어 있는 dataType 은 그대로 둔다 — Action 은 비워도 된다.
 */
@Service
@RequiredArgsConstructor
public class DataTypeMigration {
    private static final Logger logger = LoggerFactory.getLogger("simulation");
    private static final Gson GSON = new Gson();
    private static final Type POLICY_LIST = new TypeToken<List<Map<String, Object>>>() {
    }.getType();

    private final JdbcTemplate jdbcTemplate;
    private final DeviceService deviceService;

    /** @return {옮긴 Simulation 수, 옮긴 Test Plan Action 수} */
    public int[] migrate() {
        Map<Integer, Device> devices = new HashMap<>();
        for (Device d : deviceService.selectDevices()) devices.put(d.getId(), d);
        return new int[]{migrateSimulations(devices), migrateTestPlanActions(devices)};
    }

    private int migrateSimulations(Map<Integer, Device> devices) {
        int n = 0;
        for (Device device : devices.values()) {
            if (device.getProtocol() == null) continue;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, target, policies FROM simulations WHERE device_id = ?", device.getId());
            for (Map<String, Object> row : rows) {
                Object raw = row.get("policies");
                String target = row.get("target") == null ? "" : row.get("target").toString();
                if (raw == null) continue;
                List<Map<String, Object>> policies;
                try {
                    policies = GSON.fromJson(raw.toString(), POLICY_LIST);
                } catch (RuntimeException e) {
                    logger.warn("Simulation {} has unreadable policies. data_type left as is.", row.get("id"));
                    continue;
                }
                if (policies == null) continue;
                boolean changed = false;
                StringBuilder log = new StringBuilder();
                for (Map<String, Object> p : policies) {
                    if (p == null) continue;
                    String before = p.get("data_type") == null ? null : p.get("data_type").toString();
                    String after = forSimulation(device.getProtocol(), target, before, hasFraction(p));
                    if (Objects.equals(before, after)) continue;
                    if (after == null) p.remove("data_type"); else p.put("data_type", after);
                    changed = true;
                    log.append(before).append(" -> ").append(after).append(' ');
                }
                if (!changed) continue;
                jdbcTemplate.update("UPDATE simulations SET policies = ? WHERE id = ?", GSON.toJson(policies), row.get("id"));
                n++;
                logger.info("Simulation {} on device {}({}) data_type migrated: {}",
                        row.get("id"), device.getName(), device.getId(), log.toString().trim());
            }
        }
        return n;
    }

    private int migrateTestPlanActions(Map<Integer, Device> devices) {
        int n = 0;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, device_id, target, `value`, data_type FROM test_plan_actions");
        for (Map<String, Object> row : rows) {
            Object deviceId = row.get("device_id");
            Device device = deviceId == null ? null : devices.get(((Number) deviceId).intValue());
            String before = row.get("data_type") == null ? null : row.get("data_type").toString();
            if (device == null || device.getProtocol() == null || before == null || before.isEmpty()) continue;
            double value = row.get("value") == null ? 0 : ((Number) row.get("value")).doubleValue();
            String after = forSimulation(device.getProtocol(), String.valueOf(row.get("target")), before,
                    value != Math.rint(value));
            if (Objects.equals(before, after)) continue;
            jdbcTemplate.update("UPDATE test_plan_actions SET data_type = ? WHERE id = ?", after, row.get("id"));
            n++;
            logger.info("Test plan action {} data_type migrated: {} -> {}", row.get("id"), before, after);
        }
        return n;
    }

    /**
     * 새 data_type 이름. 이미 표에 맞으면 그대로 돌려준다.
     *
     * @param before      저장된 이름. 없어진 타입(BOOL, BITS)일 수 있다
     * @param hasFraction 정책의 값에 소수가 있는지. 정수 타입을 INTEGER 와 FLOAT 중 어디로 옮길지 정한다
     */
    static String forSimulation(Protocol protocol, String target, String before, boolean hasFraction) {
        String t = target == null ? "" : target.trim();
        List<DataType> allowed = DataTypeRules.allowed(protocol, t);
        if (before != null && isKnown(before) && allowed.contains(DataType.valueOf(before))) return before;
        switch (protocol) {
            case ETHERNET_IP:
                return before == null ? null : ethernetIp(before);
            case MODBUS:
                return (t.startsWith("1_") || t.startsWith("2_")) ? DataType.BINARY.name() : before;
            case XGT_PLC:
                return before;
            default: {
                DataType implied = DataTypeRules.implied(protocol, t);
                if (implied != null) return implied.name();
                if (before == null || before.startsWith("FP") || hasFraction) return DataType.FLOAT.name();
                if ("BOOL".equals(before)) return DataType.BINARY.name();
                return DataType.INTEGER.name();
            }
        }
    }

    private static String ethernetIp(String before) {
        if ("BOOL".equals(before) || "BINARY".equals(before)) return DataType.BINARY.name();
        if ("SI8".equals(before)) return DataType.SI8.name();
        if (before.startsWith("UI8") || before.startsWith("SI16")) return DataType.SI16BA.name();
        if (before.startsWith("FP") || "FLOAT".equals(before)) return DataType.FP32DA.name();
        return DataType.SI32DA.name();
    }

    private static boolean isKnown(String name) {
        for (DataType t : DataType.values()) if (t.name().equals(name)) return true;
        return false;
    }

    /** STATIC 값, SEQUENCE 값, RANDOM·ACCUMULATE 범위 중 소수가 있는지. */
    static boolean hasFraction(Map<String, Object> policy) {
        return fraction(policy.get("value")) || fraction(policy.get("range"));
    }

    private static boolean fraction(Object o) {
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            return d != Math.rint(d);
        }
        if (o instanceof List) {
            for (Object e : (List<?>) o) if (fraction(e)) return true;
        }
        return false;
    }
}
