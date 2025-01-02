package com.onionsoftware.mk119.commtestsuite.simulation.execution;


import org.springframework.boot.task.TaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.boot.task.TaskSchedulerCustomizer;
import java.util.concurrent.*;

@Service
public class _SimulationExecutorService implements  TaskSchedulerCustomizer {
    @Bean
    public ThreadPoolTaskScheduler threadPoolTaskScheduler() {
        ThreadPoolTaskScheduler threadPoolTaskScheduler
                = new ThreadPoolTaskScheduler();
        threadPoolTaskScheduler.setPoolSize(5);
        threadPoolTaskScheduler.setThreadNamePrefix(
                "ThreadPoolTaskScheduler");
        return threadPoolTaskScheduler;
    }

    @Bean
    public BlockingQueue<ScheduledFuture<?>> scheduledFutureBlockingQueue() {
        return new ArrayBlockingQueue<>(500);
    }

    @Override
    public void customize(ThreadPoolTaskScheduler taskScheduler) {

    }
}
