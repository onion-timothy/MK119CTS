package com.onionsoftware.mk119.commtestsuite.io.netty.handler;


import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetUnsignedInteger;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.*;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.BACnetData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.InetSocketAddress;

import java.util.List;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;

public class BACnetHandler extends MkHandler {
    private final BACnetService bacnetService;
    private final SimulationService simulationService;

    public BACnetHandler(IOServer server, BACnetService bacnetService, SimulationService simulationService) {
        super(server);
        this.bacnetService = bacnetService;
        this.simulationService = simulationService;
    }

    @Override
    protected void generateBuffer(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof BACnetPacket) {
            if (msg instanceof BACnetWhois) {
                Device myDevice = getTarget().getDevice();
                ByteBuf buf = ((BACnetWhois) msg).encodeBACnetIAmDeviceResponse(myDevice);
                if (buf != null) {
                    sendBuffer = buf.copy();
                }
            } else if (msg instanceof BACnetReadProperty) {
                BACnetReadProperty readProperty = (BACnetReadProperty) msg;
                int instanceNumber = readProperty.getOid().getInstance_number();

                Device myDevice = getTarget().getDevice();
                int myDeviceInstanceId = Integer
                        .parseInt(myDevice.getCommunicationSettings().get("deviceInstanceId").toString());
                // Device 객체는 OID 인스턴스로, 그 외 객체는 목적지 주소(DADR)로 대상 장비를 식별한다.
                int targetInstanceId = (readProperty.getOid().getObject_type() == BACNET_OBJECT_TYPE_device
                        ? instanceNumber
                        : resolveTargetInstance(readProperty, myDeviceInstanceId));

                if (myDeviceInstanceId == targetInstanceId) {
                    ByteBuf buf = readProperty.encodeBACnetReadPropertyResponse(myDevice);
                    if (buf != null) {
                        sendBuffer = buf.copy();
                    } else {
                        LoggerFactory.getLogger("io").error("BACnet error occur process readProperty {}", getSender());
                        BACnetError error = getBACnetError();
                        sendBuffer = error.encodeBACnetError(
                                myDevice,
                                readProperty.getBacnetAPDU().getInvoke_id(),
                                readProperty.getBacnetAPDU().getService_choice());
                    }
                } else {
                    forwardToCorrectServer(targetInstanceId, msg);
                }
            } else if (msg instanceof BACnetReadPropertyMultiple) {
                BACnetReadPropertyMultiple readPropertyMultiple = (BACnetReadPropertyMultiple) msg;

                Device myDevice = getTarget().getDevice();
                int myDeviceInstanceId = Integer
                        .parseInt(myDevice.getCommunicationSettings().get("deviceInstanceId").toString());
                int targetInstanceId = resolveTargetInstance(readPropertyMultiple, myDeviceInstanceId);

                if (myDeviceInstanceId == targetInstanceId) {
                    ByteBuf buf = readPropertyMultiple.encodeBACnetReadPropertyMultipleResponse(myDevice);
                    if (buf != null) {
                        sendBuffer = buf.copy();
                    } else {
                        LoggerFactory.getLogger("io").error("BACnet error occur process readPropertyMultiple {}", getSender());
                        BACnetError error = getBACnetError();
                        sendBuffer = error.encodeBACnetError(
                                myDevice,
                                readPropertyMultiple.getBacnetAPDU().getInvoke_id(),
                                readPropertyMultiple.getBacnetAPDU().getService_choice());
                    }
                } else {
                    forwardToCorrectServer(targetInstanceId, msg);
                }
            } else if (msg instanceof BACnetWriteProperty) {
                BACnetWriteProperty writeProperty = (BACnetWriteProperty) msg;

                Device myDevice = getTarget().getDevice();
                int myDeviceInstanceId = Integer
                        .parseInt(myDevice.getCommunicationSettings().get("deviceInstanceId").toString());
                int targetInstanceId = resolveTargetInstance(writeProperty, myDeviceInstanceId);

                if (myDeviceInstanceId == targetInstanceId) {
                    List<Simulation> simulationList = bacnetService.getSimulationMap().get(myDevice.getId());
                    if (simulationList != null) {
                        simulationList.stream()
                                // 형식이 틀린 target 은 걸러낸다. requireNonNull 이던 때는 잘못된 시뮬레이션
                                // 하나가 이 장비의 WriteProperty 처리를 통째로 막았다.
                                .filter(e -> BACnetObjectIdentifier.matches(e.getTarget(), writeProperty.getOid()))
                                .findAny().ifPresent(s -> {
                                    ByteBuf buf = writeProperty.encodeBACnetWritePropertyResponse(myDevice);
                                    simulationService.applyStopOnControl(s);
                                    try {
                                        BACnetData bacnetData = (BACnetData) getTarget().getServerData();
                                        bacnetData.setServerData(s,
                                                (double) writeProperty.getValue().getValue(),
                                                ServerDataChangeSource.PROTOCOL_WRITE);
                                    } catch (Exception ex) {
                                        LoggerFactory.getLogger("io").error("BACnet setServerData failed for target {}", s.getTarget(), ex);
                                    }
                                    if (buf != null) {
                                        sendBuffer = buf.copy();
                                    } else {
                                        LoggerFactory.getLogger("io").error("BACnet error occur process writePropertyMultiple {}", getSender());
                                        BACnetError bacnetError = getBACnetError();
                                        sendBuffer = bacnetError.encodeBACnetError(
                                                myDevice,
                                                writeProperty.getBacnetAPDU().getInvoke_id(),
                                                writeProperty.getBacnetAPDU().getService_choice());
                                    }
                                });
                    }
                } else {
                    forwardToCorrectServer(targetInstanceId, msg);
                }
            }
        }
    }

    /**
     * 요청의 목적지 주소(DADR) 마지막 바이트로 대상 장비 인스턴스를 정한다.
     * 마스터(BACnet4J)는 장비를 네트워크 2의 원격 장비(DADR=인스턴스)로 지정해 보낸다.
     * 목적지 주소가 없는 로컬 요청이면 이 소켓에 바인딩된 장비를 대상으로 처리한다.
     */
    private int resolveTargetInstance(BACnetPacket packet, int localInstanceId) {
        byte[] dadr = packet.getBacnetNPDU().getDadr();
        if (dadr != null && dadr.length > 0) {
            return dadr[dadr.length - 1] & 0xFF;
        }
        return localInstanceId;
    }

    private void forwardToCorrectServer(int targetInstanceId, Object msg) {
        IOServer targetServer = bacnetService.getBacnetServerMap().get(targetInstanceId);
        if (targetServer != null) {
            for (ChannelInboundHandlerAdapter handler : targetServer.getHandlers()) {
                if (handler instanceof BACnetHandler) {
                    BACnetHandler targetHandler = (BACnetHandler) handler;
                    InetSocketAddress originalSender = this.getSender();
                    targetHandler.getContext().executor().execute(() -> {
                        targetHandler.setSender(originalSender);
                        targetHandler.channelRead(targetHandler.getContext(), msg);
                    });
                }
            }
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
