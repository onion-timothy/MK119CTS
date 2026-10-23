package com.onionsoftware.mk119.commtestsuite.web.serverdata;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataValueRules;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ServerData 쓰기 (API_WRITE).
 * <p>
 * REST {@code POST /api/devices/{id}/serverdata} 와 Test Plan 이 같은 경로를 탄다.
 * 따로 두면 검증이 어긋나 "API 로는 되는데 Test Plan 으로는 안 된다" 가 생긴다.
 * 결과는 REST 응답 그대로의 status·body 로 돌려준다. REST 는 그대로 내보내고,
 * Test Plan 은 {@link Result#isOk()} 와 {@link Result#getError()} 만 본다.
 */
@Service
@RequiredArgsConstructor
public class ServerDataWriteService {
    private final IOServerInstances ioServerInstances;
    private final DeviceService deviceService;
    private final ServerDataSessionRegistry serverDataSessionRegistry;

    public static final class Result {
        private final HttpStatus status;
        private final Map<String, Object> body;

        private Result(HttpStatus status, Map<String, Object> body) {
            this.status = status;
            this.body = Collections.unmodifiableMap(body);
        }

        public HttpStatus getStatus() {
            return status;
        }

        public Map<String, Object> getBody() {
            return body;
        }

        public boolean isOk() {
            return status == HttpStatus.OK;
        }

        /** 실패 사유. 성공이면 null. */
        public String getError() {
            Object e = body.get("error");
            return e == null ? null : e.toString();
        }

        /** 결과를 직접 만든다. 실행기 테스트처럼 쓰기 없이 결과가 필요한 쪽이 쓴다. */
        public static Result of(HttpStatus status, Map<String, Object> body) {
            return new Result(status, new LinkedHashMap<>(body));
        }

        private static Result fail(HttpStatus status, String error) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("error", error);
            return new Result(status, body);
        }
    }

    /**
     * @param dataType    DataType enum 이름. null 이나 빈 문자열이면 지정하지 않은 것
     * @param lockSeconds {@link ServerData#lock(String, Integer)} 의 규칙을 따른다.
     *                    음수(만료 없는 lock)는 그 장비에 살아있는 편집 세션이 있을 때만 받는다
     */
    public Result write(Integer deviceId, String target, double value, String dataType, Integer lockSeconds) {
        if (target == null || target.isEmpty()) {
            return Result.fail(HttpStatus.BAD_REQUEST, "target is required");
        }
        Optional<IOServer> serverOpt = ioServerInstances.getServerInstance(deviceId);
        if (!serverOpt.isPresent() || serverOpt.get().getServerData() == null) {
            return Result.fail(HttpStatus.NOT_FOUND, "device_not_available");
        }
        // 만료 없는 lock 은 그 디바이스에 살아있는 편집 세션이 있을 때만 접수한다.
        // 세션이 있으면 종료/ack timeout 시 clearDeviceLocks 가 반드시 회수하므로 lock 이 영구히 박히지 않는다.
        // 세션 없는 호출(외부 스크립트/CI, Test Plan)은 만료 있는 lockSeconds > 0 을 써야 한다.
        if (ServerData.isPermanentLockRequest(lockSeconds)
                && !serverDataSessionRegistry.hasActiveSession(deviceId)) {
            return Result.fail(HttpStatus.CONFLICT,
                    "permanent lock requires an active ServerData editing session on this device");
        }
        ServerData sd = serverOpt.get().getServerData();

        // SNMP / GENERIC 은 맵 기반 저장이라 어떤 문자열이든 새 엔트리를 만들어버린다. 그래서
        // 등록된 simulation 의 target 집합에 없는 쓰기는 거절한다. 판정 기준은 SNMP SET 경로
        // (SnmpHandler.processSetRequest 는 미등록 OID 에 noSuchName 을 돌려준다)와 같게 맞췄다.
        Device writeDevice = deviceId == null ? null : deviceService.selectDevice(deviceId);
        if (writeDevice != null
                && (writeDevice.getProtocol() == Protocol.SNMP
                    || writeDevice.getProtocol() == Protocol.GENERIC)) {
            // ServerData 의 색인을 쓴다. 핸들러가 보는 것과 같은 것을 봐야
            // "화면에선 되는데 API 로는 안 된다" 같은 어긋남이 생기지 않는다.
            boolean known = sd.simulationFor(target) != null;
            if (!known) {
                return Result.fail(HttpStatus.NOT_IMPLEMENTED,
                        "No " + (writeDevice.getProtocol() == Protocol.SNMP ? "OID" : "field")
                        + " for target \"" + target
                        + "\". A simulation with that exact target must exist on this device.");
            }
        }

        // dataType 파싱은 쓰기와 분리한다. 한 try 로 묶으면 target 파싱 실패(NumberFormatException)도
        // IllegalArgumentException 이라서 "invalid dataType" 으로 잘못 보고됐다.
        DataType dt;
        try {
            dt = (dataType == null || dataType.isEmpty()) ? null : DataType.valueOf(dataType);
        } catch (IllegalArgumentException e) {
            return Result.fail(HttpStatus.BAD_REQUEST, "invalid dataType: " + dataType);
        }
        // 타입에 맞지 않는 값은 거절한다. 쓰기 경로는 그런 값을 조용히 잘라 쓰거나(소수, 범위 초과)
        // 아무것도 하지 않은 채 성공으로 끝냈다(dataType 없는 Modbus 레지스터).
        Protocol protocol = writeDevice == null ? null : writeDevice.getProtocol();
        String typeError = ServerDataValueRules.dataTypeError(protocol, target, dt);
        if (typeError == null) typeError = ServerDataValueRules.valueError(protocol, target, dt, value);
        if (typeError != null) {
            return Result.fail(HttpStatus.BAD_REQUEST, typeError);
        }
        try {
            sd.setServerData(target, value, dt, ServerDataChangeSource.API_WRITE, lockSeconds);
            List<String> affected = sd.computeAffectedTargets(target, dt);
            Map<String, Object> targetValues = new LinkedHashMap<>();
            for (String t : affected) {
                Number v = sd.readValueAt(t);
                if (v != null) targetValues.put(t, v);
            }
            boolean locked = ServerData.isLockRequest(lockSeconds);
            boolean permanent = ServerData.isPermanentLockRequest(lockSeconds);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("target", target);
            resp.put("value", value);
            resp.put("dataType", dataType);
            resp.put("source", ServerDataChangeSource.API_WRITE.name());
            resp.put("affectedTargets", affected);
            resp.put("targetValues", targetValues);
            // lock 을 걸지 않았다면 다음 interval 에 simulation 이 값을 되돌린다는 점을 응답으로 알린다
            resp.put("locked", locked);
            resp.put("lockSeconds", locked ? lockSeconds : null);
            // 영구 lock 은 편집 세션 종료 또는 lockSeconds=0 재전송으로만 풀린다
            resp.put("lockPermanent", permanent);
            resp.put("ts", System.currentTimeMillis());
            return new Result(HttpStatus.OK, resp);
        } catch (UnsupportedOperationException e) {
            // 이 프로토콜·이 target 은 target 기반 쓰기를 지원하지 않는다 (BACNET, 쓰기 불가 Modbus FC 등).
            // 예전에는 조용히 no-op 하고 200 + lock 이 나갔다.
            return Result.fail(HttpStatus.NOT_IMPLEMENTED, e.getMessage());
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            // target 형식 오류나 주소 범위 초과. 호출자가 고칠 수 있는 입력 문제이므로 400 으로 알린다.
            return Result.fail(HttpStatus.BAD_REQUEST, "invalid target: " + target
                    + " (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")");
        } catch (Exception e) {
            LoggerFactory.getLogger("serverdata").error("API_WRITE failed: device={}, target={}, value={}, dataType={}", deviceId, target, value, dataType, e);
            return Result.fail(HttpStatus.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
