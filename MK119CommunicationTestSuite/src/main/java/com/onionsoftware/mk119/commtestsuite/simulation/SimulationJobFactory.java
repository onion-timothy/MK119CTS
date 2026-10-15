package com.onionsoftware.mk119.commtestsuite.simulation;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.db.DBDeviceService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GroupedSimulationJob 인스턴스를 생성하는 팩토리.
 * 의존성 주입을 팩토리에서 일괄 관리한다.
 */
@Component
@RequiredArgsConstructor
public class SimulationJobFactory {
    private final IOServerInstances ioServerInstances;
    private final DBDeviceService dbDeviceService;

    public GroupedSimulationJob create(Device device, int interval) {
        return new GroupedSimulationJob(device, interval, ioServerInstances, dbDeviceService);
    }
}
