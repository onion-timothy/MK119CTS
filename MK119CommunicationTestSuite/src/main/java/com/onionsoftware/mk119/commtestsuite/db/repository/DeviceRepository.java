package com.onionsoftware.mk119.commtestsuite.db.repository;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, Integer> {
    List<Device> findDevicesByGroupId(Integer group_id);
    Device getDeviceById(Integer id);
}
