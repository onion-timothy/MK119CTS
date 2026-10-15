package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.ireasoning.protocol.snmp.*;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
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
                ret.add(new SnmpVarBind(oid, new SnmpOctetString(Double.toString(value))));
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
