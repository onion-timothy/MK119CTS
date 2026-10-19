package com.onionsoftware.mk119.commtestsuite;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.GroupService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ServiceInitializer {
    private static final Logger logger = LoggerFactory.getLogger("agent");

    private final DeviceService deviceService;
    private final SimulationService simulationService;
    private final GroupService groupService;
    private final ErrorService errorService;

    @EventListener(ApplicationReadyEvent.class)
    public void afterStart() {
        groupService.makeRootGroup();

        List<Device> devices = deviceService.selectDevices();
        for (Device device : devices) {
            if (!device.isDeviceEnabled()) continue;
            // 장비 하나의 설정 오류(예: EthernetIP implicit_t2o_addr 가 IP 포맷이 아님)로
            // 생성자/빈 초기화에서 예외가 터지면 과거엔 그대로 ApplicationReadyEvent 밖으로
            // 올라가 Spring context 가 실패하고, 외부 서비스 매니저가 즉시 재부팅 → 같은 데이터
            // 로 또 crash 하는 루프가 돌았다. 부팅 시엔 문제 장비만 건너뛰고 나머지는 올린다.
            try {
                deviceService.startDevice(device);
            } catch (Exception e) {
                logger.error("Failed to start device id={} name={} at boot. Skipped.",
                        device.getId(), device.getName(), e);
            }
        }
    }
}
