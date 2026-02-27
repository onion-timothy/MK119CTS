package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.EthernetIPData;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import etherip.protocol.*;
import etherip.types.CIPData;
import etherip.types.CNService;
import etherip.types.CNSymbolPath;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Component("ethernet_ip")
@Scope("prototype")
public class EthernetIPHandler extends MkHandler {
    ByteBuffer out;
    private final SimulationService simulationService;
    private final IOServer server;

    public EthernetIPHandler(ApplicationContext applicationContext, IOServer server) {
        super(applicationContext, server);
        simulationService = (SimulationService) applicationContext.getBean("simulationService");
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
                    String tag = path.getElements().get(0).toString();
                    CIPData cipData = ethernetIPData.getTagValue(tag);

                    if (cipData != null) {
                        if (service == CNService.CIP_ReadData) {
                            CIPReadDataProtocol cipReadDataProtocol = (CIPReadDataProtocol) mr_body.getBody();
                            cipReadDataProtocol.setData(cipData);
                        } else if (service == CNService.CIP_WriteData) {
                            try {
                                CIPWriteDataProtocol cipWriteDataProtocol = (CIPWriteDataProtocol) mr_body.getBody();
                                double value = cipWriteDataProtocol.getData().getNumber(0).doubleValue();
                                simulationService.selectSimulationsByDeviceId(target.getDevice().getId()).stream().filter(e -> e.getTarget().split("@")[1].equals(tag)).findFirst().ifPresent(s -> processWrite(s, value));
                            } catch (Exception e) {
                                MkLogger.log("io", LogLevel.ERROR, "Error while process enip write deivce {0} tag {1}", getTarget().getDevice().getName(), tag);
                            }
                        } else {
                            MkLogger.log("io", LogLevel.WARNING, "Simulation target {0} for {1} is not exist.", path.getElements().get(0).toString(), target.getDevice().getName());
                        }
                    } else {
                        MessageRouterProtocol mr_res = new MessageRouterProtocol(service, null, new ProtocolAdapter());
                        mr_res.setStatus(4);
                        mr_res.setExt_status(new int[]{0});
                        unconnectedSendProtocol.setBody(mr_res);
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
        data.putTagValue(s.getTarget(), value);

        if (s.isStopOnControl()) {
            simulationService.stopSimulation(s);
        }
    }
}