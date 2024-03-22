package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component("modbus_decoder_udp")
@Scope("prototype")
@RequiredArgsConstructor
public class ModbusUDPDecoder extends MkUDPDecoder {
    Logger logger = LoggerFactory.getLogger("io");
    int uid;
    int type;

    Device device;
    final ApplicationContext applicationContext;
    final IOServerInstances ioServerInstances;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);

        IOServer target = null;
        List<IOServer> ioServers = new ArrayList<>(ioServerInstances.getServers().values());
        for (IOServer server : ioServers) {
            Optional<ChannelInboundHandlerAdapter> optionalDecoder = server.getDecoders().stream().filter(this::equals).findFirst();
            if (optionalDecoder.isPresent()) {
                target = server;
                break;
            }
        }

        if (target != null) {
            device = target.getDevice();
            this.uid = Integer.parseInt(device.getCommunicationSettings().get("unitId").toString());
            this.type = Integer.parseInt(device.getCommunicationSettings().get("packetType").toString());
            ctx.fireChannelActive();
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
        Modbus modbus = ByteBufUtils.parseModbusPacket(in, uid, type);

        if (modbus != null) {
            modbus.setSender(msg.sender());
            if (modbus instanceof ModbusTCP && type == Modbus.MODBUS_TCP) {
                out.add(modbus);
            } else if (modbus instanceof ModbusRTU && type == Modbus.MODBUS_RTU) {
                out.add(modbus);
            }
        }

        ByteBufUtils.clear(in);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[ModbusUDPDecoder] Modbus packet decode error: {}", cause.getMessage());
    }
}
