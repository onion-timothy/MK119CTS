package com.onionsoftware.mk119.commtestsuite.db.mapper;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Mapper
public interface DeviceMapper {
    void createScheme();

    List<Device> selectDevices();

    List<Device> selectDevicesByGroupId(Integer group_id);

    Device selectDevice(Integer id);

    void insertDevice(Device device);

    void deleteDevice(Integer id);

    void updateDevice(Device device);

    Integer selectDeviceEnabled(Integer id);

    void updateDeviceEnabled(@Param("enabled") Integer enabled, @Param("id") Integer id);
    Integer identDevices();
}
