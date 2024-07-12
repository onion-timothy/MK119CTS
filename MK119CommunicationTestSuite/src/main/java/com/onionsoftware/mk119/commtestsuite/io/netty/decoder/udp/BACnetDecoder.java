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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;
import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component("bacnet_decoder_udp")
@Scope("prototype")
public class BACnetDecoder extends MkUDPDecoder {
    private final BACnetService bacnetService;
    public BACnetDecoder(ApplicationContext applicationContext, IOServer server) {
        super(applicationContext, server);
        bacnetService = (BACnetService) applicationContext.getBean("bacnetService");
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

        if (in.readableBytes() + 4 == bacnet.getBvlc_len()) {   // 패킷 길이 검사
            BACnetNPDU bacnetNPDU = new BACnetNPDU();
            bacnetNPDU.setVersion(in.readByte());
            bacnetNPDU.setControl(in.readByte());
            if (bacnetNPDU.getControl() == BACNET_NPDU_CONTROL_DEST_SPEC) {
                bacnetNPDU.setDest_net_addr(in.readShort());
                bacnetNPDU.setDest_mac_layer_addr_len(in.readByte());
                bacnetNPDU.setHop_count(in.readByte());
            } else if (bacnetNPDU.getControl() == BACNET_NPDU_CONTROL_DEST_SRC_SPEC) {
                bacnetNPDU.setDest_net_addr(in.readShort());
                bacnetNPDU.setDest_mac_layer_addr_len(in.readByte());
                bacnetNPDU.setSrc_net_addr(in.readByte());
                bacnetNPDU.setSrc_mac_layer_addr_len(in.readByte());
                byte[] src_addr = new byte[bacnetNPDU.getSrc_mac_layer_addr_len()];
                in.readBytes(src_addr);
                bacnetNPDU.setSadr(src_addr);
                bacnetNPDU.setHop_count(in.readByte());
            } else if (bacnetNPDU.getControl() == BACNET_NPDU_CONTROL_DEST_SPEC_EXPT_RPLY) {
                bacnetNPDU.setDest_net_addr(in.readShort());
                bacnetNPDU.setDest_mac_layer_addr_len(in.readByte());
                byte[] dst_addr = new byte[bacnetNPDU.getSrc_mac_layer_addr_len()];
                in.readBytes(dst_addr);
                bacnetNPDU.setSadr(dst_addr);
                bacnetNPDU.setHop_count(in.readByte());
            } else {
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
                        BACnetUnsignedInteger deviceInstanceRangeLowLimit = (BACnetUnsignedInteger) readBACnetTag(in, bacnetAPDU.getService_choice());
                        BACnetUnsignedInteger deviceInstanceRangeHighLimit = (BACnetUnsignedInteger) readBACnetTag(in, bacnetAPDU.getService_choice());
                        whois.setDeviceInstanceRangeLowLimit(deviceInstanceRangeLowLimit);
                        whois.setDeviceInstanceRangeLowLimit(deviceInstanceRangeHighLimit);
                    }
                    bacnet = whois;
                    super.getHandler().setSender(new InetSocketAddress(super.getHandler().getTarget().getDevice().getCommunicationSettings().get("broadcast").toString(), 0xbac0));
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
                    BACnetReadPropertyMultiple readPropertyMultiple = bacnetService.getBacnetReadPropertyMultipleFactory().getObject(bacnet);

                    while (in.readableBytes() != 0) {
                        BACnetObjectIdentifier oid = (BACnetObjectIdentifier) readBACnetTag(in, serviceChoice);
                        readPropertyMultiple.getOids().add(oid);
                        Map<BACnetObjectIdentifier, List<Byte>> properties = readPropertyMultiple.getPropertyIdentifiers();
                        properties.put(oid, new ArrayList<>());
                        readBACnetTag(in, serviceChoice); // Opening Tag
                        while (true) {
                            Byte pid = (byte) ((BACnetUnsignedInteger) readBACnetTag(in, serviceChoice)).getValue(); // Property ID
                            properties.get(oid).add(pid);
                            // 위의 Property가 마지막 Property라면 Closing Tag이다.
                            // Closing Tag가 아니라면 리더 인덱스를 -2 롤백한다.
                            BACnetUnsignedInteger closingTag = (BACnetUnsignedInteger) readBACnetTag(in, serviceChoice);
                            if (closingTag.getLength() == BACNET_NAMED_TAG_CLOSING_TAG && closingTag.getTagClass() == BACNET_TAG_CLASS_CONTEXT) {
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
                } else return;
            }
            out.add(bacnet);
        } else {
            MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
            ByteBufUtils.clear(in);
        }
    }
}
