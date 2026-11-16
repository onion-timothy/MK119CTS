package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.repository.DeviceRepository;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.db.DBClientDevice;
import com.onionsoftware.mk119.commtestsuite.io.db.DBDeviceService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.testplan.TestPlanScheduler;
import org.springframework.transaction.annotation.Transactional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.persistence.EntityNotFoundException;
import java.sql.SQLException;
import java.util.List;

@Service
@Getter
@RequiredArgsConstructor
public class DeviceService {
    private final IOServerInstances ioServerInstances;
    @Autowired
    @Lazy
    private SimulationService simulationService;
    @Autowired
    @Lazy
    private ErrorService errorService;
    // TestPlanScheduler → TestPlanRunner → DeviceService 로 도는 순환을 @Lazy 로 끊는다
    @Autowired
    @Lazy
    private TestPlanService testPlanService;
    @Autowired
    @Lazy
    private TestPlanScheduler testPlanScheduler;
    private final DBDeviceService dbDeviceService;
    private final BACnetService bacnetService;
    private final Logger logger = LoggerFactory.getLogger("agent");
    private final DeviceRepository deviceRepository;

    @PostConstruct
    public void init() {
    }

    public List<Device> selectDevices() {
        return deviceRepository.findAll();
    }

    public List<Device> selectDevicesByGroupId(Integer group_id) {
        return deviceRepository.findDevicesByGroupId(group_id);
    }

    public Device selectDevice(Integer id) {
        return deviceRepository.findById(id).orElseThrow(EntityNotFoundException::new);
    }

    public Device getDevice(Integer id) {
        return deviceRepository.getDeviceById(id);
    }

    public void insertDevice(Device device) {
        Device inserted = deviceRepository.save(device);
        startDevice(inserted);
    }

    /**
     * 디바이스와 딸린 것을 함께 지운다 — 시뮬레이션, 에러 시뮬레이션, 그리고 Test Plan 의 해당
     * Action. 남겨 두면 참조가 끊긴 채 남아, Plan 은 검증 A1("Action 의 디바이스는 존재해야
     * 한다")에 걸려 저장도 못 하고 실행마다 그 Action 에서 실패한다.
     * <p>
     * 딸린 것을 디바이스 행보다 먼저 지운다 — errors.device_id 가 devices 를 참조한다.
     * <p>
     * 한 트랜잭션으로 묶는다. 시뮬레이션 삭제는 엔티티를 하나씩 지우는 파생 쿼리라 트랜잭션이
     * 있어야 하고, 중간에 실패했을 때 딸린 것만 지워지고 디바이스가 남는 상태도 막는다.
     */
    @Transactional
    public List<String> deleteDevice(Integer id) {
        stopDevice(id);
        errorService.deleteErrorsByDeviceId(id);
        simulationService.deleteSimulationByDeviceId(id);
        // Action 이 하나도 안 남은 Plan 은 사라지므로 그 스케줄을 내린다. Action 만 줄어든 Plan 은
        // 그대로 둬도 된다 — 스케줄은 시각만 쥐고 있고, 실행기가 돌 때 Plan 을 다시 읽는다
        TestPlanService.PlanCleanup cleanup = testPlanService.removeDeviceFromPlans(id);
        cleanup.deleted.forEach(testPlanScheduler::unregister);
        deviceRepository.deleteById(id);
        return cleanup.deletedNames;
    }

    public void updateDevice(Device device) {
        stopDevice(device.getId());
        Device updated = deviceRepository.save(device);
        startDevice(updated);
    }

    public void restartDevice(Device device) {
        stopDevice(device.getId());
        startDevice(device);
    }

    public boolean selectDeviceEnabled(Integer id) {
        return deviceRepository.findById(id).orElseThrow(EntityNotFoundException::new)
                .isDeviceEnabled();
    }

    public void updateDeviceEnabled(Integer enabled, Integer id) {
        stopDevice(id);
        Device device = deviceRepository.findById(id).orElseThrow(EntityNotFoundException::new);
        device.setEnabled(enabled);
        Device updated = deviceRepository.save(device);
        startDevice(updated);
    }

    public void startDevice(Device device) {
        if (device.isDeviceEnabled()) {
            if (Protocol.NETTY_PROTOCOLS.contains(device.getProtocol())) {
                ioServerInstances.addServerInstance(device);
            } else if (device.getProtocol() == Protocol.BACNET) {
                bacnetService.addDevice(device);
            } else if (device.getProtocol() == Protocol.DB) {
                try {
                    DBClientDevice dbClientDevice = new DBClientDevice(device);
                    dbDeviceService.addDevice(dbClientDevice);
                } catch (ClassNotFoundException e) {
                    logger.error("DB Driver class '{}' not found.", device.getCommunicationSettings().get("dbdriver"));
                } catch (SQLException e) {
                    logger.error("SQL error to establish connection.", e);
                }
            }
            simulationService.startDeviceSimulations(device);
            errorService.startAllErrorSchedules(device.getId());
        }
    }

    public void stopDevice(Integer id) {
        Device dev = this.selectDevice(id);
        simulationService.stopDeviceSimulations(id);
        errorService.stopAllErrorSchedules(id);
        if (Protocol.NETTY_PROTOCOLS.contains(dev.getProtocol())) {
            ioServerInstances.stopServerInstance(id);
        } else if (dev.getProtocol() == Protocol.BACNET) {
            bacnetService.removeDevice(id);
        } else if (dev.getProtocol() == Protocol.DB) {
            dbDeviceService.removeDevice(dev.getId());
        }
    }
}
