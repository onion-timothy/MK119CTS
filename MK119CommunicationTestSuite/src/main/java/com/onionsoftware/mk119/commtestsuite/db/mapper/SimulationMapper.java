package com.onionsoftware.mk119.commtestsuite.db.mapper;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Mapper
public interface SimulationMapper {
    void createScheme();

    List<Simulation> selectSimulationsByDeviceId(@Param("device_id") Integer id);

    void insertSimulation(Simulation simulation);

    Simulation selectSimulation(Integer id);

    void deleteSimulation(Integer id);

    void updateSimulation(Simulation device);

    Integer selectSimulationEnabled(Integer id);

    void updateSimulationEnabled(@Param("enabled") Integer enabled, @Param("id") Integer id);

    List<Simulation> selectSimulations();

    void deleteSimulationByDeviceId(Integer id);
    void copySimulations(@Param("copyTo") Integer copyTo, @Param("device_id") Integer device_id);
}
