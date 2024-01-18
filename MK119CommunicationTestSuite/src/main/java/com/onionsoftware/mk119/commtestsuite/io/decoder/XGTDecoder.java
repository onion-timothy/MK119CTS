package com.onionsoftware.mk119.commtestsuite.io.decoder;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onionsoftware.mk119.commtestsuite.io.packet.XGT;
import com.onionsoftware.mk119.commtestsuite.io.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class XGTDecoder extends ByteToMessageDecoder {
	Logger logger = LoggerFactory.getLogger("io");
	
	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		ByteBufUtils.writeRXPacketLog(in);
		
		Object packet = null;
		
		byte[] buf = new byte[8];
    	in.getBytes(0, buf, 0, buf.length);
    	String cid = new String(buf);
    	
		switch (cid) {
			case "LSIS-XGT":
				XGT xgt = new XGT();
	    		in.skipBytes(13);
	    		xgt.setSource(in.readByte());
	    		if (xgt.getSource() != 0x33) {
	    			logger.error("XGTDecoder format source error : {}", xgt.getSource());
	    			break;
	    		}
	    		xgt.setInvokeId(in.readShortLE());
	    		xgt.setLength(in.readShortLE());
	    		in.skipBytes(2);
	    		
	    		xgt.setCode(in.readShortLE());
	    		if(xgt.getCode() != 0x54) {
	    			logger.error("XGTDecoder format function code error : {}", xgt.getCode());
	    			break;
	    		}
	    		
	    		xgt.setDataType(in.readShortLE());
	    		if(xgt.getDataType() != 0x14) {
	    			logger.error("XGTDecoder format function code error : {}", xgt.getDataType());
	    			break;
	    		}
	    		
	    		in.skipBytes(4);
	    		
	    		byte[] varBuff = new byte[in.readShortLE()];
	    		in.readBytes(varBuff);
	    		xgt.setVariable(new String(varBuff));
	    		xgt.setBlockSize(in.readShortLE());
	    		packet = xgt;
				break;
			default:
				break;
		}
    	
    	if (packet != null) {
			out.add(packet);
		} else {
			ByteBufUtils.clear(in);
		}
	}

}
