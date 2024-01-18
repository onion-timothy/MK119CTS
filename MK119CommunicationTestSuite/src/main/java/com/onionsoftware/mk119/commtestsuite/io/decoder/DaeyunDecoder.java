package com.onionsoftware.mk119.commtestsuite.io.decoder;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onionsoftware.mk119.commtestsuite.io.packet.Daeyun;
import com.onionsoftware.mk119.commtestsuite.io.tool.ByteBufUtils;
import com.onionsoftware.mk119.commtestsuite.io.tool.CRC16;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class DaeyunDecoder extends ByteToMessageDecoder {
	Logger logger = LoggerFactory.getLogger("io");

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		ByteBufUtils.writeRXPacketLog(in);
		Object packet = null;
		
		byte[] _cpyBuf = new byte[in.readableBytes()];
		in.getBytes(0, _cpyBuf, 0, in.readableBytes() - 2);
		short _crc = CRC16.getCRC16(_cpyBuf);
		
		Daeyun daeyun = new Daeyun();
		daeyun.setAddress(in.readByte());
		switch(daeyun.getAddress()) {
			case 1:
				daeyun.setFunction(in.readByte());
				
				if(daeyun.getFunction() == 0x03 || daeyun.getFunction() == 0x02) {
					// AI & DI
					daeyun.setCardAddress(in.readShort());
					if(daeyun.getCardAddress() != 0x00) {
						logger.error("DaeyunDecoder format card address error: {}", daeyun.getCardAddress());
						break;
					}
					daeyun.setInputChannelCount(in.readShort());
				} else if (daeyun.getFunction() == 0xfe) {
					// Version
				} else if (daeyun.getFunction() == 0x05) {
					// DO
					daeyun.setDOTarget(in.readShort());
					daeyun.setDOParam(in.readShort());
					if(daeyun.getDOParam() != 0xff00 || daeyun.getDOParam() != 0x0000) {
						logger.error("DaeyunDecoder format do parameter error: {}", daeyun.getDOParam());
						break;
					}
				} else if (daeyun.getFunction() == 0x0a) {
					// Serial
					daeyun.setPort(in.readUnsignedShort());
					daeyun.setLength(in.readUnsignedShort());
					byte[] payload = new byte[daeyun.getLength()];
					in.readBytes(payload);
				} else {
					logger.error("DaeyunDecoder format function error: {}", daeyun.getFunction());
					break;
				}
				
				short crcReceived = in.readShort();
				if(_crc != in.readShort()) {
					logger.error("DaeyunDecoder format crc error: {}", crcReceived);
					break;
				}
				packet = daeyun;
				break;
			default:
				logger.error("DaeyunDecoder format address error: {}", daeyun.getAddress());
				break;
		}
		
		if(packet != null)
			out.add(packet);
		else ByteBufUtils.clear(in);
	}
}
