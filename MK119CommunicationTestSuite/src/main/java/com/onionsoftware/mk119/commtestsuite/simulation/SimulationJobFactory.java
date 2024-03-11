package com.onionsoftware.mk119.commtestsuite.simulation;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
public class SimulationJobFactory {
    @Bean
    @Scope(value = "prototype")
    public SimulationJob simulationJob(Simulation simulation) {
        return new SimulationJob(simulation);
    }
}
