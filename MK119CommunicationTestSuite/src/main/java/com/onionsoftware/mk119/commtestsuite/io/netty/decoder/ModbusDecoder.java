package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.Modbus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("modbus_decoder_tcp")
@Scope("prototype")
@RequiredArgsConstructor
public class ModbusDecoder extends ByteToMessageDecoder {
    Logger logger = LoggerFactory.getLogger("io");
    int uid;
    int type;

    Device device;
    final ApplicationContext applicationContext;
    final IOServerInstances ioServerInstances;

    @Override
    public void channelActive(ChannelHandlerContext ctx){
        logger.info("decoder channel registered");
        Optional<IOServer> optionalIOServer = ioServerInstances.getServers().values().stream().filter(e->this.equals(e.getDecoder())).findFirst();
        if(optionalIOServer.isPresent()){
            IOServer ioServer = optionalIOServer.get();
            device = ioServer.getDevice();

            this.uid = Integer.parseInt(device.getCommunicationSettings().get("unitId").toString());
            this.type = Integer.parseInt(device.getCommunicationSettings().get("packetType").toString());
        }
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());
        Modbus modbus = ByteBufUtils.parseModbusPacket(in, uid, type);
        if (modbus instanceof ModbusTCP && type == Modbus.MODBUS_TCP) {
            out.add(modbus);
        } else if (modbus instanceof ModbusRTU && type == Modbus.MODBUS_RTU) {
            out.add(modbus);
        }
        ByteBufUtils.clear(in);
    }
}
