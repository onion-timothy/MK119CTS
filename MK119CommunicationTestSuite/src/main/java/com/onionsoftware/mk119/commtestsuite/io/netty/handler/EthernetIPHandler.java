package com.onionsoftware.mk119.commtestsuite.io.netty.handler;


import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ethernetip.SymbolPadding;
import org.slf4j.LoggerFactory;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.EthernetIPData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ServerDataChangeSource;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ethernetip.CipErrorReply;
import etherip.protocol.*;
import etherip.types.CIPData;
import etherip.types.CNService;
import etherip.types.CNSymbolPath;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class EthernetIPHandler extends MkHandler {
    ByteBuffer out;
    private final SimulationService simulationService;
    private final IOServer server;

    public EthernetIPHandler(IOServer server, SimulationService simulationService) {
        super(server);
        this.simulationService = simulationService;
        out = ByteBuffer.allocateDirect(1024);
        out.order(ByteOrder.LITTLE_ENDIAN);
        this.server = server;
    }

    @Override
    protected void generateBuffer(ChannelHandlerContext ctx, Object msg) {
        out.clear();
        if (msg instanceof Encapsulation) {
            Encapsulation encapsulation = (Encapsulation) msg;
            if (encapsulation.getCommand() == Encapsulation.Command.RegisterSession) {
                int session = ((EthernetIPData) target.getServerData()).createSession();
                Encapsulation res = new Encapsulation(Encapsulation.Command.RegisterSession, session, new RegisterSessionProtocol());
                res.setContext(encapsulation.getContext());
                try {
                    res.encode(out, null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else if (encapsulation.getCommand() == Encapsulation.Command.UnRegisterSession) {
                ((EthernetIPData) target.getServerData()).invalidateSession(encapsulation.getSession());
                return;
            } else if (encapsulation.getCommand() == Encapsulation.Command.SendRRData) {
                SendRRDataProtocol sendRRDataProtocol = (SendRRDataProtocol) encapsulation.getBody();
                UnconnectedSendProtocol unconnectedSendProtocol = (UnconnectedSendProtocol) sendRRDataProtocol.getBody();

                MessageRouterProtocol mr_encoder = unconnectedSendProtocol.getEncoder();
                CNService service = mr_encoder.getService();

                if (service == CNService.Get_Attribute_All) {
                    GetIdentityProtocol getIdentityProtocol = new GetIdentityProtocol();
                    getIdentityProtocol.setIdentity(GetIdentityProtocol.IDENTITY_ROCKWELL_AB_1756_EN_2TD);
                    unconnectedSendProtocol.setBody(new MessageRouterProtocol(CNService.Get_Attribute_All, null, getIdentityProtocol));
                } else if (service == CNService.CM_ForwardOpen) {
                    ConnectionManagerProtocol connectionManagerProtocol = (ConnectionManagerProtocol) ((MessageRouterProtocol) unconnectedSendProtocol.getBody()).getBody();
                    sendRRDataProtocol.setCount((short) 4);
                    sendRRDataProtocol.setAddr_type((short) 0);
                    sendRRDataProtocol.setAddr_length((short) 0);
                    sendRRDataProtocol.setData_type((short) 0xb2);

                    EthernetIPData ethernetIPData = (EthernetIPData) server.getServerData();
                    connectionManagerProtocol.setO2t_sin_family((short) 2);
                    connectionManagerProtocol.setO2t_sin_port(ethernetIPData.getImplicit_o_t_port());
                    connectionManagerProtocol.setO2t_sin_addr(ethernetIPData.getImplicit_t_o_addr());

                    InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
                    connectionManagerProtocol.setT2o_sin_addr(ByteBuffer.wrap(remote.getAddress().getAddress()).order(ByteOrder.BIG_ENDIAN).getInt());
                } else {
                    MessageRouterProtocol mr_body = (MessageRouterProtocol) unconnectedSendProtocol.getBody();
                    service = mr_body.getService();
                    EthernetIPData ethernetIPData = (EthernetIPData) getTarget().getServerData();
                    CNSymbolPath path = (CNSymbolPath) mr_body.getPath();
                    // 홀수 길이 이름은 디코더가 채움 바이트까지 이름으로 읽게 했다(SymbolPadding). 그 NUL 을 뗀다
                    String tag = SymbolPadding.stripPadding(path.getElements().get(0).toString());
                    CIPData cipData = ethernetIPData.getTagValue(tag);

                    if (cipData != null) {
                        if (service == CNService.CIP_ReadData) {
                            CIPReadDataProtocol cipReadDataProtocol = (CIPReadDataProtocol) mr_body.getBody();
                            cipReadDataProtocol.setData(cipData);
                        } else if (service == CNService.CIP_WriteData) {
                            try {
                                CIPWriteDataProtocol cipWriteDataProtocol = (CIPWriteDataProtocol) mr_body.getBody();
                                CIPData.Type requested = cipWriteDataProtocol.getData().getType();
                                CIPData.Type expected = ethernetIPData.cipTypeOf(tag);
                                if (expected != null && requested != expected) {
                                    // 실제 Logix 와 같게 거절한다: 일반 상태 0xFF, 확장 상태 0x2107
                                    // (요청의 데이터 타입이 태그의 데이터 타입과 다름). 값은 쓰지 않는다.
                                    LoggerFactory.getLogger("io").info("EtherNet/IP write rejected on device {}: tag {} is {} but the request is {}.",
                                            getTarget().getDevice().getName(), tag, expected, requested);
                                    unconnectedSendProtocol.setBody(new CipErrorReply(service, 0xFF, 0x2107));
                                } else {
                                    double value = cipWriteDataProtocol.getData().getNumber(0).doubleValue();
                                    // 색인은 태그 이름으로 되어 있다(EthernetIPData.canonicalTarget)
                                    Simulation hit = ethernetIPData.simulationFor(tag);
                                    if (hit != null) processWrite(hit, value);
                                }
                            } catch (Exception e) {
                                LoggerFactory.getLogger("io").error("Error while process enip write deivce {} tag {}", getTarget().getDevice().getName(), tag);
                            }
                        } else {
                            LoggerFactory.getLogger("io").warn("Simulation target {} for {} is not exist.", tag, target.getDevice().getName());
                        }
                    } else {
                        // 없는 태그: Path segment error(0x04)
                        unconnectedSendProtocol.setBody(new CipErrorReply(service, 4, 0));
                    }
                }
                try {
                    encapsulation.encodeResponse(out);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            out.flip();
            sendBuffer = Unpooled.wrappedBuffer(out);
        }
    }

    private void processWrite(Simulation s, double value) {
        EthernetIPData data = (EthernetIPData) getTarget().getServerData();
        // lock 에 막혀 쓰지 못했으면 정지도 걸지 않는다. 응답은 호출한 쪽이 그대로 보낸다.
        boolean applied = false;
        try {
            applied = data.setServerData(s, value, ServerDataChangeSource.PROTOCOL_WRITE);
        } catch (Exception e) {
            LoggerFactory.getLogger("io").error("EthernetIP setServerData failed for target {}", s.getTarget(), e);
        }

        if (applied) simulationService.applyStopOnControl(s);
    }
}