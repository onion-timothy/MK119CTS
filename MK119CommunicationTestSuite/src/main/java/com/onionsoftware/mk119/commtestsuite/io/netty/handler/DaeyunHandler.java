package com.onionsoftware.mk119.commtestsuite.io.netty.handler;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.decoder.MkDecoderInterface;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.TXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.DaeyunData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.GenericData;
import com.onionsoftware.mk119.commtestsuite.io.netty.serverData.ModbusData;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.CRC16;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ExceptionUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.*;

@Component("daeyun_rcu")
@Scope("prototype")
@RequiredArgsConstructor
public class DaeyunHandler extends MkHandler {
    static Logger logger = LoggerFactory.getLogger("io");
    private List<TXFrame> sendFrames;
    private String transport;
    private DaeyunData daeyunData;
    Device device;
    final ApplicationContext applicationContext;
    final IOServerInstances ioServerInstances;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);

        IOServer target = null;
        List<IOServer> ioServers = new ArrayList<>(ioServerInstances.getServers().values());
        for (IOServer server : ioServers) {
            Optional<ChannelInboundHandlerAdapter> optionalDecoder = server.getHandlers().stream().filter(this::equals).findFirst();
            if (optionalDecoder.isPresent()) {
                target = server;
                break;
            }
        }
        if (target != null) {
            device = target.getDevice();
            daeyunData = (DaeyunData) applicationContext.getBean("serverData", device);
            target.setServerData(daeyunData);
            transport = device.getCommunicationSettings().get("transport").toString();

            Type listType = new TypeToken<ArrayList<TXFrame>>() {}.getType();
            this.sendFrames = new Gson().fromJson(device.getCommunicationSettings().get("tx").toString(), listType);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf out = Unpooled.buffer();
        int error = ERROR_NO_ERROR;
        int length = 0;
        if (msg instanceof Daeyun) {
            Daeyun daeyun = (Daeyun) msg;
            out.writeByte(0x01);
            out.writeByte(daeyun.getFunction());
            if (daeyun.getFunction() == Daeyun.DI_FUNCTION) {
                // DI
                int count = daeyun.getInputChannelCount();
                if (count < 32) {
                    int blength = (int) Math.ceil((double) count / 8);
                    length = blength + 3;
                    out.writeByte(blength);
                    byte[] values = new byte[blength];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = 0;
                        for (int j = 0; j < 8; j++) {
                            int index = i * 8 + j;
                            values[i] += (byte) (index < count && daeyunData.getDigitalChannelValue(i) ? 1 << j : 0);
                        }
                    }
                    out.writeBytes(values);
                } else error = ERROR_AI_DI_CHANNEL_RANGE;
            } else if (daeyun.getFunction() == Daeyun.AI_FUNCTION) {
                // AI
                if (daeyun.getInputChannelCount() < 32) {
                    short[] values = daeyunData.getAnalogChannelValues(daeyun.getInputChannelCount());
                    out.writeByte(values.length * 2);
                    length = values.length * 2 + 3;
                    for (short s : values) out.writeShort(s);
                } else error = ERROR_AI_DI_CHANNEL_RANGE;
            } else if (daeyun.getFunction() == Daeyun.DO_FUNCTION) {
                out.writeShort(daeyun.getDOTarget());
                out.writeShort(daeyun.getDOParam());
                length = 6;
                logger.info("DaeyunRCU DO requested : {}, {}", daeyun.getDOTarget(), daeyun.getDOParam() == 65280 ? "ON" : "OFF");
            } else if (daeyun.getFunction() == Daeyun.VI_FUNCTION) {
                out.writeByte(0);
                out.writeByte(4);
                out.writeByte(0);
                ctx.writeAndFlush(out);
                return;
            } else if (daeyun.getFunction() == Daeyun.HB_FUNCTION) {
                out.writeByte(0);
                ctx.writeAndFlush(out);
                return;
            } else if (daeyun.getFunction() == Daeyun.RS_FUNCTION) {
                Optional<TXFrame> optionalTX = sendFrames.stream().filter(e -> e.getTx_id().equals(daeyun.getPacket().getRx_id())).findFirst();
                daeyun.getPacket().setGenericData(new GenericData(daeyunData.getTxFieldsIdValueMap()));
                if (optionalTX.isPresent()) {
                    TXFrame frame = optionalTX.get();
                    frame.translateFields(daeyun.getPacket());
                    int len = ByteBufUtils.calulateTXLength(frame.getFields());
                    out.writeShort(daeyun.getPort());
                    out.writeShort(len);
                    ByteBuf temp = Unpooled.buffer(len);
                    ByteBufUtils._writeTXPacketByFrame(frame, temp, daeyun.getPacket());
                    out.writeBytes(temp);
                    length = len + 6;
                } else {
                    logger.error("no matched tx frame. {}", daeyun.getPacket().getRx_id());
                    return;
                }
            }

            if (error == ERROR_NO_ERROR) {
                byte[] toCRC = new byte[length];
                out.getBytes(0, toCRC, 0, length);
                short crc = CRC16.getCRC16(toCRC);
                out.writeShort(crc);
                ByteBufUtils.writeTXPacketLog(out, this.getClass().getSimpleName());
                if (transport.equals("UDP")) {
                    ctx.writeAndFlush(new DatagramPacket(out, daeyun.getSender()));
                } else ctx.writeAndFlush(out);
                super.channelRead(ctx, msg);

            }
        } else {
            logger.error("Message is not matched for handler. {}", msg.getClass().getName());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[DaeyunHandler] exception occur. {}", cause.getMessage());
        ExceptionUtil.printStackTrace(cause);
        ctx.close();
    }

    public static final int ERROR_NO_ERROR = 0;
    public static final int ERROR_AI_DI_CHANNEL_RANGE = 1;
    public static final int ERROR_TX_MATCH_NOT_FOUND = 2;

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        IOServer server = ioServerInstances.getServerInstance(device.getId());
        server.getSessions().remove((InetSocketAddress) ctx.channel().remoteAddress());
        server.getHandlers().removeIf(this::equals);
        server.getDecoders().removeIf(e->e instanceof MkDecoderInterface && ((MkDecoderInterface) e).getContext().equals(ctx));
    }
}