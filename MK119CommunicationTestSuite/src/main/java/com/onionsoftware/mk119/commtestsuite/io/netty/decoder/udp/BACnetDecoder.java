package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetObjectIdentifier;
import com.onionsoftware.mk119.commtestsuite.io.bacnet.tag.BACnetUnsignedInteger;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet.*;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.onionsoftware.mk119.commtestsuite.io.bacnet.BACnetConstants.*;
import static com.onionsoftware.mk119.commtestsuite.io.netty.tool.BACnetByteBufUtils.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BACnetDecoder extends MessageToMessageDecoder<DatagramPacket> {
    private final Logger logger = LoggerFactory.getLogger("io");

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        ByteBuf in = msg.content();

        BACnetPacket bacnet = new BACnetPacket();
        bacnet.setSender(msg.sender());

        bacnet.setBvlc_type(in.readByte());
        bacnet.setBvlc_func(in.readByte());
        bacnet.setBvlc_len(in.readShort());

        if (in.readableBytes() + 4 == bacnet.getBvlc_len()) {
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
                ByteBufUtils.clear(in);
                return;
            }
            BACnetAPDU bacnetAPDU = new BACnetAPDU();
            byte apduType_pduFlags = in.readByte();
            bacnetAPDU.setType((byte) (apduType_pduFlags >> 4));
            if (bacnetAPDU.getType() == BACNET_APDU_TYPE_UNCONFIRMED_REQ) {
                bacnetAPDU.setService_choice(in.readByte());
                if (bacnetAPDU.getService_choice() == BACNET_APDU_SERVICE_I_AM) {
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
                }
            } else if (bacnetAPDU.getType() == BACNET_APDU_TYPE_CONFIRMED_REQ) {
                bacnetAPDU.setFlags((byte) (apduType_pduFlags & 0xf));
                bacnetAPDU.setMax_res_seg_acpt(in.readByte());
                bacnetAPDU.setInvoke_id(in.readByte());
                byte serviceChoice = in.readByte();
                bacnetAPDU.setService_choice(serviceChoice);
                bacnet.setBacnetAPDU(bacnetAPDU);

                if (serviceChoice == BACNET_APDU_SERVICE_READ_PROPERTY) {
                    BACnetReadProperty readProperty = new BACnetReadProperty(bacnet);
                    readProperty.setOid((BACnetObjectIdentifier) readBACnetTag(in, serviceChoice));
                    readProperty.setPropertyIdentifier((BACnetUnsignedInteger) readBACnetTag(in, serviceChoice));

                    bacnet = readProperty;
                } else if (serviceChoice == BACNET_APDU_SERVICE_READ_PROPERTY_MULTIPLE) {
                    BACnetReadPropertyMultiple readPropertyMultiple = new BACnetReadPropertyMultiple(bacnet);

                    while(in.readableBytes() != 0){
                        BACnetObjectIdentifier oid = (BACnetObjectIdentifier) readBACnetTag(in, serviceChoice);
                        readPropertyMultiple.getOids().add(oid);
                        Map<BACnetObjectIdentifier, List<Byte>> properties = readPropertyMultiple.getPropertyIdentifiers();
                        properties.put(oid, new ArrayList<>());
                        readBACnetTag(in, serviceChoice); // Opening Tag
                        while(true){
                            Byte pid = (byte) ((BACnetUnsignedInteger) readBACnetTag(in, serviceChoice)).getValue(); // Property Identifier
                            properties.get(oid).add(pid);

                            // Might be Closing Tag or another porperty identifier
                            // If BUI is closing tag, end of pid list loop. else rollback reader index.
                            BACnetUnsignedInteger closingTag = (BACnetUnsignedInteger) readBACnetTag(in, serviceChoice);
                            if (closingTag.getLength() == BACNET_NAMED_TAG_CLOSING_TAG && closingTag.getTagClass() == BACNET_TAG_CLASS_CONTEXT) {
                                break;
                            } else {
                                in.readerIndex(in.readerIndex() - 2);
                            }
                        }
                    }
                    bacnet = readPropertyMultiple;
                }
            }

            out.add(bacnet);
        } else {
            ByteBufUtils.clear(in);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
    }
}
