package com.onionsoftware.mk119.commtestsuite.simulation;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class SimulationExecutorService extends ThreadPoolExecutor {
    Map<Integer, Simulation> executingSimulations;
    final Map<Integer, Thread> threads;
    Logger logger = LoggerFactory.getLogger("simulation");

    public SimulationExecutorService() {
        super(200, 200, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2000),
                new BasicThreadFactory.Builder().namingPattern("simulation-pool-thread-%d").daemon(true).build());
        executingSimulations = new HashMap<>();
        threads = new HashMap<>();
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        if (r instanceof SimulationJob) {
            SimulationJob job = (SimulationJob) r;
            synchronized (threads) {
                threads.put(job.getSimulation().getId(), t);
            }
        }
    }

    public void execute(SimulationJob job) {
        Simulation sim = job.getSimulation();
        executingSimulations.put(sim.getId(), sim);
        super.execute(job);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        if (r instanceof SimulationJob) {
            SimulationJob job = (SimulationJob) r;
            try {
                Thread.sleep(job.getSimulation().getInterval() * 1000L);
                super.execute(r);
            } catch (InterruptedException e) {
                logger.error("Interval interrupted.", e);
                executingSimulations.remove(job.getSimulation().getId());
            } finally {
                synchronized (threads) {
                    threads.remove(job.getSimulation().getId());
                }
            }
        }
    }

    public void terminateJob(int id) {
        synchronized (threads) {
            Thread thread = threads.get(id);
            if (thread != null) {
                thread.interrupt();
                threads.remove(id);
            }
        }
    }
}
