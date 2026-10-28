package com.onionsoftware.mk119.commtestsuite;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.GroupService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.db.service.TestPlanService;
import com.onionsoftware.mk119.commtestsuite.testplan.TestPlanScheduler;
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
    private final TestPlanService testPlanService;
    private final TestPlanScheduler testPlanScheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void afterStart() {
        groupService.makeRootGroup();

        // interval 로 저장된 예전 Test Plan 을 지운다(실행 시각으로 바뀜). 스케줄을 등록하기 전에 해야 한다
        try {
            int dropped = testPlanService.dropIntervalPlans();
            if (dropped >= 0) logger.info("Removed {} test plan(s) saved with intervals; plans now use run times.", dropped);
        } catch (Exception e) {
            logger.error("Failed to remove interval-based test plans at boot.", e);
        }
        // skip_on_failure(실패해도 계속) → stop_on_failure(실패하면 멈춤). 기본이 "계속" 으로 바뀌었다
        try {
            int moved = testPlanService.migrateSkipToStop();
            if (moved >= 0) logger.info("Moved {} test plan action(s) from skip_on_failure to stop_on_failure.", moved);
        } catch (Exception e) {
            logger.error("Failed to migrate skip_on_failure to stop_on_failure at boot.", e);
        }

        // 실행 도중 서버가 내려가 RUNNING 으로 남은 Test Plan 이력을 닫는다. 실행은 이어서 하지 않는다.
        // 이력 정리 실패로 기동이 막히면 안 되므로 예외는 남기고 넘어간다.
        try {
            testPlanService.closeInterruptedRuns();
        } catch (Exception e) {
            logger.error("Failed to close interrupted test plan runs at boot.", e);
        }
        // 켜진 스케줄을 다시 등록한다. 꺼져 있던 동안 놓친 실행은 건너뛴다(E4)
        try {
            testPlanScheduler.registerAll();
        } catch (Exception e) {
            logger.error("Failed to register test plan schedules at boot.", e);
        }

        // 예전 형식(REAL@TAG1)의 EtherNet/IP Simulation 을 태그 이름 + data_type 으로 옮긴다.
        // 장비가 뜨기 전에 해야 색인이 처음부터 태그 이름으로 만들어진다
        try {
            int migrated = simulationService.migrateEthernetIpTargets();
            if (migrated > 0) logger.info("Migrated {} EtherNet/IP simulation target(s) to tag names.", migrated);
        } catch (Exception e) {
            logger.error("Failed to migrate EtherNet/IP simulation targets at boot.", e);
        }

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
