package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.ireasoning.protocol.snmp.*;
import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.bean.ErrorResult;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.SnmpPdu;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.SnmpData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("snmp")
@Scope("prototype")
public class SnmpHandler extends MkHandler {
    public SnmpHandler(ApplicationContext applicationContext, IOServerInstances ioServerInstances) {
        super(applicationContext, ioServerInstances);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
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

                ErrorService errorService = (ErrorService) applicationContext.getBean("errorService");
                Error errorSimulation = errorService.selectErrorByDeviceId(target.getDevice().getId());
                if (errorSimulation != null) {
                    ErrorResult _ret = errorSimulation.performErrorSimulation(buffer);
                    switch (_ret.getResultType()) {
                        case ErrorResult.RESULT_TYPE_DISCONNECT:
                            ctx.close();
                            break;
                        case ErrorResult.RESULT_TYPE_TIMEOUT:
                        case ErrorResult.RESULT_TYPE_GARBAGE:
                            buffer = _ret.getBufToWrite();
                            break;
                        case ErrorResult.RESULT_TYPE_DISCARD:
                            return;
                    }
                }

                lastSent = buffer.copy();
                ctx.writeAndFlush(new DatagramPacket(buffer, packet.getSender()));
                super.channelRead(ctx, msg);
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
            SimulationService simulationService = (SimulationService) applicationContext.getBean("simulationService");
            Optional<Simulation> simulationOptional = simulationService.selectSimulationsByDeviceId(target.getDevice().getId()).stream().filter(e -> e.getTarget().equals(oid.toString())).findAny();
            if (simulationOptional.isPresent()) {
                Simulation simulation = simulationOptional.get();
                ret.add(snmpVarBind);
                double valueToWrite = Double.parseDouble(snmpVarBind.getValue().toString());
                snmpData.getOidValueMap().put(oid.toString(), valueToWrite);
                if(simulation.getPolicies().get(0).isStopOnControl()){
                    simulation.setEnabled(Simulation.DISABLED);
                    simulationService.updateSimulation(simulation);
                }
            } else {
                packet.setErrorIndex(1);
                packet.setErrorStatus(2);
                return null;
            }
        }
        return ret;
    }
}
