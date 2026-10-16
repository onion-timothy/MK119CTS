package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.ireasoning.protocol.snmp.*;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.SnmpPdu;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.SnmpData;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.util.Optional;

public class SnmpHandler extends MkHandler {
    private final SimulationService simulationService;

    public SnmpHandler(IOServer server, SimulationService simulationService) {
        super(server);
        this.simulationService = simulationService;
    }

    @Override
    protected void generateBuffer(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof SnmpPdu) {
            SnmpPdu packet = (SnmpPdu) msg;

            SnmpPdu ret = new SnmpPdu(SnmpConst.RESPONSE);
            ret.setDestinationAddress(packet.getSourceAddress());
            ret.setDestinationPort(packet.getDestinationPort());
            ret.setVersion(packet.getVersion());
            ret.setCommunity(packet.getCommunity());
            ret.setRequestID(packet.getRequestID());

            if (packet.getCommand() == SnmpConst.SET) {
                ret.addVarBinds(processSetRequest(packet));
            } else {
                ret.addVarBinds(processGetRequest(packet));
            }
            ByteBuf buffer = Unpooled.buffer();
            try {
                buffer.writeBytes(SnmpPdu.encodePdu(ret));
                sendBuffer = buffer.copy();
            } catch (SnmpEncodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private SnmpVarBindList processGetRequest(SnmpPdu packet) {
        SnmpData snmpData = (SnmpData) target.getServerData();
        SnmpVarBindList ret = new SnmpVarBindList();
        for (SnmpVarBind snmpVarBind : packet.getVarBinds()) {
            SnmpOID oid = snmpVarBind.getName();
            Double value = snmpData.getOidValueMap().get(oid.toString());
            if (value != null) {
                ret.add(new SnmpVarBind(oid, snmpValueOf(snmpData, oid.toString(), value)));
            } else {
                if (packet.getVersion() == SnmpConst.SNMPV2 && packet.getCommand() == SnmpConst.GET_BULK)
                    ret.add(new SnmpVarBind(oid, SnmpDataType.NO_SUCH_OBJECT_OBJECT));
                else {
                    packet.setErrorIndex(1);
                    packet.setErrorStatus(2);
                    return null;
                }
            }
        }
        return ret;
    }

    /**
     * Simulation 의 data_type 에 맞는 SNMP 타입으로 값을 싼다.
     * <p>
     * 전에는 타입과 무관하게 {@code SnmpOctetString(Double.toString(value))} 로만 보냈다.
     * 그러면 6128 이 문자열 "6128.0" 으로 나가, 표준 SNMP 매니저가 숫자로 읽지 못한다.
     * <pre>
     *   UI32*                 Unsigned32 (SnmpUInt)
     *   그 밖의 8/16/32 정수   Integer32  (SnmpInt)
     *   FP32* / FP64* / 64비트 정수, data_type 미지정   OctetString (문자열)
     * </pre>
     * SNMP 에는 실수 타입이 없고 Integer32 는 부호 있는 32비트라, 담을 수 없는 것은
     * 문자열로 남긴다. 바이트 순서는 BER 이 big-endian 으로 고정하므로 AB/BA 구분은 쓰지 않는다.
     */
    private SnmpDataType snmpValueOf(SnmpData snmpData, String oid, double value) {
        DataType dataType = dataTypeOf(snmpData, oid);
        if (dataType == null) {
            return new SnmpOctetString(Double.toString(value));
        }
        String name = dataType.name();
        if (name.startsWith("FP") || name.contains("64")) {
            // SNMP 에 float 타입이 없다. Counter64 는 카운터 전용이라 일반 값에 쓰지 않는다.
            return new SnmpOctetString(Double.toString(value));
        }
        long rounded = Math.round(value);
        if (name.startsWith("UI32")) {
            return new SnmpUInt(rounded & 0xFFFFFFFFL);
        }
        return new SnmpInt((int) rounded);
    }

    /** 그 OID 를 쓰는 Simulation 의 첫 정책에서 data_type 을 꺼낸다. 없으면 null. */
    private DataType dataTypeOf(SnmpData snmpData, String oid) {
        Simulation simulation = snmpData.simulationFor(oid);
        if (simulation == null || simulation.getPolicies() == null || simulation.getPolicies().isEmpty()) {
            return null;
        }
        return simulation.getPolicies().get(0).getData_type();
    }

    private SnmpVarBindList processSetRequest(SnmpPdu packet) {
        SnmpData snmpData = (SnmpData) target.getServerData();
        SnmpVarBindList ret = new SnmpVarBindList();
        for (SnmpVarBind snmpVarBind : packet.getVarBinds()) {
            SnmpOID oid = snmpVarBind.getName();
            Simulation found = snmpData.simulationFor(oid.toString());
            if (found != null) {
                Simulation simulation = found;
                ret.add(snmpVarBind);
                double valueToWrite = Double.parseDouble(snmpVarBind.getValue().toString());
                try {
                    snmpData.setServerData(simulation, valueToWrite, ServerDataChangeSource.PROTOCOL_WRITE);
                } catch (Exception e) {
                    MkLogger.log("io", e, "SNMP setServerData failed for OID {0}", oid.toString());
                }
                simulationService.applyStopOnControl(simulation);
            } else {
                packet.setErrorIndex(1);
                packet.setErrorStatus(2);
                return null;
            }
        }
        return ret;
    }
}
