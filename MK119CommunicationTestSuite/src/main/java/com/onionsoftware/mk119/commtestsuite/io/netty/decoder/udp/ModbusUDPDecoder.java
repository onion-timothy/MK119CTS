package com.onionsoftware.mk119.commtestsuite.io.netty.decoder.udp;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component("modbus_decoder_udp")
@Scope("prototype")
public class ModbusUDPDecoder extends MkUDPDecoder {
    int uid;
    int type;

    public ModbusUDPDecoder(IOServerInstances ioServerInstances) {
        super(ioServerInstances);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);
        this.uid = Integer.parseInt(target.getDevice().getCommunicationSettings().get("unitId").toString());
        this.type = Integer.parseInt(target.getDevice().getCommunicationSettings().get("packetType").toString());
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        ByteBuf in = msg.content();
        Modbus modbus = ByteBufUtils.parseModbusPacket(in, uid, type);

        if (modbus != null) {
            modbus.setSender(msg.sender());
            if (modbus instanceof ModbusTCP && type == Modbus.MODBUS_TCP) {
                out.add(modbus);
            } else if (modbus instanceof ModbusRTU && type == Modbus.MODBUS_RTU) {
                out.add(modbus);
            }
        } else {
            MkLogger.log("io", LogLevel.WARNING, "Invalid packet received device {0}({1})", target.getDevice().getName(), target.getDevice().getId());
        }

        ByteBufUtils.clear(in);
    }
}
