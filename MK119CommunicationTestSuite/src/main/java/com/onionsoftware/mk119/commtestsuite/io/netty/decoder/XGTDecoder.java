package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class XGTDecoder extends ByteToMessageDecoder {
	Logger logger = LoggerFactory.getLogger("io");
	
	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		ByteBufUtils.writeRXPacketLog(in, this.getClass().getSimpleName());

		XGT xgt = ByteBufUtils.parseXGTPacket(in);
		if(xgt != null){
			out.add(xgt);
		}
		ByteBufUtils.clear(in);
	}

}
