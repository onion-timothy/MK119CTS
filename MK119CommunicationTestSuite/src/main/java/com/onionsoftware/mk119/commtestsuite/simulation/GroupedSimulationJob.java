package com.onionsoftware.mk119.commtestsuite.simulation;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Distribution;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Policy;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.io.db.DBDeviceService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import lombok.Getter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 동일 Device + 동일 Interval을 공유하는 Simulation 목록을 하나의 Runnable로 묶어 실행한다.
 * 타겟당 SimulationJob을 개별 생성하는 대신, 그룹 단위로 스케줄링하여 인스턴스 수를 줄인다.
 */
@Getter
public class GroupedSimulationJob implements Runnable {
    private final Device device;
    private final int interval;
    private final List<Simulation> simulations = new CopyOnWriteArrayList<>();
    /** simulations 의 id 집합. 대량 적재 시 중복 검사를 O(1) 로 만들기 위한 색인. */
    private final Set<Integer> simulationIds = ConcurrentHashMap.newKeySet();

    private final IOServerInstances ioServerInstances;
    private final DBDeviceService dbDeviceService;

    /**
     * ACCUMULATE 정책의 누적값. key = simulation id.
     * 값이 없으면 range[0](초기값)부터, 있으면 거기에 range[1](증가분)을 더해 나간다.
     * 여러 ThreadPoolTaskScheduler 스레드가 서로 다른 그룹을 동시에 실행하며 함께 건드리므로
     * 동시성 맵이어야 한다.
     */
    private static final Map<Integer, Double> accumValues = new ConcurrentHashMap<>();

    /** 누적값을 버린다. 다음 실행에서 range[0](초기값)부터 다시 시작한다. */
    public static void resetAccumulated(Integer simulationId) {
        if (simulationId != null) accumValues.remove(simulationId);
    }

    /**
     * SEQUENCE 정책의 현재 인덱스. key = simulation id.
     * accumValues 와 같은 이유로 동시성 맵이어야 한다.
     */
    private static final Map<Integer, Integer> seqIndexes = new ConcurrentHashMap<>();

    /** 시퀀스 위치를 버린다. 다음 실행에서 첫 번째 값부터 다시 시작한다. */
    public static void resetSequence(Integer simulationId) {
        if (simulationId != null) seqIndexes.remove(simulationId);
    }

    /** 정책에 상관없이 해당 simulation 의 진행 상태를 모두 버린다. */
    public static void resetProgress(Integer simulationId) {
        resetAccumulated(simulationId);
        resetSequence(simulationId);
    }

    public GroupedSimulationJob(Device device, int interval,
            IOServerInstances ioServerInstances,
            DBDeviceService dbDeviceService) {
        this.device = device;
        this.interval = interval;
        this.ioServerInstances = ioServerInstances;
        this.dbDeviceService = dbDeviceService;
    }

    /** 이미 들어있으면 false. 중복 검사를 호출측에서 선형 스캔하지 않도록 여기서 처리한다. */
    public boolean addSimulation(Simulation simulation) {
        if (!simulationIds.add(simulation.getId())) {
            return false;
        }
        simulations.add(simulation);
        return true;
    }

    public boolean removeSimulation(int simulationId) {
        if (!simulationIds.remove(simulationId)) {
            return false;
        }
        simulations.removeIf(s -> s.getId().equals(simulationId));
        return true;
    }

    public boolean isEmpty() {
        return simulations.isEmpty();
    }

    public int size() {
        return simulations.size();
    }

    @Override
    public void run() {
        List<Integer> executedIds = new ArrayList<>();

        if (device.getProtocol() == Protocol.BACNET
                || Protocol.NETTY_PROTOCOLS.contains(device.getProtocol())) {
            ioServerInstances.getServerInstance(device.getId()).ifPresent(s -> {
                ServerData serverData = s.getServerData();
                for (Simulation simulation : simulations) {
                    // 값을 만들기 전에 본다. setServerData 안에서도 한 번 더 막지만, 거기까지 가면
                    // 값이 이미 계산되어 ACCUMULATE 누적과 SEQUENCE 위치가 나아가 버린다.
                    // 정지(lock) 중에는 진행 자체가 멈춰야 하므로 여기서 건너뛴다.
                    if (serverData.isLocked(simulation.getTarget()))
                        continue;
                    try {
                        List<SimulationPolicy> policies = simulation.getPolicies();
                        double value = parseAndMakeValue(simulation, policies.get(0));
                        serverData.setServerData(simulation, value, ServerDataChangeSource.VALUE_SIMULATION);
                        executedIds.add(simulation.getId());
                    } catch (Exception e) {
                        MkLogger.log("simulation", e, "Simulation {0} execution failed on device {1}({2})",
                                simulation.getId(), device.getName(), device.getId());
                    }
                }
            });
        } else if (device.getProtocol() == Protocol.DB) {
            // DB 장비는 ServerData 가 없어 SQL 로 바로 쓴다. 걸 lock 이 없으므로 정지도 없다.
            Connection connection = dbDeviceService.getConnection(device);
            for (Simulation simulation : simulations) {
                String replaced = simulation.getTarget();
                for (SimulationPolicy policy : simulation.getPolicies()) {
                    double value = parseAndMakeValue(simulation, policy);
                    replaced = replaced.replaceAll("\\$\\{" + policy.getId() + "}", Double.toString(value));
                }
                if (connection != null) {
                    try {
                        PreparedStatement pstmt = connection.prepareStatement(replaced);
                        int res = pstmt.executeUpdate();
                        if (res == 0) {
                            MkLogger.log("simulation", LogLevel.WARNING,
                                    "DB simulation device {0} target {1} has no effect.", simulation.getTarget(),
                                    device.getName());
                        }
                        executedIds.add(simulation.getId());
                    } catch (SQLException e) {
                        MkLogger.log("simulation", e, "DB simulation device {0} SQL Error : {1}", device.getName(),
                                simulation.getTarget());
                    }
                } else {
                    MkLogger.log("simulation", LogLevel.WARNING, "DB simulation device {0} target {1} has no effect.",
                            simulation.getTarget(), device.getName());
                }
            }
        }

        if (!executedIds.isEmpty()) {
            String logMsg;
            if (executedIds.size() > 10) {
                String joined = executedIds.subList(0, 10).stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", "));
                int otherCount = executedIds.size() - 10;
                logMsg = joined + " and " + otherCount + " other simulations";
            } else {
                logMsg = executedIds.stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", "));
            }
            MkLogger.log("simulation", LogLevel.INFO, "Simulation executed : {0}", logMsg);
        }
    }

    private Double parseAndMakeValue(Simulation simulation, SimulationPolicy simulationPolicy) {
        Policy policy = simulationPolicy.getPolicy();
        double value = Double.NaN;
        if (policy == Policy.STATIC) {
            Double v = simulationPolicy.numericValue();
            if (v == null) {
                MkLogger.log("simulation", LogLevel.ERROR,
                        "STATIC simulation {0} has no numeric value.", simulation.getId());
                return Double.NaN;
            }
            value = v;
        } else if (policy == Policy.SEQUENCE) {
            List<Double> series = simulationPolicy.sequenceValues();
            if (series == null) {
                MkLogger.log("simulation", LogLevel.ERROR,
                        "SEQUENCE simulation {0} has no numeric array in value.", simulation.getId());
                return Double.NaN;
            }
            // 직전 인덱스의 다음 칸으로 진행하고, 끝에 닿으면 처음으로 되돌아간다.
            // 배열 길이가 줄어든 경우에도 안전하도록 매번 모듈로를 적용한다.
            Integer last = seqIndexes.get(simulation.getId());
            int next = (last == null) ? 0 : (last + 1) % series.size();
            seqIndexes.put(simulation.getId(), next);
            value = series.get(next);
        } else if (policy == Policy.RANDOM) {
            List<Double> rangeList = simulationPolicy.getRange();
            double low = rangeList.get(0);
            double high = rangeList.get(1);
            DataType dataType = simulationPolicy.getData_type();
            Distribution dist = simulationPolicy.getDistribution();

            double[] sampleRange = pickBinRange(dist, low, high);
            double binLow = sampleRange[0];
            double binHigh = sampleRange[1];
            double binSpan = binHigh - binLow;

            if (dataType.name().startsWith("FP32")) {
                value = binLow + new Random().nextFloat() * binSpan;
            } else if (dataType.name().startsWith("FP64")) {
                value = binLow + new Random().nextDouble() * binSpan;
            } else {
                // 정수 타입: bin 경계를 정수로 맞춰 [low, high] 범위의 정수를 균등 추출한다.
                // (binLow/binHigh 는 분포 분할 결과라 소수일 수 있으므로 정수 경계로 보정한다.)
                long intLow = (long) Math.ceil(binLow);
                long intHigh = (long) Math.floor(binHigh);
                if (intHigh >= intLow) {
                    value = intLow + new Random().nextInt((int) (intHigh - intLow + 1));
                } else {
                    value = Math.round(binLow);
                }
            }
        } else if (policy == Policy.ACCUMULATE) {
            Double last = accumValues.get(simulation.getId());
            if (last == null) {
                value = simulationPolicy.getRange().get(0);
            } else {
                value = last + simulationPolicy.getRange().get(1);
            }
            accumValues.put(simulation.getId(), value);
        }
        return value;
    }

    /**
     * Distribution 설정에 따라 샘플링할 [low, high] segment 범위를 반환한다.
     * distribution 이 null 이거나 가중치가 유효하지 않으면 전체 range 를 반환(= 기존 균등 분포).
     * sizes 가 주어지면 각 segment 의 상대 폭으로 사용하고, 비어있으면 균등 분할한다.
     */
    private double[] pickBinRange(Distribution dist, double low, double high) {
        if (dist == null || !"BINS".equalsIgnoreCase(dist.getType())) {
            return new double[] { low, high };
        }
        Integer binCount = dist.getBinCount();
        List<Double> weights = dist.getWeights();
        if (binCount == null || binCount <= 0 || weights == null || weights.isEmpty()) {
            return new double[] { low, high };
        }
        int n = Math.min(binCount, weights.size());
        double total = 0.0;
        for (int i = 0; i < n; i++) {
            Double w = weights.get(i);
            if (w != null && w > 0) total += w;
        }
        if (total <= 0) {
            return new double[] { low, high };
        }
        double roll = new Random().nextDouble() * total;
        double cumulative = 0.0;
        int chosen = n - 1;
        for (int i = 0; i < n; i++) {
            Double w = weights.get(i);
            if (w == null || w <= 0) continue;
            cumulative += w;
            if (roll < cumulative) {
                chosen = i;
                break;
            }
        }

        double[] boundaries = computeSegmentBoundaries(dist.getSizes(), n, low, high);
        double binLow = boundaries[chosen];
        double binHigh = boundaries[chosen + 1];
        return new double[] { binLow, binHigh };
    }

    /**
     * sizes(상대 폭) 비율에 따라 [low, high] 를 n 개의 segment 로 나눈 경계 배열을 반환한다.
     * sizes 가 null/empty/합계≤0 이면 균등 분할한다. 길이 = n+1, [0]=low, [n]=high.
     */
    private double[] computeSegmentBoundaries(List<Double> sizes, int n, double low, double high) {
        double[] boundaries = new double[n + 1];
        boundaries[0] = low;
        boundaries[n] = high;

        double span = high - low;
        double sizeTotal = 0.0;
        int m = 0;
        if (sizes != null && !sizes.isEmpty()) {
            m = Math.min(n, sizes.size());
            for (int i = 0; i < m; i++) {
                Double s = sizes.get(i);
                if (s != null && s > 0) sizeTotal += s;
            }
        }

        if (sizeTotal <= 0 || sizes == null) {
            double step = span / n;
            for (int i = 1; i < n; i++) {
                boundaries[i] = low + step * i;
            }
            return boundaries;
        }

        double cum = 0.0;
        for (int i = 1; i < n; i++) {
            double s = 0.0;
            if (i - 1 < m) {
                Double v = sizes.get(i - 1);
                if (v != null && v > 0) s = v;
            }
            cum += s;
            boundaries[i] = low + span * (cum / sizeTotal);
        }
        return boundaries;
    }

    /**
     * 그룹 키를 생성한다. (deviceId_interval)
     */
    public static String makeGroupKey(int deviceId, int interval) {
        return deviceId + "_" + interval;
    }

    public String getGroupKey() {
        return makeGroupKey(device.getId(), interval);
    }
}
