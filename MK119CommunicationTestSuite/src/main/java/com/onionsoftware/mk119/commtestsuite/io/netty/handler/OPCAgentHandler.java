package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.bean.ErrorResult;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.OPCAgent;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.OPCAgentData;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("opc_agent")
@Scope("prototype")
public class OPCAgentHandler extends MkHandler {
    private String transport;

    public OPCAgentHandler(ApplicationContext applicationContext, IOServerInstances ioServerInstances) {
        super(applicationContext, ioServerInstances);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        transport = target.getDevice().getCommunicationSettings().get("transport").toString();
    }

    private static final int DEFAULT_TAGS_LENGTH = 1023;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof OPCAgent) {
            OPCAgentData opcAgentData = (OPCAgentData) target.getServerData();
            OPCAgent opc = (OPCAgent) msg;
            int start = opc.getStart();
            int length;
            if (DEFAULT_TAGS_LENGTH + start > opcAgentData.getValues().length) {
                length = opcAgentData.getValues().length - start;
            } else length = DEFAULT_TAGS_LENGTH;

            ByteBuf out = ctx.alloc().buffer(length * 8 + 3);

            double[] values = opcAgentData.getValues(start, length);
            out.writeShort(values.length);
            for (double v : values) {
                out.writeDouble(v);
            }

            byte[] bytes = new byte[values.length * 8 + 2];
            out.getBytes(0, bytes);
            int sum = 0;
            for (byte b : bytes) {
                sum += b;
            }
            out.writeByte(sum & 0xff);

            ErrorService errorService = (ErrorService) applicationContext.getBean("errorService");
            Error errorSimulation = errorService.selectErrorByDeviceId(target.getDevice().getId());
            if (errorSimulation != null) {
                ErrorResult _ret = errorSimulation.performErrorSimulation(out);
                switch (_ret.getResultType()) {
                    case ErrorResult.RESULT_TYPE_DISCONNECT:
                        ctx.close();
                        break;
                    case ErrorResult.RESULT_TYPE_TIMEOUT:
                    case ErrorResult.RESULT_TYPE_GARBAGE:
                        out = _ret.getBufToWrite();
                        break;
                    case ErrorResult.RESULT_TYPE_DISCARD:
                        return;
                }
            }

            lastSent = out.copy();
            if (transport.equals("UDP")) {
                ctx.writeAndFlush(new DatagramPacket(out, opc.getSender()));
            } else ctx.writeAndFlush(out);

            super.channelRead(ctx, msg);
        }
    }
}
