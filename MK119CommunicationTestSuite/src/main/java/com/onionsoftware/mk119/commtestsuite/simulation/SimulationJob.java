package com.onionsoftware.mk119.commtestsuite.simulation;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Policy;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.db.DBDeviceService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

@Getter
@RequiredArgsConstructor
public class SimulationJob implements Runnable {
    private final Simulation simulation;
    private static final Map<Integer, Double> accumValues = new HashMap<>();
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private DBDeviceService dbDeviceService;
    @Autowired
    private IOServerInstances ioServerInstances;
    @Autowired
    private BACnetService bacnetService;

    @Override
    public void run() {
        List<SimulationPolicy> simulationPolicies = simulation.getPolicies();

        Device device = deviceService.selectDevice(simulation.getDevice_id());
        if (device.getProtocol() == Protocol.BACNET) {
            double value = parseAndMakeValue(simulationPolicies.get(0));
            bacnetService.getSimulationValues().put(simulation.getId(), (float) value);
        } else if (Protocol.NETTY_PROTOCOLS.contains(device.getProtocol())) {
            double value = parseAndMakeValue(simulationPolicies.get(0));
            IOServer server = ioServerInstances.getServerInstance(simulation.getDevice_id());
            if (server != null) {
                ServerData serverData = server.getServerData();
                if (serverData != null) {
                    if (serverData instanceof GenericData) {
                        GenericData genericData = (GenericData) serverData;
                        genericData.getTxFieldsIdValueMap().put(simulation.getTarget(), value);
                    } else if (serverData instanceof ModbusData) {
                        try {
                            ModbusData modbusData = (ModbusData) serverData;
                            String[] targetTokens = simulation.getTarget().split("_");
                            int function = Integer.parseInt(targetTokens[0]);
                            int register = Integer.parseInt(targetTokens[1]);

                            if (function == Modbus.FC_01) {
                                modbusData.setCoil_status(value == 1, register);
                            } else if (function == Modbus.FC_02) {
                                modbusData.setInput_status(value == 1, register);
                            } else if (function == Modbus.FC_03) {
                                modbusData.setHolding_registers(value, simulationPolicies.get(0).getData_type(), register);
                            } else if (function == Modbus.FC_04) {
                                modbusData.setInput_registers(value, simulationPolicies.get(0).getData_type(), register);
                            }
                        } catch (NumberFormatException e) {
                            MkLogger.log("simulation", LogLevel.ERROR, "'{0}' is Invalid target for Modbus simulation device {1}({2}). Format target to {Function}_{Register}", getSimulation().getTarget(), device.getName(), device.getId());
                        }
                    } else if (serverData instanceof XGTData) {
                        try {
                            XGTData xgtData = (XGTData) serverData;
                            String target = simulation.getTarget();
                            int addr = extractNumbers(target);
                            String block = String.valueOf(target.charAt(1));
                            char dataTypeChar = target.charAt(2);
                            DataType dataType = simulationPolicies.get(0).getData_type();

                            if (dataTypeChar == 'B') {
                                if (dataType == null || !dataType.name().contains("8")) {
                                    MkLogger.log("simulation", LogLevel.ERROR, "XGTPLC simulation wrong data type. {0}, {1}, {2}", device.getName(), dataType == null ? null : dataType.name(), dataTypeChar);
                                    return;
                                }
                            } else if (dataTypeChar == 'W') {
                                if (dataType == null || !dataType.name().contains("16")) {
                                    MkLogger.log("simulation", LogLevel.ERROR, "XGTPLC simulation wrong data type. {0}, {1}, {2}", device.getName(), dataType == null ? null : dataType.name(), dataTypeChar);
                                    return;
                                }
                                addr *= 2;
                            } else if (dataTypeChar == 'D') {
                                if (dataType == null || !dataType.name().contains("32")) {
                                    MkLogger.log("simulation", LogLevel.ERROR, "XGTPLC simulation wrong data type. {0}, {1}, {2}", device.getName(), dataType == null ? null : dataType.name(), dataTypeChar);
                                    return;
                                }
                                addr *= 4;
                            }

                            try {
                                xgtData.setBlockData(block, simulationPolicies.get(0).getData_type(), value, addr);
                            } catch (Exception e) {
                                MkLogger.log("simulation", e, "XGTPLC data set error.");
                            }


                            /*
                            String[] targetTokens = simulation.getTarget().split("B");
                            String block = targetTokens[0];
                            int address = Integer.parseInt(targetTokens[1]);

                            try {
                                xgtData.setBlockData(block, simulationPolicies.get(0).getData_type(), value, address);
                            } catch (Exception e) {
                                MkLogger.log("simulation", e, "XGTPLC data set error.");
                            }

                             */
                        } catch (NumberFormatException e) {
                            MkLogger.log("simulation", LogLevel.ERROR, "'{0}' is Invalid target for XGTPLC simulation device {1}({2}). Format target to {Block}B{Address}.", getSimulation().getTarget(), device.getName(), device.getId());
                        }
                    } else if (serverData instanceof DaeyunData) {
                        DaeyunData daeyunData = (DaeyunData) serverData;
                        if (simulation.getTarget().startsWith("@RCUDI_")) {
                            int channel = Integer.parseInt(simulation.getTarget().split("_")[1]);
                            daeyunData.setDigitalChannelValues(channel - 1, value != 0);
                        } else if (simulation.getTarget().startsWith("@RCUAI_")) {
                            int channel = Integer.parseInt(simulation.getTarget().split("_")[1]);
                            daeyunData.setAnalogChannelValues(channel - 1, (short) value);
                        } else {
                            daeyunData.getTxFieldsIdValueMap().put(simulation.getTarget(), value);
                        }
                    } else if (serverData instanceof OPCAgentData) {
                        try {
                            OPCAgentData opcAgentData = (OPCAgentData) serverData;
                            int index = Integer.parseInt(simulation.getTarget());
                            opcAgentData.setValue(index, value);
                        } catch (NumberFormatException e) {
                            MkLogger.log("simulation", LogLevel.ERROR, "'{0}' is Invalid target for OPCAgent simulation device {1}({2}). Format target to Integer.", getSimulation().getTarget(), device.getName(), device.getId());
                        }
                    } else if (serverData instanceof SnmpData) {
                        SnmpData snmpData = (SnmpData) serverData;
                        snmpData.getOidValueMap().put(simulation.getTarget(), value);
                    }
                }
            }
        } else if (device.getProtocol() == Protocol.DB) {
            String replaced = simulation.getTarget();
            for (SimulationPolicy policy : simulationPolicies) {
                double value = parseAndMakeValue(policy);
                replaced = replaced.replaceAll("\\$\\{" + policy.getId() + "}", Double.toString(value));
            }
            Connection connection = dbDeviceService.getConnection(device);
            if (connection != null) {
                try {
                    PreparedStatement pstmt = connection.prepareStatement(replaced);
                    int res = pstmt.executeUpdate();
                    if (res == 0) {
                        MkLogger.log("simluation", LogLevel.WARNING, "DB simulation device {0} target {1} has no effect.", simulation.getTarget(), device.getName());
                    }
                } catch (SQLException e) {
                    MkLogger.log("simluation", e, "DB simulation device {0} SQL Error : {1}", device.getName(), simulation.getTarget());
                }
            } else
                MkLogger.log("simluation", LogLevel.WARNING, "DB simulation device {0} target {1} has no effect.", simulation.getTarget(), device.getName());
        }
    }

    private Double parseAndMakeValue(SimulationPolicy simulationPolicy) {
        Policy policy = simulationPolicy.getPolicy();
        double value = Double.NaN;
        if (policy == Policy.STATIC) {
            value = simulationPolicy.getValue();
        } else if (policy == Policy.RANDOM) {
            List<Double> rangeList = simulationPolicy.getRange();
            double range = rangeList.get(1) - rangeList.get(0);
            DataType dataType = simulationPolicy.getData_type();
            if (dataType.name().startsWith("FP32")) {
                value = new Random().nextFloat() * range;
            } else if (dataType.name().startsWith("FP64")) {
                value = new Random().nextDouble() * range;
            } else {
                value = new Random().nextInt((int) range);
            }
            value += rangeList.get(0);
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

    private int extractNumbers(String input) {
        StringBuilder numbers = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isDigit(ch)) {
                numbers.append(ch);
            }
        }

        return Integer.parseInt(numbers.toString());
    }
}
