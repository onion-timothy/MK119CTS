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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component("ethernet_ip")
@Scope("prototype")
public class EthernetIPHandler extends MkHandler {
    int session;
    static List<Integer> sessions = new ArrayList<>();
    ByteBuffer out;
    private final SimulationService simulationService;

    public EthernetIPHandler(ApplicationContext applicationContext, IOServer server) {
        super(applicationContext, server);
        simulationService = (SimulationService) applicationContext.getBean("simulationService");
        session = new Random().nextInt();
        while (sessions.contains(session)) {
            session = new Random().nextInt();
        }
        sessions.add(session);
        out = ByteBuffer.allocateDirect(1024);
        out.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        out.clear();
        if (msg instanceof Encapsulation) {
            Encapsulation encapsulation = (Encapsulation) msg;
            if (encapsulation.getCommand() == Encapsulation.Command.RegisterSession) {
                Encapsulation res = new Encapsulation(Encapsulation.Command.RegisterSession, this.session, new RegisterSessionProtocol());
                res.setContext(encapsulation.getContext());
                try {
                    res.encode(out, null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else if (encapsulation.getCommand() == Encapsulation.Command.UnRegisterSession) {
                sessions.remove(new Integer(session));
                return;
            } else if (encapsulation.getCommand() == Encapsulation.Command.SendRRData) {
                SendRRDataProtocol sendRRDataProtocol = (SendRRDataProtocol) encapsulation.getBody();
                UnconnectedSendProtocol unconnectedSendProtocol = (UnconnectedSendProtocol) sendRRDataProtocol.getBody();

                MessageRouterProtocol mr_encoder = (MessageRouterProtocol) unconnectedSendProtocol.getEncoder();
                MessageRouterProtocol mr_body = (MessageRouterProtocol) unconnectedSendProtocol.getBody();

                CNService service = mr_encoder.getService();

                if (service == CNService.Get_Attribute_All) {
                    GetIdentityProtocol getIdentityProtocol = new GetIdentityProtocol();
                    getIdentityProtocol.setIdentity(GetIdentityProtocol.IDENTITY_ROCKWELL_AB_1756_EN_2TD);
                    unconnectedSendProtocol.setBody(new MessageRouterProtocol(CNService.Get_Attribute_All, null, getIdentityProtocol));
                } else {
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
                        mr_res.setExt_status(new int [] {0});
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
            super.channelRead(ctx, msg);
        }
    }

    private void processWrite(Simulation s, double value) {
        EthernetIPData data = (EthernetIPData) getTarget().getServerData();
        data.putTagValue(s.getTarget(), value);

        if (s.getPolicies().get(0).isStopOnControl()) {
            simulationService.stopSimulation(getTarget().getDevice(), s);
        }
    }
}