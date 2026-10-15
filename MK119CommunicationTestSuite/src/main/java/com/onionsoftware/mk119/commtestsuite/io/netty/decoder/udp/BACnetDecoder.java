package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetService;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetReal;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetUnsignedInteger;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.*;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;
import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BACnetDecoder extends MkUDPDecoder {
    private final BACnetService bacnetService;

    public BACnetDecoder(IOServer server, BACnetService bacnetService) {
        super(server);
        this.bacnetService = bacnetService;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();

        BACnetPacket bacnet = new BACnetPacket();

        // BACnet Virtual Link Control 디코딩
        bacnet.setBvlc_type(in.readByte());
        bacnet.setBvlc_func(in.readByte());
        bacnet.setBvlc_len(in.readShort());

        if (in.readableBytes() + 4 == bacnet.getBvlc_len()) { // 패킷 길이 검사
            BACnetNPDU bacnetNPDU = new BACnetNPDU();
            bacnetNPDU.setVersion(in.readByte());
            byte control = in.readByte();
            bacnetNPDU.setControl(control);

            // 표준 NPDU 파싱 (ASHRAE 135 6.2.2): control 비트에 따라 가변 필드를 읽는다.
            // 목적지 지정(0x20): DNET(2) DLEN(1) DADR(DLEN). 대상 장비는 DADR 로 식별된다.
            // 소스 지정(0x08): SNET(2) SLEN(1) SADR(SLEN).
            // 홉 카운트(1)는 목적지 지정이 있을 때만 마지막에 온다.
            boolean destPresent = (control & BACNET_NPDU_CONTROL_DEST_PRESENT) != 0;
            boolean srcPresent = (control & BACNET_NPDU_CONTROL_SRC_PRESENT) != 0;

            if (destPresent) {
                bacnetNPDU.setDest_net_addr(in.readShort());
                byte dlen = in.readByte();
                bacnetNPDU.setDest_mac_layer_addr_len(dlen);
                if (dlen > 0) {
                    byte[] dadr = new byte[dlen];
                    in.readBytes(dadr);
                    bacnetNPDU.setDadr(dadr);
                }
            }
            if (srcPresent) {
                bacnetNPDU.setSrc_net_addr(in.readShort());
                byte slen = in.readByte();
                bacnetNPDU.setSrc_mac_layer_addr_len(slen);
                if (slen > 0) {
                    byte[] sadr = new byte[slen];
                    in.readBytes(sadr);
                    bacnetNPDU.setSadr(sadr);
                }
            }
            if (destPresent) {
                bacnetNPDU.setHop_count(in.readByte());
            }
            if ((control & BACNET_NPDU_CONTROL_NET_MSG) != 0) {
                // APDU 가 아닌 네트워크 계층 메시지(라우터 관리 등)는 지원하지 않는다.
                ByteBufUtils.clear(in);
                return;
            }
            bacnet.setBacnetNPDU(bacnetNPDU);
            if (bacnetNPDU.getVersion() != BACNET_NPDU_VERSION_ASHRAE_135_1995) {
                // BACnet version 1 지원
                ByteBufUtils.clear(in);
                return;
            }
            BACnetAPDU bacnetAPDU = new BACnetAPDU();
            byte apduType_pduFlags = in.readByte();
            bacnetAPDU.setType((byte) (apduType_pduFlags >> 4));
            if (bacnetAPDU.getType() == BACNET_APDU_TYPE_UNCONFIRMED_REQ) {
                bacnetAPDU.setService_choice(in.readByte());
                if (bacnetAPDU.getService_choice() == BACNET_APDU_SERVICE_I_AM) {
                    // 다른 장비의 i-AM에는 응답하지 않는다.
                    ByteBufUtils.clear(in);
                    return;
                } else if (bacnetAPDU.getService_choice() == BACNET_APDU_SERVICE_WHO_IS) {
                    bacnet.setBacnetAPDU(bacnetAPDU);

                    BACnetWhois whois = new BACnetWhois(bacnet);
                    if (in.readableBytes() > 0) {
                        BACnetUnsignedInteger deviceInstanceRangeLowLimit = (BACnetUnsignedInteger) readBACnetTag(in,
                                bacnetAPDU.getService_choice());
                        BACnetUnsignedInteger deviceInstanceRangeHighLimit = (BACnetUnsignedInteger) readBACnetTag(in,
                                bacnetAPDU.getService_choice());
                        whois.setDeviceInstanceRangeLowLimit(deviceInstanceRangeLowLimit);
                        whois.setDeviceInstanceRangeLowLimit(deviceInstanceRangeHighLimit);
                    }
                    bacnet = whois;
                    super.getHandler().setSender(new InetSocketAddress(super.getHandler().getTarget().getDevice()
                            .getCommunicationSettings().get("broadcast").toString(), 0xbac0));
                }
            } else if (bacnetAPDU.getType() == BACNET_APDU_TYPE_CONFIRMED_REQ) {
                bacnetAPDU.setFlags((byte) (apduType_pduFlags & 0xf));
                bacnetAPDU.setMax_res_seg_acpt(in.readByte());
                bacnetAPDU.setInvoke_id(in.readByte());
                byte serviceChoice = in.readByte();
                bacnetAPDU.setService_choice(serviceChoice);
                bacnet.setBacnetAPDU(bacnetAPDU);

                if (serviceChoice == BACNET_APDU_SERVICE_READ_PROPERTY) {
                    // 단일 프로퍼티 읽기 readPropertyRequest APDU는 OID 1개와 Property ID 1개로 구성된다.
                    BACnetReadProperty readProperty = bacnetService.getBacnetReadPropertyFactory().getObject(bacnet);
                    readProperty.setOid((BACnetObjectIdentifier) readBACnetTag(in, serviceChoice));
                    readProperty.setPropertyIdentifier((BACnetUnsignedInteger) readBACnetTag(in, serviceChoice));

                    bacnet = readProperty;
                } else if (serviceChoice == BACNET_APDU_SERVICE_READ_PROPERTY_MULTIPLE) {
                    // 다중 프로퍼티 읽기 readPropertyMultipleRequest APDU는 {OID, Property List}의 반복으로 구성된다.
                    BACnetReadPropertyMultiple readPropertyMultiple = bacnetService
                            .getBacnetReadPropertyMultipleFactory().getObject(bacnet);

                    while (in.readableBytes() != 0) {
                        BACnetObjectIdentifier oid = (BACnetObjectIdentifier) readBACnetTag(in, serviceChoice);
                        readPropertyMultiple.getOids().add(oid);
                        Map<BACnetObjectIdentifier, List<Byte>> properties = readPropertyMultiple
                                .getPropertyIdentifiers();
                        properties.put(oid, new ArrayList<>());
                        readBACnetTag(in, serviceChoice); // Opening Tag
                        while (true) {
                            Byte pid = (byte) ((BACnetUnsignedInteger) readBACnetTag(in, serviceChoice)).getValue(); // Property
                                                                                                                     // ID
                            properties.get(oid).add(pid);
                            // 위의 Property가 마지막 Property라면 Closing Tag이다.
                            // Closing Tag가 아니라면 리더 인덱스를 -2 롤백한다.
                            BACnetUnsignedInteger closingTag = (BACnetUnsignedInteger) readBACnetTag(in, serviceChoice);
                            if (closingTag.getLength() == BACNET_NAMED_TAG_CLOSING_TAG
                                    && closingTag.getTagClass() == BACNET_TAG_CLASS_CONTEXT) {
                                break;
                            } else {
                                in.readerIndex(in.readerIndex() - 2);
                            }
                        }
                    }
                    bacnet = readPropertyMultiple;
                } else if (serviceChoice == BACNET_APDU_SERVICE_WRITE_PROPERTY) {
                    BACnetWriteProperty writeProperty = bacnetService.getBacnetWritePropertyFactory().getObject(bacnet);
                    writeProperty.setOid((BACnetObjectIdentifier) readBACnetTag(in, serviceChoice));
                    writeProperty.setPropertyIdentifier((BACnetUnsignedInteger) readBACnetTag(in, serviceChoice));

                    readBACnetTag(in, serviceChoice); // Opening tag
                    writeProperty.setValue((BACnetReal) readBACnetTag(in, serviceChoice));
                    readBACnetTag(in, serviceChoice); // Closing tag
                    writeProperty.setPriority((BACnetUnsignedInteger) readBACnetTag(in, serviceChoice));
                    bacnet = writeProperty;
                } else
                    return;
            }
            out.add(bacnet);
        } else {
            MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})",
                    target.getDevice().getName(), target.getDevice().getId());
            ByteBufUtils.clear(in);
        }
    }
}
