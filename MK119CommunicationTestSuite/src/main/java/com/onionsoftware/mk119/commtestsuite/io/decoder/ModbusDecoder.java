package com.onionsoftware.mk119.commtestsuite.io.decoder;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onionsoftware.mk119.commtestsuite.io.packet.ModbusRTU;
import com.onionsoftware.mk119.commtestsuite.io.packet.ModbusTCP;
import com.onionsoftware.mk119.commtestsuite.io.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.io.tool.CRC16;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class ModbusDecoder extends ByteToMessageDecoder {
	Logger logger = LoggerFactory.getLogger("io");
	int uid;

	public ModbusDecoder(int uid) {
		this.uid = uid;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		ByteBufUtils.writeRXPacketLog(in);
		Object packet = null;
		
		switch (in.readableBytes()) {
			case 12:
				ModbusTCP modbusTcp = new ModbusTCP();
				modbusTcp.setTid(in.readShort());
				modbusTcp.setPid(in.readShort());
				if (modbusTcp.getPid() != ModbusTCP.PROTOCOL_ID) {
					logger.error("ModbusDecoder format pid error: {}", modbusTcp.getPid());
					break;
				}
				
				modbusTcp.setTcpLength(in.readShort());
				if (modbusTcp.getTcpLength() != 6) {
					logger.error("ModbusDecoder format length error: {}", modbusTcp.getTcpLength());
					break;
				}
				
				modbusTcp.setUid(in.readByte());
				if(this.uid != 0 && modbusTcp.getUid() != this.uid) {
					logger.error("ModbusDecoder format uid error: {}", modbusTcp.getUid());
					break;
				}
				
				modbusTcp.setFc(in.readByte());
				if (modbusTcp.getFc() > 4 || modbusTcp.getFc() < 0) {
					logger.error("ModbusDecoder format function code error: {}", modbusTcp.getFc());
					break;
				}
				
				modbusTcp.setRegister(in.readShort());
				modbusTcp.setRequestSize(in.readShort());
				packet = modbusTcp;
				break;
			case 8:
				ModbusRTU modbusRtu = new ModbusRTU();
				byte[] cpyBuf = new byte[6];
				in.getBytes(0, cpyBuf, 0, 6);
				short crc = CRC16.getCRC16(cpyBuf);
	
				modbusRtu.setUid(in.readByte());
				if(this.uid != 0 && modbusRtu.getUid() != this.uid) {
					logger.error("ModbusDecoder format uid error: {}", modbusRtu.getUid());
					break;
				}
				
				modbusRtu.setFc(in.readByte());
				if (modbusRtu.getFc() > 4 || modbusRtu.getFc() < 0) {
					logger.error("ModbusDecoder format function code error: {}", modbusRtu.getFc());
					break;
				}
				modbusRtu.setRegister(in.readShort());
				modbusRtu.setRequestSize(in.readShort());
				modbusRtu.setCrc(in.readShort());
				if (crc != modbusRtu.getCrc()) {
					logger.error("ModbusDecoder format crc error: {}", modbusRtu.getCrc());
					break;
				}
				packet = modbusRtu;
				break;
			default:
				logger.error("ModbusDecoder format frame error.");
		}

		if (packet != null) {
			out.add(packet);
		} else {
			ByteBufUtils.clear(in);
		}
	}
}
