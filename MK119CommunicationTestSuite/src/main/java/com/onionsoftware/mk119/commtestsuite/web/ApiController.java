package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.bean.*;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceSettingsValidator;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.GroupService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationTargetValidator;
import com.onionsoftware.mk119.commtestsuite.enums.Policy;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.EthernetIPTypes;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import com.onionsoftware.mk119.commtestsuite.io.netty.handler.MkHandler;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.web.bean.ErrorStateStats;
import com.onionsoftware.mk119.commtestsuite.web.bean.ServerDataSnapshot;
import com.onionsoftware.mk119.commtestsuite.web.bean.ServerDataWriteRequest;
import com.onionsoftware.mk119.commtestsuite.web.bean.Session;
import com.onionsoftware.mk119.commtestsuite.web.dto.SimulationListItem;
import com.onionsoftware.mk119.commtestsuite.web.serverdata.ServerDataWriteService;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.persistence.EntityNotFoundException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping(value = "/api")
@RequiredArgsConstructor
public class ApiController {
    private final DeviceService deviceService;
    private final GroupService groupService;
    private final SimulationService simulationService;
    private final IOServerInstances ioServerInstances;
    private final ErrorService errorService;
    private final ServerDataWriteService serverDataWriteService;
    private final SimulationTargetValidator simulationTargetValidator;
    private final Logger logger = LoggerFactory.getLogger("api");

    @RequestMapping(value = "/device/devices/{group_id}", method = RequestMethod.GET)
    public List<Device> getDevicesByGroupId(@PathVariable Integer group_id) {
        return deviceService.selectDevicesByGroupId(group_id);
    }

    @RequestMapping(value = "/device/devices/treeData", method = RequestMethod.GET)
    public List<Map<?, ?>> getDevicesTreeData(@RequestParam Integer id) {
        List<Group> groups = new ArrayList<>(groupService.selectGroupsByParentGroupId(id));
        List<Device> devices = new ArrayList<>(deviceService.selectDevicesByGroupId(id));

        Comparator<String> nameCmp = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        groups.sort(Comparator.comparing((Group g) -> g.getName(), nameCmp));
        devices.sort(Comparator.comparing((Device d) -> d.getName(), nameCmp));

        List<Map<?, ?>> ret = new LinkedList<>();
        for (Group group : groups) {
            Map<Object, Object> groupObj = new HashMap<>();
            groupObj.put("id", "g_" + group.getId());
            // parentId 가 비어 있는 행도 트리 전체를 깨뜨리지 않도록 최상위로 올린다.
            Integer parentId = group.getParentId();
            groupObj.put("parent", parentId == null || parentId == 0 ? "#" : "g_" + parentId);
            groupObj.put("text", group.getName());
            groupObj.put("children", true);
            groupObj.put("type", "group");
            // 트리가 그룹 옆에 식구 수를 적는다. 펼치기 전에도 보여야 해서 여기서 세어 보낸다
            groupObj.put("count", groupService.selectGroupsByParentGroupId(group.getId()).size()
                    + deviceService.selectDevicesByGroupId(group.getId()).size());
            ret.add(groupObj);
        }

        for (Device device : devices) {
            Map<Object, Object> deviceObj = new HashMap<>();
            deviceObj.put("id", "d_" + device.getId());
            deviceObj.put("parent", device.getGroup().getId() == 0 ? "#" : "g_" + device.getGroup().getId());
            deviceObj.put("text", device.getName());
            deviceObj.put("children", false);
            deviceObj.put("type", "device");
            // 트리가 장비 옆에 프로토콜 배지를 단다
            deviceObj.put("protocol", device.getProtocol() == null ? null : device.getProtocol().name());
            ret.add(deviceObj);
        }
        return ret;
    }

    @RequestMapping(value = "/group/groups/treeData", method = RequestMethod.GET)
    public List<Map<?, ?>> getGroupsTreeData() {
        List<Group> groups = groupService.selectGroups();

        List<Map<?, ?>> ret = new LinkedList<>();
        for (Group group : groups) {
            Map<Object, Object> groupObj = new HashMap<>();
            groupObj.put("id", "g_" + group.getId());
            // parentId 가 비어 있는 행도 트리 전체를 깨뜨리지 않도록 최상위로 올린다.
            Integer parentId = group.getParentId();
            groupObj.put("parent", parentId == null || parentId == 0 ? "#" : "g_" + parentId);
            groupObj.put("text", group.getName());
            groupObj.put("type", "group");
            ret.add(groupObj);
        }
        return ret;
    }

    @RequestMapping(value = "/device/{id}")
    public Device getDevice(@PathVariable Integer id) {
        return deviceService.selectDevice(id);
    }

    /** 초기 화면 로드용 ServerData 스냅샷. WS 접속과 별개로 REST 로도 조회 가능. */
    @RequestMapping(value = "/devices/{device_id}/serverdata", method = RequestMethod.GET)
    public ResponseEntity<ServerDataSnapshot> getServerDataSnapshot(@PathVariable Integer device_id) {
        return ioServerInstances.getServerInstance(device_id)
                .filter(s -> s.getServerData() != null)
                .map(s -> ResponseEntity.ok(ServerDataSnapshot.of(0L, s.getServerData())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * ServerData 쓰기 (REST). Server Data 화면과 서드파티/스크립트/CI 가 쓴다.
     * 검증과 쓰기는 {@link ServerDataWriteService} 가 하고(Test Plan 과 같은 경로),
     * 여기서는 그 결과를 그대로 응답한다. {@code API_WRITE} 소스로 처리되며,
     * {@code ServerDataBridge} 를 통해 접속중인 WS 구독자에게도 자동 브로드캐스트된다.
     */
    @RequestMapping(value = "/devices/{device_id}/serverdata", method = RequestMethod.POST)
    public ResponseEntity<Map<String, Object>> writeServerData(
            @PathVariable Integer device_id,
            @RequestBody ServerDataWriteRequest req) {
        ServerDataWriteService.Result result = req == null
                ? serverDataWriteService.write(device_id, null, 0, null, null)
                : serverDataWriteService.write(device_id, req.getTarget(), req.getValue(),
                        req.getDataType(), req.getLockSeconds());
        return ResponseEntity.status(result.getStatus()).body(result.getBody());
    }

    @RequestMapping(value = "/group/addGroup", method = RequestMethod.PUT)
    public Group addGroup(@RequestBody Group group) {
        groupService.insertGroup(group);
        return group;
    }

    @RequestMapping(value = "/group/deleteGroup/{id}", method = RequestMethod.DELETE)
    public Integer deleteGroup(@PathVariable Integer id) {
        deleteGroupAndDeviceRecursively(id);

        List<Device> devices = deviceService.selectDevicesByGroupId(id);
        for (Device device : devices) {
            deviceService.deleteDevice(device.getId());
        }
        groupService.deleteGroup(id);

        return id;
    }

    public void deleteGroupAndDeviceRecursively(int groupId) {
        List<Group> groups = groupService.selectGroupsByParentGroupId(groupId);
        for (Group group : groups) {
            List<Device> devices = deviceService.selectDevicesByGroupId(group.getId());
            for (Device device : devices) {
                deviceService.deleteDevice(device.getId());
            }
            groupService.deleteGroup(group.getId());
            deleteGroupAndDeviceRecursively(group.getId());
        }
    }

    @RequestMapping(value = "/group/renameGroup", method = RequestMethod.POST)
    public Group renameGroup(@RequestBody Group group) {
        groupService.renameGroup(group);
        return group;
    }

    @RequestMapping(value = "/device/addDevice", method = RequestMethod.PUT)
    public ResponseEntity<?> renameGroup(@RequestBody Device device) {
        String error = commSettingsError(device);
        if (error != null) return ResponseEntity.badRequest().body(error);
        deviceService.insertDevice(device);
        return ResponseEntity.ok(device);
    }

    /**
     * 단건 저장도 편집기와 같은 규칙으로 통신 설정을 막는다. 편집기는 저장 전에 같은 규칙으로 먼저 막으므로
     * 화면을 거친 요청은 여기서 걸리지 않는다. 문구가 그대로 응답 본문이 된다(편집기는 status + 본문을 alert 한다).
     */
    private static String commSettingsError(Device device) {
        String protocol = device.getProtocol() == null ? null : device.getProtocol().name();
        return DeviceSettingsValidator.problem(protocol, device.getCommunicationSettings());
    }

    /**
     * 클라이언트 검증과 동일한 규칙으로 batch 를 검사한다. 통신 설정은 장비 편집기가 기준이다(DeviceSettingsValidator).
     * 화면(batchAddDevices.html)이 같은 규칙을 JS 로 들고 있어 입력 즉시 피드백을 주고,
     * 이 메서드는 batchInsert 직전에 DB 를 지키는 최종 관문 역할을 한다.
     * 반환값의 각 행에는 error(통과 시 null)가 들어간다.
     */
    @RequestMapping(value = "/device/batchValidate", method = RequestMethod.POST)
    public Map<String, Object> batchValidate(@RequestBody Map<String, Object> body) {
        return validateBatch(asRowList(body.get("devices")), asRowList(body.get("simulations")));
    }

    private Map<String, Object> validateBatch(List<Map<String, Object>> devices,
                                              List<Map<String, Object>> simulations) {
        List<Device> registered = deviceService.selectDevices();
        Set<String> existingNames = registered.stream()
                .map(Device::getName).collect(Collectors.toSet());
        // 바인딩 엔드포인트(host + transport + port)는 머신 전체에서 유일해야 함.
        // transport|port -> 그 조합에 바인딩된 host 집합. 와일드카드 충돌 판정을 위해 host 를 따로 모은다.
        // DB / BACNET 은 device 고유 엔드포인트가 없으므로 제외.
        Map<String, Set<String>> existingEndpoints = new HashMap<>();
        registered.stream()
                .filter(d -> d.getTransport() != null && d.getPort() != null && d.getProtocol() != null)
                .filter(d -> bindsEndpoint(d.getProtocol().name()))
                .forEach(d -> addEndpoint(existingEndpoints, d.getHost(), d.getTransport().name(), d.getPort()));

        Set<String> seenNames = new HashSet<>();
        Map<String, Set<String>> seenEndpoints = new HashMap<>();
        // 템플릿의 simulation 은 '이 파일에서 새로 만들어지는 device' 만 참조할 수 있다.
        // 기존 device 에 simulation 을 추가하는 일은 Simulations 페이지의 batch add 가 담당한다.
        // 덕분에 simulation 이름 중복도 파일 내에서만 검사하면 충분해진다 (신규 device 엔 기존 simulation 이 없으므로).
        Set<String> allowedDeviceNames = new HashSet<>();
        // simulation 검증용. 아직 저장 전이라 파일의 행으로 만든다(프로토콜·통신 설정만 쓴다)
        Map<String, Device> fileDevices = new HashMap<>();

        List<Map<String, Object>> devicesOut = new ArrayList<>();
        for (Map<String, Object> d : devices) {
            Map<String, Object> row = new LinkedHashMap<>(d);
            String error = validateDeviceRow(d, existingNames, seenNames, existingEndpoints, seenEndpoints);
            row.put("error", error);
            devicesOut.add(row);
            if (error == null) {
                String name = asString(d.get("name"));
                if (name != null) {
                    seenNames.add(name);
                    allowedDeviceNames.add(name);
                    try {
                        fileDevices.put(name, buildDeviceFromMap(d, null));
                    } catch (Exception ignored) {
                        // 행 검증을 통과했으면 만들 수 있다. 못 만들면 simulation 의 타입 검증만 건너뛴다
                    }
                }
                String protocol = asString(d.get("protocol"));
                if (bindsEndpoint(protocol) && d.get("port") instanceof Number) {
                    addEndpoint(seenEndpoints, asString(d.get("host")), asString(d.get("transport")),
                            ((Number) d.get("port")).intValue());
                }
            }
        }

        List<Map<String, Object>> simsOut = new ArrayList<>();
        Set<String> seenSimKeys = new HashSet<>();
        for (Map<String, Object> s : simulations) {
            Map<String, Object> row = new LinkedHashMap<>(s);
            String error = validateSimulationRow(s, allowedDeviceNames, existingNames, seenSimKeys);
            Device fileDevice = fileDevices.get(asString(s.get("deviceName")));
            if (error == null && fileDevice != null) error = validateSimulationOf(s, fileDevice, null);
            row.put("error", error);
            simsOut.add(row);
            if (error == null) {
                seenSimKeys.add(simKey(asString(s.get("deviceName")), asString(s.get("name"))));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("devices", devicesOut);
        response.put("simulations", simsOut);
        return response;
    }

    @RequestMapping(value = "/device/batchInsert", method = RequestMethod.POST)
    public Map<String, Object> batchInsert(@RequestBody Map<String, Object> body) {
        Integer groupId = ((Number) body.get("groupId")).intValue();
        Group group = new Group();
        group.setId(groupId);
        List<Map<String, Object>> devices = asRowList(body.get("devices"));
        List<Map<String, Object>> simulations = asRowList(body.get("simulations"));

        // insert 직전 최종 검증 — 화면 검증이 우회되거나, 그 사이 다른 등록이 끼어들었어도 DB 를 지킨다
        Map<String, Object> validated = validateBatch(devices, simulations);
        List<Map<String, Object>> deviceChecks = asRowList(validated.get("devices"));
        List<Map<String, Object>> simChecks = asRowList(validated.get("simulations"));

        // 이 batch 에서 만들어진 device 만 담는다.
        // 기존 device 를 참조하는 simulation 은 검증에서 막히므로 여기에 시드할 필요가 없다.
        Map<String, Integer> nameToId = new HashMap<>();
        // simulation 수천 건마다 device 를 다시 SELECT 하지 않도록 캐시한다
        Map<String, Device> nameToDevice = new HashMap<>();

        List<Map<String, Object>> createdDevices = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < devices.size(); i++) {
            Map<String, Object> dm = devices.get(i);
            String name = asString(dm.get("name"));
            String invalid = i < deviceChecks.size() ? asString(deviceChecks.get(i).get("error")) : null;
            if (invalid != null) {
                failures.add("device " + name + ": " + invalid);
                continue;
            }
            try {
                Device device = buildDeviceFromMap(dm, group);
                deviceService.insertDevice(device);
                nameToId.put(device.getName(), device.getId());
                nameToDevice.put(device.getName(), device);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", device.getId());
                info.put("name", device.getName());
                createdDevices.add(info);
            } catch (Exception e) {
                failures.add("device " + name + ": " + e.getMessage());
            }
        }

        List<Map<String, Object>> createdSims = new ArrayList<>();
        for (int i = 0; i < simulations.size(); i++) {
            Map<String, Object> sm = simulations.get(i);
            String devName = asString(sm.get("deviceName"));
            String simName = asString(sm.get("name"));
            String invalid = i < simChecks.size() ? asString(simChecks.get(i).get("error")) : null;
            if (invalid != null) {
                failures.add("simulation " + simName + ": " + invalid);
                continue;
            }
            Integer devId = nameToId.get(devName);
            if (devId == null) {
                failures.add("simulation " + simName + ": device '" + devName + "' not found");
                continue;
            }
            try {
                Simulation sim = buildSimulationFromMap(sm, devId);
                simulationService.insertSimulation(sim, nameToDevice.get(devName));
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", sim.getId());
                info.put("name", sim.getName());
                info.put("deviceName", devName);
                createdSims.add(info);
            } catch (Exception e) {
                failures.add("simulation " + simName + ": " + e.getMessage());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("createdDevices", createdDevices);
        response.put("createdSimulations", createdSims);
        response.put("failures", failures);
        return response;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Simulation batch add — 이미 등록된 device 하나에 simulation 을 대량 추가한다.
    //  (device batch add 는 파일 내 신규 device 만 다루므로 이쪽이 기존 device 담당)
    // ──────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/simulation/batchValidate", method = RequestMethod.POST)
    public Map<String, Object> simulationBatchValidate(@RequestBody Map<String, Object> body) {
        Integer deviceId = ((Number) body.get("deviceId")).intValue();
        return validateSimulationBatch(deviceId, asRowList(body.get("simulations")));
    }

    /** simulation 의 device 를 DB 에서 읽는다. 없으면 null. */
    private Device deviceOf(Integer deviceId) {
        return deviceId == null ? null : deviceService.selectDevice(deviceId);
    }

    private Map<String, Object> validateSimulationBatch(Integer deviceId,
                                                       List<Map<String, Object>> simulations) {
        // (name, device_id) UNIQUE 이므로 이 device 의 기존 simulation 이름과도 비교해야 한다
        Set<String> existingNames = simulationService.selectSimulationsByDeviceId(deviceId).stream()
                .map(Simulation::getName).collect(Collectors.toSet());
        Set<String> seenNames = new HashSet<>();
        Device batchDevice = deviceOf(deviceId);
        // EtherNet/IP 는 태그 하나에 Simulation 하나다. 기존 것과 파일 안의 것을 함께 본다
        boolean eip = batchDevice != null && batchDevice.getProtocol() == Protocol.ETHERNET_IP;
        Set<String> usedTags = new HashSet<>();
        if (eip) {
            for (Simulation existing : simulationService.selectSimulationsByDeviceId(deviceId)) {
                usedTags.add(EthernetIPTypes.tagOf(existing.getTarget()));
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : simulations) {
            Map<String, Object> row = new LinkedHashMap<>(s);
            String error = validateSimulationBatchRow(s, existingNames, seenNames);
            if (error == null) error = validateSimulationOf(s, batchDevice, deviceId);
            String tag = eip ? EthernetIPTypes.tagOf(asString(s.get("target"))) : null;
            if (error == null && tag != null && usedTags.contains(tag)) {
                error = "Tag \"" + tag + "\" is already used on this device. A tag has one data type, so it can be registered once.";
            }
            row.put("error", error);
            out.add(row);
            if (error == null) {
                seenNames.add(asString(s.get("name")));
                if (tag != null) usedTags.add(tag);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("simulations", out);
        return response;
    }

    /**
     * 일괄 등록 행을 저장될 Simulation 으로 만들어 단건 등록과 같은 검증을 한다 — target 형식,
     * data_type 이 target 에 맞는지, 정책의 값이 그 타입에 맞는지.
     */
    private String validateSimulationOf(Map<String, Object> row, Device device, Integer deviceId) {
        Simulation sim;
        try {
            sim = buildSimulationFromMap(row, deviceId);
        } catch (Exception e) {
            return "Invalid simulation: " + e.getMessage();
        }
        return simulationTargetValidator.validate(device, sim);
    }

    @SuppressWarnings("unchecked")
    private String validateSimulationBatchRow(Map<String, Object> s,
                                              Set<String> existingNames, Set<String> seenNames) {
        String name = asString(s.get("name"));
        if (name == null || name.trim().isEmpty()) return "Missing name.";
        if (existingNames.contains(name)) return "Duplicated simulation name (already on this device).";
        if (seenNames.contains(name)) return "Duplicated simulation name in file.";

        if (blank(s.get("target"))) return "Missing target.";

        Double interval = num(s.get("interval"));
        if (interval == null) return "interval must be a number.";
        if (interval < 1) return "interval must be >= 1.";

        Map<String, Object> p = s;
        Object policiesObj = s.get("policies");
        if (policiesObj instanceof List && !((List<Object>) policiesObj).isEmpty()) {
            Object first = ((List<Object>) policiesObj).get(0);
            if (first instanceof Map) p = (Map<String, Object>) first;
        }

        String policy = asString(p.get("policy"));
        if (policy == null || policy.isEmpty()) return "Missing policy.";
        try { Policy.valueOf(policy); } catch (Exception e) { return "Invalid policy: " + policy; }

        String dt = asString(p.get("data_type"));
        if (dt != null && !dt.isEmpty()) {
            try { DataType.valueOf(dt); } catch (Exception e) { return "Invalid data_type: " + dt; }
        }

        // 정책별 필수값 — GroupedSimulationJob.parseAndMakeValue 가 실제로 읽는 항목
        if ("STATIC".equals(policy)) {
            if (num(p.get("value")) == null) return "STATIC policy requires a numeric 'value'.";
        } else if ("SEQUENCE".equals(policy)) {
            if (parseNumberList(p.get("value")) == null) {
                return "SEQUENCE policy requires 'value' as a non-empty array of numbers.";
            }
        } else {
            // RANDOM: range[0]=low range[1]=high, ACCUMULATE: range[0]=start range[1]=step
            Object rangeObj = p.get("range");
            if (!(rangeObj instanceof List) || ((List<Object>) rangeObj).size() < 2) {
                return policy + " policy requires 'range' with 2 numbers.";
            }
            List<Object> r = (List<Object>) rangeObj;
            Double lo = num(r.get(0)), hi = num(r.get(1));
            if (lo == null || hi == null) return policy + " policy 'range' must be numeric.";
            if ("RANDOM".equals(policy) && lo > hi) return "RANDOM policy 'range' low must be <= high.";
        }
        return null;
    }

    @RequestMapping(value = "/simulation/batchInsert", method = RequestMethod.POST)
    public Map<String, Object> simulationBatchInsert(@RequestBody Map<String, Object> body) {
        Integer deviceId = ((Number) body.get("deviceId")).intValue();
        List<Map<String, Object>> simulations = asRowList(body.get("simulations"));

        // insert 직전 최종 검증 — 화면 검증이 우회되거나 그 사이 다른 등록이 끼어들었어도 DB 를 지킨다
        List<Map<String, Object>> checks =
                asRowList(validateSimulationBatch(deviceId, simulations).get("simulations"));

        Device device = deviceService.getDevice(deviceId);
        List<Map<String, Object>> created = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < simulations.size(); i++) {
            Map<String, Object> sm = simulations.get(i);
            String simName = asString(sm.get("name"));
            String invalid = i < checks.size() ? asString(checks.get(i).get("error")) : null;
            if (invalid != null) {
                failures.add("simulation " + simName + ": " + invalid);
                continue;
            }
            try {
                Simulation sim = buildSimulationFromMap(sm, deviceId);
                simulationService.insertSimulation(sim, device);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", sim.getId());
                info.put("name", sim.getName());
                created.add(info);
            } catch (Exception e) {
                failures.add("simulation " + simName + ": " + e.getMessage());
            }
        }

        // 대량 적재는 건마다 색인을 다시 만들면 O(n^2) 이 되므로 끝나고 한 번만 부른다
        simulationService.reindexDevice(deviceId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("created", created);
        response.put("failures", failures);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRowList(Object o) {
        if (o instanceof List) return (List<Map<String, Object>>) o;
        return Collections.emptyList();
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static String transportPortKey(String transport, Integer port) {
        return transport + "|" + port;
    }

    private static String normHost(String host) {
        return host == null ? "" : host.trim();
    }

    /**
     * 모든 인터페이스에 바인딩되는 host 인지.
     * 와일드카드로 바인딩하면 해당 transport/port 를 인터페이스 전체에서 점유하므로,
     * 같은 transport/port 를 쓰는 다른 어떤 host 와도 충돌한다.
     */
    private static boolean isWildcardHost(String host) {
        String h = normHost(host);
        return h.equals("0.0.0.0") || h.equals("::") || h.equals("[::]") || h.equals("*");
    }

    private static void addEndpoint(Map<String, Set<String>> map, String host, String transport, Integer port) {
        map.computeIfAbsent(transportPortKey(transport, port), k -> new HashSet<>()).add(normHost(host));
    }

    /** 충돌하는 상대 host 를 반환한다. 충돌이 없으면 null. */
    private static String findEndpointConflict(Map<String, Set<String>> map,
                                               String host, String transport, Integer port) {
        Set<String> hosts = map.get(transportPortKey(transport, port));
        if (hosts == null || hosts.isEmpty()) return null;
        String h = normHost(host);
        // 신규가 와일드카드면 이미 등록된 아무 host 와도 충돌
        if (isWildcardHost(h)) return hosts.iterator().next();
        if (hosts.contains(h)) return h;
        // 기존 쪽이 와일드카드면 특정 IP 도 바인딩 불가
        for (String existing : hosts) {
            if (isWildcardHost(existing)) return existing;
        }
        return null;
    }

    private static String endpointConflictMessage(String host, String conflictHost,
                                                  String transport, int port, String what) {
        String h = normHost(host);
        String self = h + ":" + port + "/" + transport;
        if (h.equals(conflictHost)) {
            return "Endpoint " + self + " " + what + ".";
        }
        return "Endpoint " + self + " " + what + " — conflicts with " + conflictHost + ":" + port
                + "/" + transport + " (wildcard host binds all interfaces).";
    }

    // device_editor.html 의 PROTOCOLS[].transports 와 같은 조합 제약
    private static final Map<String, Set<String>> TRANSPORT_PROTOCOLS;
    static {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("TCP", new HashSet<>(Arrays.asList("GENERIC", "MODBUS", "XGT_PLC", "DAEYUN_RCU", "DB", "ETHERNET_IP")));
        m.put("UDP", new HashSet<>(Arrays.asList("GENERIC", "MODBUS", "XGT_PLC", "SNMP", "BACNET", "OPC_AGENT", "DB")));
        TRANSPORT_PROTOCOLS = Collections.unmodifiableMap(m);
    }

    /**
     * host/transport/port 가 이 device 만의 바인딩 엔드포인트인지.
     * 아래 두 프로토콜은 엔드포인트 중복 검사(및 host/port 형식 검사) 대상이 아니다.
     * - DB: 외부 DB 서버로 접속하는 클라이언트라 host/port 가 바인딩과 무관하다.
     *       (device_editor 에서도 host/port 입력란을 숨긴다)
     * - BACNET: 모든 BACnet device 가 0.0.0.0:47808 엔드포인트를 공유한다.
     *       (device_editor 에서 host/port 를 readonly 로 고정한다)
     */
    private static boolean bindsEndpoint(String protocol) {
        return !"DB".equals(protocol) && !"BACNET".equals(protocol);
    }

    private static boolean blank(Object o) {
        return o == null || o.toString().trim().isEmpty();
    }

    private static Double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (Exception e) { return null; }
        }
        return null;
    }

    /** 통신 설정. 규칙·문구는 장비 편집기가 기준이다 — DeviceSettingsValidator */
    private String validateCommSettings(String protocol, Object csRaw) {
        return DeviceSettingsValidator.problem(protocol, csRaw);
    }

    private String validateDeviceRow(Map<String, Object> d,
                                     Set<String> existingNames, Set<String> seenNames,
                                     Map<String, Set<String>> existingEndpoints,
                                     Map<String, Set<String>> seenEndpoints) {
        String name = asString(d.get("name"));
        if (name == null || name.trim().isEmpty()) return "Missing name.";
        if (existingNames.contains(name)) return "Duplicated device name (already exists).";
        if (seenNames.contains(name)) return "Duplicated device name in file.";

        String transport = asString(d.get("transport"));
        if (transport == null) return "Invalid transport.";
        try { Transport.valueOf(transport); } catch (Exception e) { return "Invalid transport."; }

        String protocol = asString(d.get("protocol"));
        if (protocol == null) return "Invalid protocol.";
        try { Protocol.valueOf(protocol); } catch (Exception e) { return "Invalid protocol."; }

        Set<String> allowedForTransport = TRANSPORT_PROTOCOLS.get(transport);
        if (allowedForTransport != null && !allowedForTransport.contains(protocol)) {
            return protocol + " is not supported over " + transport + ".";
        }

        // DB / BACNET 은 host/port 가 device 고유 엔드포인트가 아니므로 형식·중복 검사 모두 생략
        if (bindsEndpoint(protocol)) {
            String host = asString(d.get("host"));
            if (host == null || host.trim().isEmpty()) return "Missing host.";

            Object portObj = d.get("port");
            if (!(portObj instanceof Number)) return "Invalid port.";
            int port = ((Number) portObj).intValue();
            if (port < 0 || port > 65535) return "Invalid port range.";

            String conflict = findEndpointConflict(existingEndpoints, host, transport, port);
            if (conflict != null) {
                return endpointConflictMessage(host, conflict, transport, port, "already used by another device");
            }
            conflict = findEndpointConflict(seenEndpoints, host, transport, port);
            if (conflict != null) {
                return endpointConflictMessage(host, conflict, transport, port, "duplicated in file");
            }
        }

        return validateCommSettings(protocol, d.get("communicationSettings"));
    }

    /** simulation 은 device 안에서만 이름이 유일해야 한다 (DB 제약: UNIQUE(name, device_id)). */
    private static String simKey(String deviceName, String simName) {
        return deviceName + "\u0000" + simName;
    }

    @SuppressWarnings("unchecked")
    private String validateSimulationRow(Map<String, Object> s,
                                         Set<String> allowedDeviceNames,
                                         Set<String> registeredNames,
                                         Set<String> seenSimKeys) {
        String deviceName = asString(s.get("deviceName"));
        if (deviceName == null || deviceName.isEmpty()) return "Missing deviceName.";
        if (!allowedDeviceNames.contains(deviceName)) {
            if (registeredNames.contains(deviceName)) {
                return "Device '" + deviceName + "' already exists — add its simulations from the Simulations page.";
            }
            return "Unknown device: " + deviceName;
        }
        String name = asString(s.get("name"));
        if (name == null || name.isEmpty()) return "Missing simulation name.";
        if (seenSimKeys.contains(simKey(deviceName, name))) {
            return "Duplicated simulation name '" + name + "' for device '" + deviceName + "' in file.";
        }

        // policy/data_type 은 클라이언트가 policies[0] 안으로 접어서 보낸다.
        // 평평한 형태(엑셀 원본 그대로)도 받아들인다.
        Map<String, Object> p = s;
        Object policiesObj = s.get("policies");
        if (policiesObj instanceof List && !((List<Object>) policiesObj).isEmpty()) {
            Object first = ((List<Object>) policiesObj).get(0);
            if (first instanceof Map) p = (Map<String, Object>) first;
        }

        String policy = asString(p.get("policy"));
        if (policy != null && !policy.isEmpty()) {
            try { Policy.valueOf(policy); } catch (Exception e) { return "Invalid policy: " + policy; }
        }
        String dt = asString(p.get("data_type"));
        if (dt != null && !dt.isEmpty()) {
            try { DataType.valueOf(dt); } catch (Exception e) { return "Invalid data_type: " + dt; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Device buildDeviceFromMap(Map<String, Object> m, Group group) {
        Device d = new Device();
        d.setName(asString(m.get("name")));
        d.setHost(asString(m.get("host")));
        d.setTransport(Transport.valueOf(asString(m.get("transport"))));
        d.setProtocol(Protocol.valueOf(asString(m.get("protocol"))));
        d.setPort(((Number) m.get("port")).intValue());
        Object enabledObj = m.get("enabled");
        d.setEnabled(enabledObj instanceof Number ? ((Number) enabledObj).intValue() : 0);
        d.setLog(0);
        Object cs = m.get("communicationSettings");
        if (cs instanceof Map) {
            d.setCommunicationSettings((Map<Object, Object>) cs);
        } else if (cs instanceof String && !((String) cs).isEmpty()) {
            d.setCommunicationSettings(new Gson().fromJson((String) cs, Map.class));
        } else {
            d.setCommunicationSettings(new HashMap<>());
        }
        d.setGroup(group);
        return d;
    }

    @SuppressWarnings("unchecked")
    private Simulation buildSimulationFromMap(Map<String, Object> m, Integer deviceId) {
        Simulation sim = new Simulation();
        sim.setName(asString(m.get("name")));
        sim.setTarget(asString(m.get("target")));
        Object interval = m.get("interval");
        if (interval instanceof Number) sim.setInterval(((Number) interval).intValue());
        Object enabled = m.get("enabled");
        if (enabled instanceof Number) sim.setEnabled(((Number) enabled).intValue());
        // stopOnControl 은 Simulation 의 최상위 필드다 (SimulationPolicy 에는 없다)
        Object stopOnControl = m.get("stopOnControl");
        if (stopOnControl instanceof Number) sim.setStopOnControl(((Number) stopOnControl).intValue());

        List<SimulationPolicy> policies = new ArrayList<>();
        Object policiesObj = m.get("policies");
        if (policiesObj instanceof List) {
            for (Object po : (List<Object>) policiesObj) {
                if (po instanceof Map) policies.add(new Gson().fromJson(new Gson().toJson(po), SimulationPolicy.class));
            }
        }
        sim.setPolicies(policies);

        Device dev = new Device();
        dev.setId(deviceId);
        sim.setDevice(dev);
        return sim;
    }

    /**
     * 디바이스와 딸린 것(시뮬레이션·에러 시뮬레이션·Test Plan 의 해당 Action)을 지운다.
     * Action 이 하나도 안 남아 통째로 사라진 Plan 이 있으면 그 이름을 돌려준다 — 화면이 알린다.
     */
    @RequestMapping(value = "/device/deleteDevice/{id}", method = RequestMethod.DELETE)
    public Map<String, Object> deleteDevice(@PathVariable Integer id) {
        List<String> deletedPlans = deviceService.deleteDevice(id);
        Map<String, Object> res = new HashMap<>();
        res.put("id", id);
        res.put("deletedPlans", deletedPlans);
        return res;
    }

    @RequestMapping(value = "/device/updateDevice", method = RequestMethod.POST)
    public ResponseEntity<?> updateDevice(@RequestBody Device device) {
        String error = commSettingsError(device);
        if (error != null) return ResponseEntity.badRequest().body(error);
        deviceService.updateDevice(device);
        return ResponseEntity.ok(device);
    }

    @RequestMapping(value = "/device/getEnabled/{id}", method = RequestMethod.GET)
    public Boolean selectDeviceEnabled(@PathVariable Integer id) {
        return deviceService.selectDeviceEnabled(id);
    }

    @RequestMapping(value = "/device/setEnabled/{id}/{enabled}", method = RequestMethod.POST)
    public Integer updateDeviceEnabled(@PathVariable Integer id, @PathVariable Integer enabled) {
        deviceService.updateDeviceEnabled(enabled, id);
        return enabled;
    }

    @RequestMapping(value = "/device/devices", method = RequestMethod.GET)
    public List<Device> getDevices() {
        return deviceService.selectDevices();
    }

    /**
     * 한 장비의 시뮬레이션 목록. device 는 빼고 보낸다 — 줄마다 같은 장비를 통째로 반복하느라
     * 응답의 절반이 중복이었다 (SimulationListItem 참고).
     */
    @RequestMapping(value = "/simulation/simulations/{deviceId}")
    public List<SimulationListItem> getSimulationsByDeviceId(@PathVariable Integer deviceId) {
        return simulationService.selectSimulationsByDeviceId(deviceId).stream()
                .map(SimulationListItem::new)
                .collect(java.util.stream.Collectors.toList());
    }

    @RequestMapping(value = "/simulation/addSimulation", method = RequestMethod.PUT)
    public ResponseEntity<?> addSimulation(@RequestBody Simulation simulation) {
        String error = validateSimulationTarget(simulation);
        if (error != null) return ResponseEntity.badRequest().body(error);
        simulationService.insertSimulation(simulation);
        return ResponseEntity.ok(simulation);
    }

    @RequestMapping(value = "/simulation/updateSimulation", method = RequestMethod.POST)
    public ResponseEntity<?> updateSimulation(@RequestBody Simulation simulation) {
        String error = validateSimulationTarget(simulation);
        if (error != null) return ResponseEntity.badRequest().body(error);
        simulationService.updateSimulation(simulation);
        return ResponseEntity.ok(simulation);
    }

    /** 편집기가 저장 전에 부른다. 저장 경로(/saveSimulation)와 같은 검증이다. 문제없으면 error 가 null. */
    @RequestMapping(value = "/simulation/validate", method = RequestMethod.POST)
    public Map<String, Object> validateSimulation(@RequestBody Simulation simulation) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", validateSimulationTarget(simulation));
        return response;
    }

    /** 단건 등록/수정용 target 검증. device 는 보통 id 만 실려오므로 protocol 은 DB 에서 읽는다. */
    private String validateSimulationTarget(Simulation simulation) {
        if (simulation == null || simulation.getDevice() == null) return null;
        Device device = deviceOf(simulation.getDevice().getId());
        String error = simulationTargetValidator.validate(device, simulation);
        return error != null ? error : simulationService.duplicateTagError(simulation, device);
    }

    @RequestMapping(value = "/simulation/deleteSimulation/{id}", method = RequestMethod.DELETE)
    public Integer deleteSimulation(@PathVariable Integer id) {
        simulationService.deleteSimulation(id);
        return id;
    }

    @RequestMapping(value = "/simulation/getEnabled/{id}", method = RequestMethod.GET)
    public Boolean selectSimulationEnabled(@PathVariable Integer id) {
        return simulationService.selectSimulationEnabled(id);
    }

    @RequestMapping(value = "/simulation/setEnabled/{id}/{enabled}", method = RequestMethod.POST)
    public Integer updateSimulationEnabled(@PathVariable Integer id, @PathVariable Integer enabled) {
        Simulation simulation = simulationService.selectSimulation(id);
        simulation.setEnabled(enabled);
        simulationService.updateSimulation(simulation);
        return enabled;
    }

    @RequestMapping(value = "/socketTest", method = RequestMethod.GET)
    public Boolean socketTest(@RequestParam("host") String host,
                              @RequestParam("transport") String transport,
                              @RequestParam("port") Integer port) throws IOException {

        try {
            if (SystemUtils.IS_OS_MAC) {
                if (host.equals("0.0.0.0") || transport.equalsIgnoreCase("udp")) host = "\\*";
                String regex = transport.toLowerCase() + ".+" + host + "\\." + port + ".+";
                Process netstat = Runtime.getRuntime().exec(new String[]{
                        "/bin/sh",
                        "-c",
                        transport.equals("TCP") ? "netstat -anv | grep LISTEN" : "netstat -anv | grep udp"});
                BufferedReader inStream = new BufferedReader(new InputStreamReader(netstat.getInputStream()));
                List<String> lines = inStream.lines().collect(Collectors.toList());
                for (String line : lines) {
                    if (Pattern.matches(regex, line)) {
                        return true;
                    }
                }
                return false;
            } else if (SystemUtils.IS_OS_WINDOWS) {
                // TODO IF RUN ON WINDOWS
                String regex = ".+" + transport + ".+" + host + ":" + port + (transport.equals("TCP") ? ".+LISTENING" : "") + ".+";
                logger.info(regex);
                String[] cmd = {"cmd.exe", "/c", "netstat -nao | findstr " + port};
                Process netstat = Runtime.getRuntime().exec(cmd);
                BufferedReader inStream = new BufferedReader(new InputStreamReader(netstat.getInputStream()));
                List<String> lines = inStream.lines().collect(Collectors.toList());
                for (String line : lines) {
                    if (Pattern.matches(regex, line)) {
                        return true;
                    }
                }
                return false;
            } else return false;
        } catch (IOException e) {
            logger.error("Runtime exec error.", e);
            return false;
        }
    }

    @RequestMapping(value = "/sessions/{id}", method = RequestMethod.GET)
    public List<Session> sessions(@PathVariable("id") Integer id) {
        IOServer server = ioServerInstances.getServers().get(id);
        List<Session> sessions = new ArrayList<>();
        if (server != null) {
            for (InetSocketAddress remote : server.getSessions().keySet()) {
                Session session = new Session();
                session.setRemoteHost(remote.getHostName());
                session.setRemotePort(remote.getPort());
                session.setUptime(System.currentTimeMillis() - server.getSessions().get(remote));
                MkHandler handler_target = null;
                for (ChannelInboundHandlerAdapter handler : server.getHandlers()) {
                    MkHandler mkHandler = (MkHandler) handler;
                    if (mkHandler.getContext().channel().remoteAddress().equals(remote)) {
                        handler_target = mkHandler;
                    }
                }
                MkDecoderInterface decoder_target = null;
                for (ChannelInboundHandlerAdapter decoder : server.getDecoders()) {
                    MkDecoderInterface mkDecoder = (MkDecoderInterface) decoder;
                    if (mkDecoder.getContext().channel().remoteAddress().equals(remote)) {
                        decoder_target = mkDecoder;
                    }
                }
                if (handler_target != null && decoder_target != null) {
                    session.setTx(handler_target.getTxCounter());
                    session.setRx(decoder_target.getRxCounter());
                    sessions.add(session);
                }
            }
        }
        return sessions;
    }

    @RequestMapping(value = "/disconnect/{server}/{remoteAddr}", method = RequestMethod.DELETE)
    public Boolean disconnect(@PathVariable Integer server, @PathVariable String remoteAddr) {
        AtomicBoolean ret = new AtomicBoolean(false);
        ioServerInstances.getServerInstance(server).ifPresent(s->{
            for (ChannelInboundHandlerAdapter handler : s.getHandlers()) {
                MkHandler mkHandler = (MkHandler) handler;
                InetSocketAddress addr = (InetSocketAddress) mkHandler.getContext().channel().remoteAddress();

                if (remoteAddr.equals(addr.getHostName() + ":" + addr.getPort())) {
                    mkHandler.getContext().close();
                    ret.set(true);
                }
            }
        });
        return ret.get();
    }

    @RequestMapping(value = "/error/errors/{device_id}", method = RequestMethod.GET)
    public List<Error> gerError(@PathVariable Integer device_id) {
        return errorService.selectErrorsByDeviceId(device_id);
    }

    /**
     * 디바이스 서버 기동 이후 에러 상태별 누적 점유 시간 (ms).
     * 서버가 떠 있지 않으면 비어있는 통계를 반환한다.
     */
    @RequestMapping(value = "/error/stats/{device_id}", method = RequestMethod.GET)
    public ErrorStateStats errorStateStats(@PathVariable Integer device_id) {
        IOServer server = ioServerInstances.getServers().get(device_id);
        if (server == null) return new ErrorStateStats();
        return server.getErrorStateStats();
    }

    @RequestMapping(value = "/error/addError", method = RequestMethod.PUT)
    public String addError(@RequestBody Error error) {
        if (error.getSimulation() != null && error.getSimulation().getId() != null) {
            error.setSimulation(simulationService.selectSimulation(error.getSimulation().getId()));
        }
        errorService.insertError(error);
        ioServerInstances.getServerInstance(error.getDevice().getId()).ifPresent(s->s.setErrors(errorService.selectEnabledErrorsByDeviceId(error.getDevice().getId())));
        resetErrorStateStats(error.getDevice().getId());
        errorService.startErrorSchedule(error);
        return "success";
    }

    /**
     * 에러 구성이 바뀌었을 때(추가·수정·삭제·켜고 끄기) 그 장비의 점유 통계를 지금부터 다시 센다.
     * 바뀌기 전의 시간이 섞이면 Actual 비율이 새 구성을 보여 주지 못한다 — 오래 떠 있던 서버면 NORMAL 이 부푼다.
     */
    private void resetErrorStateStats(Integer deviceId) {
        ioServerInstances.getServerInstance(deviceId).ifPresent(IOServer::resetErrorStateStats);
    }

    @RequestMapping(value = "/error/updateError", method = RequestMethod.POST)
    public String updateError(@RequestBody Error error) {
        if (error.getSimulation() != null && error.getSimulation().getId() != null) {
            error.setSimulation(simulationService.selectSimulation(error.getSimulation().getId()));
        }
        errorService.stopErrorSchedule(error.getId());
        errorService.updateError(error);
        ioServerInstances.getServerInstance(error.getDevice().getId()).ifPresent(s->s.setErrors(errorService.selectEnabledErrorsByDeviceId(error.getDevice().getId())));
        resetErrorStateStats(error.getDevice().getId());
        errorService.startErrorSchedule(error);
        return "success";
    }

    @RequestMapping(value = "/error/deleteError/{device_id}", method = RequestMethod.DELETE)
    public String deleteError(@PathVariable Integer device_id) {
        // 경로 변수 이름은 device_id 지만 실제로는 에러 id 다. 장비는 지우기 전에 읽어 둔다
        Integer deviceId = deviceIdOfError(device_id);
        errorService.stopErrorSchedule(device_id);
        errorService.deleteError(device_id);
        if (deviceId != null) {
            // 추가·수정·켜고 끄기처럼 장비 서버가 들고 있는 에러 목록도 새로 채운다
            ioServerInstances.getServerInstance(deviceId).ifPresent(s -> s.setErrors(errorService.selectEnabledErrorsByDeviceId(deviceId)));
            resetErrorStateStats(deviceId);
        }
        return "success";
    }

    /** 에러가 걸린 장비 id. 없거나 장비가 비어 있으면 null */
    private Integer deviceIdOfError(Integer errorId) {
        try {
            Error e = errorService.selectErrorById(errorId);
            return e.getDevice() == null ? null : e.getDevice().getId();
        } catch (EntityNotFoundException ex) {
            return null;
        }
    }

    @RequestMapping(value = "/error/getEnabled/{error_id}", method = RequestMethod.DELETE)
    public Boolean getEnabled(@PathVariable Integer error_id) {
        return errorService.getErrorEnabled(error_id);
    }

    @RequestMapping(value = "/simulation/copySimulation/{device_id}", method = RequestMethod.PUT)
    public String copySimulations(@PathVariable Integer device_id, @RequestBody List<Integer> copyTo) {
        for (Integer index : copyTo) {
            Device device = deviceService.selectDevice(index);
            simulationService.copySimulations(index, device_id);
            List<Simulation> simulations = simulationService.selectSimulationsByDeviceId(index);
            for (Simulation simulation : simulations) {
                simulationService.startSimulation(device, simulation);
            }
        }
        return "success";
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Error batch add — 이미 등록된 device 하나에 error 를 대량 추가한다.
    //  batchAddErrors.html 이 같은 규칙을 JS 로 들고 있어 입력 즉시 피드백을 주고,
    //  이 메서드는 batchInsert 직전에 DB 를 지키는 최종 관문 역할을 한다.
    // ──────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/error/batchValidate", method = RequestMethod.POST)
    public Map<String, Object> errorBatchValidate(@RequestBody Map<String, Object> body) {
        Integer deviceId = ((Number) body.get("deviceId")).intValue();
        return validateErrorBatch(deviceId, asRowList(body.get("errors")));
    }

    private Map<String, Object> validateErrorBatch(Integer deviceId,
                                                   List<Map<String, Object>> rows) {
        Map<String, Simulation> simsByName = new HashMap<>();
        for (Simulation s : simulationService.selectSimulationsByDeviceId(deviceId)) {
            simsByName.put(s.getName(), s);
        }
        boolean isDbProtocol = false;
        Device dev = deviceOf(deviceId);
        if (dev != null && dev.getProtocol() != null) {
            isDbProtocol = "DB".equals(dev.getProtocol().name());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            String err = validateErrorBatchRow(r, simsByName, isDbProtocol);
            row.put("error", err);
            out.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("errors", out);
        return response;
    }

    /** 에러마다 독립 스케줄로 자기 interval 마다 Bernoulli 시행 — frequency 합계 캡은 없다. */
    private String validateErrorBatchRow(Map<String, Object> r,
                                         Map<String, Simulation> simsByName,
                                         boolean isDbProtocol) {
        String modeRaw = asString(r.get("mode"));
        if (modeRaw == null || modeRaw.trim().isEmpty()) return "Missing mode.";
        com.onionsoftware.mk119.commtestsuite.enums.ErrorMode mode;
        try {
            mode = com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.valueOf(modeRaw.trim().toUpperCase());
        } catch (Exception e) {
            return "Invalid mode: " + modeRaw;
        }

        Double freq = num(r.get("frequency"));
        if (freq == null) return "frequency must be a number.";
        if (freq < 0 || freq > 100) return "frequency must be 0~100.";

        Double interval = num(r.get("interval"));
        if (interval == null) return "interval must be a number.";
        if (interval < 1) return "interval must be >= 1.";

        if (mode != com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.SIMULATION) {
            Double d = num(r.get("errorDuration"));
            if (d == null) return "errorDuration must be a number.";
            if (d < 1) return "errorDuration must be >= 1.";
        }

        if (mode == com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.TIMEOUT) {
            Double t = num(r.get("timeout"));
            if (t == null) return "TIMEOUT requires numeric timeout.";
            if (t < 0 || t > 10) return "timeout must be 0~10.";
        } else if (mode == com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.GARBAGE) {
            Double g = num(r.get("garbageLength"));
            if (g == null) return "GARBAGE requires numeric garbageLength.";
            if (g < 1 || g > 1024) return "garbageLength must be 1~1024.";
        } else if (mode == com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.SIMULATION) {
            String simName = asString(r.get("simulationName"));
            if (simName == null || simName.trim().isEmpty()) return "SIMULATION requires simulationName.";
            if (!simsByName.containsKey(simName)) {
                return "SIMULATION target '" + simName + "' not found on this device.";
            }
            if (num(r.get("errorValue")) == null) return "SIMULATION requires numeric errorValue.";
            if (!isDbProtocol) {
                Double st = num(r.get("simulation_stop"));
                if (st == null) return "SIMULATION requires numeric simulation_stop.";
                if (st < 0 || st > 60) return "simulation_stop must be 0~60.";
            }
        }

        return null;
    }

    @RequestMapping(value = "/error/batchInsert", method = RequestMethod.POST)
    public Map<String, Object> errorBatchInsert(@RequestBody Map<String, Object> body) {
        Integer deviceId = ((Number) body.get("deviceId")).intValue();
        List<Map<String, Object>> rows = asRowList(body.get("errors"));

        // 화면 검증이 우회되거나 그 사이 다른 등록이 끼어들었어도 DB 를 지킨다
        List<Map<String, Object>> checks =
                asRowList(validateErrorBatch(deviceId, rows).get("errors"));

        Device device = deviceService.selectDevice(deviceId);
        Map<String, Simulation> simsByName = new HashMap<>();
        for (Simulation s : simulationService.selectSimulationsByDeviceId(deviceId)) {
            simsByName.put(s.getName(), s);
        }

        List<Map<String, Object>> created = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            String label = "error #" + (i + 1) + " (" + asString(r.get("mode")) + ")";
            String invalid = i < checks.size() ? asString(checks.get(i).get("error")) : null;
            if (invalid != null) {
                failures.add(label + ": " + invalid);
                continue;
            }
            try {
                Error e = buildErrorFromMap(r, device, simsByName);
                errorService.insertError(e);
                ioServerInstances.getServerInstance(deviceId).ifPresent(
                        s -> s.setErrors(errorService.selectEnabledErrorsByDeviceId(deviceId)));
                errorService.startErrorSchedule(e);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", e.getId());
                info.put("mode", e.getMode());
                created.add(info);
            } catch (Exception ex) {
                failures.add(label + ": " + ex.getMessage());
            }
        }

        // 에러 구성이 바뀌었으니 점유 통계를 지금부터 다시 센다 (한 건이라도 들어갔을 때)
        if (!created.isEmpty()) {
            resetErrorStateStats(deviceId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("created", created);
        response.put("failures", failures);
        return response;
    }

    private Error buildErrorFromMap(Map<String, Object> r, Device device,
                                    Map<String, Simulation> simsByName) {
        Error e = new Error();
        e.setDevice(device);
        com.onionsoftware.mk119.commtestsuite.enums.ErrorMode mode =
                com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.valueOf(
                        asString(r.get("mode")).trim().toUpperCase());
        e.setMode(mode);
        e.setFrequency(num(r.get("frequency")).intValue());
        e.setInterval(num(r.get("interval")).intValue());
        Double enabled = num(r.get("enabled"));
        e.setEnabled(enabled == null ? Error.ERROR_ENABLED : enabled.intValue());

        if (mode == com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.SIMULATION) {
            String simName = asString(r.get("simulationName"));
            e.setSimulation(simsByName.get(simName));
            e.setErrorValue(num(r.get("errorValue")));
            Double stopVal = num(r.get("simulation_stop"));
            e.setSimulation_stop(stopVal == null ? 0 : stopVal.intValue());
            // errorDuration 은 SIMULATION 에선 쓰이지 않지만 int 필드라 0 으로 둔다
        } else {
            e.setErrorDuration(num(r.get("errorDuration")).intValue());
            if (mode == com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.TIMEOUT) {
                e.setTimeout(num(r.get("timeout")).intValue());
            } else if (mode == com.onionsoftware.mk119.commtestsuite.enums.ErrorMode.GARBAGE) {
                e.setGarbageLength(num(r.get("garbageLength")).intValue());
            }
        }
        return e;
    }

    @RequestMapping(value = "/error/setEnabled/{error_id}/{enabled}")
    public String updateErrorEnabled(@PathVariable Integer error_id, @PathVariable Integer enabled) {
        Error error = errorService.selectErrorById(error_id);
        error.setEnabled(enabled);
        errorService.updateError(error);
        ioServerInstances.getServerInstance(error.getDevice().getId()).ifPresent(s->s.setErrors(errorService.selectEnabledErrorsByDeviceId(error.getDevice().getId())));
        resetErrorStateStats(error.getDevice().getId());
        if (enabled == Error.ERROR_ENABLED) {
            errorService.startErrorSchedule(error);
        } else {
            errorService.stopErrorSchedule(error.getId());
        }
        return "success";
    }

    @RequestMapping(value = "/simulation/updateStaticSimulationValue/{simulation_id}/{value}", method = RequestMethod.POST)
    public String updateSimulation(@PathVariable Integer simulation_id, @PathVariable Double value) {
        Simulation simulation = simulationService.selectSimulation(simulation_id);
        if(simulation != null) {
            List<SimulationPolicy> policies = simulation.getPolicies();
            policies.get(0).setValue(value);
            simulationService.updateSimulation(simulation);
            return "success";
        } else {
            return "simulation id not found: " + simulation_id;
        }
    }

    /**
     * RANDOM / ACCUMULATE 정책의 range 두 값을 즉시 수정한다 (테이블 더블클릭 편집용).
     * 가중 분포(BINS)가 걸린 RANDOM 은 차트를 보며 조정해야 하므로 여기서 막고 editor 로 보낸다.
     * 음수·소수를 다루므로 path 대신 body 로 받는다.
     */
    @RequestMapping(value = "/simulation/updateSimulationRange/{simulation_id}", method = RequestMethod.POST)
    public ResponseEntity<String> updateSimulationRange(@PathVariable Integer simulation_id,
                                                        @RequestBody Map<String, Object> body) {
        Simulation simulation = simulationService.selectSimulation(simulation_id);
        if (simulation == null) {
            return new ResponseEntity<>("simulation id not found: " + simulation_id,
                    new HttpHeaders(), HttpStatus.NOT_FOUND);
        }
        List<SimulationPolicy> policies = simulation.getPolicies();
        if (policies == null || policies.isEmpty()) {
            return new ResponseEntity<>("simulation has no policy", new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }
        SimulationPolicy p = policies.get(0);
        Policy policy = p.getPolicy();
        if (policy != Policy.RANDOM && policy != Policy.ACCUMULATE) {
            return new ResponseEntity<>("range edit applies to RANDOM or ACCUMULATE only",
                    new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }
        if (hasWeightedDistribution(p)) {
            return new ResponseEntity<>("Weighted distribution must be edited in the simulation editor.",
                    new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }

        Double low = num(body.get("low"));
        Double high = num(body.get("high"));
        if (low == null || high == null) {
            return new ResponseEntity<>("low/high must be numeric", new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }
        if (policy == Policy.RANDOM && low > high) {
            return new ResponseEntity<>("low must be <= high", new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }

        // ACCUMULATE 는 초기값(range[0])이 바뀌었을 때만 누적을 리셋한다.
        // 증가분(range[1])만 바꾼 경우는 현재 누적값에서 새 증가분으로 이어가는 게 자연스럽다.
        boolean resetAccumulation = false;
        if (policy == Policy.ACCUMULATE) {
            List<Double> old = p.getRange();
            Double oldLow = (old != null && !old.isEmpty()) ? old.get(0) : null;
            resetAccumulation = (oldLow == null) || (Math.abs(oldLow - low) > 0d);
        }

        p.setRange(Arrays.asList(low, high));
        simulationService.updateSimulation(simulation);
        if (resetAccumulation) {
            simulationService.resetAccumulatedValue(simulation_id);
        }
        return new ResponseEntity<>(resetAccumulation ? "success (accumulation reset)" : "success",
                new HttpHeaders(), HttpStatus.OK);
    }

    /** 진행 상태(ACCUMULATE 누적값 / SEQUENCE 인덱스)를 버려 처음부터 다시 시작하게 한다. */
    @RequestMapping(value = "/simulation/resetAccumulation/{simulation_id}", method = RequestMethod.POST)
    public ResponseEntity<String> resetAccumulation(@PathVariable Integer simulation_id) {
        Simulation simulation = simulationService.selectSimulation(simulation_id);
        if (simulation == null) {
            return new ResponseEntity<>("simulation id not found: " + simulation_id,
                    new HttpHeaders(), HttpStatus.NOT_FOUND);
        }
        List<SimulationPolicy> policies = simulation.getPolicies();
        Policy policy = (policies == null || policies.isEmpty()) ? null : policies.get(0).getPolicy();
        if (policy != Policy.ACCUMULATE && policy != Policy.SEQUENCE) {
            return new ResponseEntity<>("reset applies to ACCUMULATE or SEQUENCE only",
                    new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }
        simulationService.resetAccumulatedValue(simulation_id);
        return new ResponseEntity<>("success", new HttpHeaders(), HttpStatus.OK);
    }

    /**
     * SEQUENCE 의 숫자 배열을 즉시 수정한다. 배열이 바뀌면 인덱스를 리셋해 첫 값부터 다시 돈다.
     * body: {"values":[1,2,3]}
     */
    @RequestMapping(value = "/simulation/updateSequenceValues/{simulation_id}", method = RequestMethod.POST)
    public ResponseEntity<String> updateSequenceValues(@PathVariable Integer simulation_id,
                                                       @RequestBody Map<String, Object> body) {
        Simulation simulation = simulationService.selectSimulation(simulation_id);
        if (simulation == null) {
            return new ResponseEntity<>("simulation id not found: " + simulation_id,
                    new HttpHeaders(), HttpStatus.NOT_FOUND);
        }
        List<SimulationPolicy> policies = simulation.getPolicies();
        if (policies == null || policies.isEmpty()) {
            return new ResponseEntity<>("simulation has no policy", new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }
        SimulationPolicy p = policies.get(0);
        if (p.getPolicy() != Policy.SEQUENCE) {
            return new ResponseEntity<>("values edit applies to SEQUENCE only",
                    new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }

        List<Double> parsed = parseNumberList(body.get("values"));
        if (parsed == null) {
            return new ResponseEntity<>("values must be a non-empty array of numbers",
                    new HttpHeaders(), HttpStatus.BAD_REQUEST);
        }

        p.setValue(parsed);
        simulationService.updateSimulation(simulation);
        // 배열이 바뀌면 길이/의미가 달라지므로 항상 첫 값부터 다시 시작한다
        simulationService.resetAccumulatedValue(simulation_id);
        return new ResponseEntity<>("success", new HttpHeaders(), HttpStatus.OK);
    }

    /** 숫자 배열로 해석한다. 배열이 아니거나 비어 있거나 숫자가 아닌 원소가 있으면 null. */
    @SuppressWarnings("unchecked")
    private static List<Double> parseNumberList(Object raw) {
        if (!(raw instanceof List)) return null;
        List<Object> list = (List<Object>) raw;
        if (list.isEmpty()) return null;
        List<Double> out = new ArrayList<>(list.size());
        for (Object o : list) {
            Double n = num(o);
            if (n == null) return null;
            out.add(n);
        }
        return out;
    }

    /** pickBinRange 가 가중 분포로 인정하는 조건과 동일하게 판정한다. */
    private static boolean hasWeightedDistribution(SimulationPolicy p) {
        Distribution d = p.getDistribution();
        if (d == null || d.getType() == null || !"BINS".equalsIgnoreCase(d.getType())) return false;
        Integer binCount = d.getBinCount();
        List<Double> weights = d.getWeights();
        return binCount != null && binCount > 0 && weights != null && !weights.isEmpty();
    }

    @ExceptionHandler({SQLException.class})
    public ResponseEntity<String> handleDBError(Exception e) {
        return new ResponseEntity<>("DB Error. " + e.getMessage(), new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
