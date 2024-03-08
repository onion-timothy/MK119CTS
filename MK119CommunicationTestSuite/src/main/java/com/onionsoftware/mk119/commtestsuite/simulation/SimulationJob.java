package com.onionsoftware.mk119.commtestsuite.simulation;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Protocol;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Policy;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.*;
import com.onionsoftware.mk119.commtestsuite.utils.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SimulationJob implements Runnable {
    static Logger logger = LoggerFactory.getLogger("simulation");
    private final Simulation simulation;
    private static final Map<Integer, Double> accumValues = new HashMap<>();

    public SimulationJob(Simulation simulation) {
        this.simulation = simulation;
    }

    private static final Protocol[] nettyProtocols = new Protocol[]{Protocol.GENERIC, Protocol.MODBUS, Protocol.XGT_PLC, Protocol.DAEYUN_RCU, Protocol.OPC_AGENT};
    private static final DeviceService deviceService = (DeviceService) BeanUtils.getBean("deviceService");
    @Override
    public void run() {
        SimulationPolicy simulationPolicy = simulation.getPolicy();
        double value = parseAndMakeValue(simulationPolicy);

        Device device = deviceService.selectDevice(simulation.getDevice_id());
        if(device.getProtocol() == Protocol.BACNET){
            BACnetService service = BACnetService.getInstance();
            service.getSimulationValues().put(simulation.getId(), (float) value);
        } else if (Arrays.asList(nettyProtocols).contains(device.getProtocol())){
            IOServerInstances ioServerInstances = (IOServerInstances) BeanUtils.getBean("ioServerInstances");
            IOServer server = ioServerInstances.getServerInstance(simulation.getDevice_id());
            if (server != null) {
                ServerData serverData = server.getServerData();
                if (serverData != null) {
                    if (serverData instanceof GenericData) {
                        GenericData genericData = (GenericData) serverData;
                        genericData.getTxFieldsIdValueMap().put(simulation.getTarget(), value);
                    } else if (serverData instanceof ModbusData) {
                        ModbusData modbusData = (ModbusData) serverData;
                        String[] targetTokens = simulation.getTarget().split("_");
                        int function = Integer.parseInt(targetTokens[0]);
                        int register = Integer.parseInt(targetTokens[1]);

                        if (function == Modbus.FC_01) {
                            modbusData.setCoil_status(value == 1, register);
                        } else if (function == Modbus.FC_02) {
                            modbusData.setInput_status(value == 1, register);
                        } else if (function == Modbus.FC_03) {
                            modbusData.setHolding_registers(value, simulationPolicy.getData_type(), register);
                        } else if (function == Modbus.FC_04) {
                            modbusData.setInput_registers(value, simulationPolicy.getData_type(), register);
                        }
                    } else if (serverData instanceof XGTData) {
                        XGTData xgtData = (XGTData) serverData;
                        String[] targetTokens = simulation.getTarget().split("B");
                        String block = targetTokens[0];
                        int address = Integer.parseInt(targetTokens[1]);

                        try {
                            xgtData.setBlockData(block, simulationPolicy.getData_type(), value, address);
                        } catch (Exception e) {
                            logger.error("XGT-PLC set data error.", e);
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
                        OPCAgentData opcAgentData = (OPCAgentData) serverData;
                        int index = Integer.parseInt(simulation.getTarget());
                        opcAgentData.setValue(index, value);
                    } else if (serverData instanceof SnmpData) {
                        SnmpData snmpData = (SnmpData) serverData;
                        snmpData.getOidValueMap().put(simulation.getTarget(), value);
                    }
                }
            }
        }
    }

    public Simulation getSimulation() {
        return this.simulation;
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
}
