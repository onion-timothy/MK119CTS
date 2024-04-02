package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetUnsignedInteger;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.*;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;

@Component("bacnet")
@Scope("prototype")
public class BACnetHandler extends MkHandler {
    private final BACnetService bacnetService;

    public BACnetHandler(BACnetService bacnetService, ApplicationContext applicationContext, IOServerInstances ioServerInstances) {
        super(applicationContext, ioServerInstances);
        this.bacnetService = bacnetService;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof BACnetPacket) {
            if (msg instanceof BACnetWhois) {
                for (Device device : bacnetService.getBacnetDevices()) {
                    ByteBuf buf = ((BACnetWhois) msg).encodeBACnetIAmDeviceResponse(device);
                    if (buf != null) {
                        lastSent = buf.copy();
                        ctx.writeAndFlush(new DatagramPacket(buf, new InetSocketAddress(device.getCommunicationSettings().get("broadcast").toString(), 0xbac0)));
                    }
                }
            } else if (msg instanceof BACnetReadProperty) {
                BACnetReadProperty readProperty = (BACnetReadProperty) msg;
                byte instanceNumberSADR = readProperty.getBacnetNPDU().getSadr()[readProperty.getBacnetNPDU().getSadr().length - 1];
                int instanceNumber = readProperty.getOid().getInstance_number();

                Optional<Device> deviceOptional = bacnetService.getBacnetDevices().stream().filter(e -> Integer.parseInt(e.getCommunicationSettings().get("deviceInstanceId").toString()) == (readProperty.getOid().getObject_type() == BACNET_OBJECT_TYPE_device ? instanceNumber : instanceNumberSADR)).findFirst();
                if (deviceOptional.isPresent()) {
                    ByteBuf buf = readProperty.encodeBACnetReadPropertyResponse(deviceOptional.get());
                    if (buf != null) {
                        lastSent = buf.copy();
                        ctx.writeAndFlush(new DatagramPacket(buf, readProperty.getSender()));
                    } else {
                        MkLogger.log("io", LogLevel.ERROR, "BACnet error occur process readProperty {0}", readProperty.getSender());
                        BACnetError error = getBACnetError();
                        lastSent = error.encodeBACnetError(
                                deviceOptional.get(),
                                readProperty.getBacnetAPDU().getInvoke_id(),
                                readProperty.getBacnetAPDU().getService_choice());
                        ctx.writeAndFlush(new DatagramPacket(lastSent.copy(), readProperty.getSender()));
                    }
                }
            } else if (msg instanceof BACnetReadPropertyMultiple) {
                BACnetReadPropertyMultiple readPropertyMultiple = (BACnetReadPropertyMultiple) msg;
                byte instanceNumberSADR = readPropertyMultiple.getBacnetNPDU().getSadr()[readPropertyMultiple.getBacnetNPDU().getSadr().length - 1];

                Optional<Device> deviceOptional = bacnetService.getBacnetDevices().stream().filter(e -> Integer.parseInt(e.getCommunicationSettings().get("deviceInstanceId").toString()) == instanceNumberSADR).findFirst();
                if (deviceOptional.isPresent()) {
                    ByteBuf buf = readPropertyMultiple.encodeBACnetReadPropertyMultipleResponse(deviceOptional.get());
                    if (buf != null) {
                        lastSent = buf.copy();
                        ctx.writeAndFlush(new DatagramPacket(buf, readPropertyMultiple.getSender()));
                    } else {
                        MkLogger.log("io", LogLevel.ERROR, "BACnet error occur process readPropertyMultiple {0}", readPropertyMultiple.getSender());
                        BACnetError error = getBACnetError();
                        lastSent = error.encodeBACnetError(
                                deviceOptional.get(),
                                readPropertyMultiple.getBacnetAPDU().getInvoke_id(),
                                readPropertyMultiple.getBacnetAPDU().getService_choice());
                        ctx.writeAndFlush(new DatagramPacket(lastSent.copy(), readPropertyMultiple.getSender()));
                    }
                }
            } else if (msg instanceof BACnetWriteProperty) {
                BACnetWriteProperty writeProperty = (BACnetWriteProperty) msg;
                byte instanceNumberSADR = writeProperty.getBacnetNPDU().getSadr()[writeProperty.getBacnetNPDU().getSadr().length - 1];

                Optional<Device> deviceOptional = bacnetService.getBacnetDevices().stream().filter(e -> Integer.parseInt(e.getCommunicationSettings().get("deviceInstanceId").toString()) == instanceNumberSADR).findFirst();
                if (deviceOptional.isPresent()) {
                    Device device = deviceOptional.get();
                    List<Simulation> simulationList = bacnetService.getSimulationMap().get(device.getId());
                    Optional<Simulation> optionalSimulation = simulationList.stream().filter(e -> Objects.requireNonNull(BACnetObjectIdentifier.parseSimulationTarget(e.getTarget())).equals(writeProperty.getOid())).findAny();
                    if (optionalSimulation.isPresent()) {
                        Simulation simulation = optionalSimulation.get();
                        if (simulation.getPolicies().get(0).isStopOnControl()) {
                            SimulationService service = (SimulationService) applicationContext.getBean("simulationService");
                            simulation.setEnabled(Simulation.DISABLED);
                            service.updateSimulation(simulation);
                        }
                        bacnetService.getSimulationValues().put(simulation.getId(), writeProperty.getValue().getValue());

                        ByteBuf buf = writeProperty.encodeBACnetWritePropertyResponse(device);
                        if (buf != null) {
                            lastSent = buf.copy();
                            ctx.writeAndFlush(new DatagramPacket(buf, writeProperty.getSender()));
                        } else {
                            MkLogger.log("io", LogLevel.ERROR, "BACnet error occur process writePropertyMultiple {0}", writeProperty.getSender());
                            BACnetError error = getBACnetError();
                            lastSent = error.encodeBACnetError(
                                    device,
                                    writeProperty.getBacnetAPDU().getInvoke_id(),
                                    writeProperty.getBacnetAPDU().getService_choice());
                            ctx.writeAndFlush(new DatagramPacket(lastSent.copy(), writeProperty.getSender()));
                        }
                    }
                }
            } else return;
            super.channelRead(ctx, msg);
        }
    }

    private static BACnetError getBACnetError() {
        BACnetUnsignedInteger errorClass = new BACnetUnsignedInteger(0);
        errorClass.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        errorClass.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
        errorClass.setLength((byte) 1);

        BACnetUnsignedInteger errorCode = new BACnetUnsignedInteger(0);
        errorCode.setTagClass(BACNET_TAG_CLASS_APPLICATION);
        errorCode.setTagNumber(BACNET_APPLICATION_TAG_NUMBER_ENUMERATED);
        errorCode.setLength((byte) 1);

        return new BACnetError(errorClass, errorCode);
    }
}
