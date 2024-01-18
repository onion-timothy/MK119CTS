package com.onionsoftware.mk119.commtestsuite.io.decoder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.io.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.generic.RXField;
import com.onionsoftware.mk119.commtestsuite.io.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.tool.ByteBufUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class GenericTCPUDPDecoder extends ByteToMessageDecoder {
	Logger logger = LoggerFactory.getLogger("io");
	private List<RXFrame> acceptFrames;
	static Gson parser = new Gson();

	public GenericTCPUDPDecoder(List<String> acceptFrames) {
		this.acceptFrames = new ArrayList<RXFrame>();
		for (String acceptFrame : acceptFrames) {
			RXFrame frame = parser.fromJson(acceptFrame, RXFrame.class);
			this.acceptFrames.add(frame);
		}
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		ByteBufUtils.writeRXPacketLog(in);
		GenericTCPUDPPacket packet = null;

		for (RXFrame frame : acceptFrames) {
			if (frame.getLength() != null && frame.getLength() != in.readableBytes()) {
				continue;
			}
			try {
				packet = new GenericTCPUDPPacket(frame.getRx_id());
				for (RXField field : frame.getFields()) {
					String methodName = "read" + field.getData_type().name();
					Method method = ByteBufUtils.class.getMethod(methodName, ByteBuf.class);
					RXField newFieldObj = (RXField) field.clone();
					newFieldObj.setValue((Double) method.invoke(null, in));
					
					logger.info("{} {}", newFieldObj.getValue(), newFieldObj.getRequired_value());
					if(!methodName.contains("FP") && newFieldObj.getRequired_value() != null && !newFieldObj.getRequired_value().equals(newFieldObj.getValue())) {
						packet = null;
						break;
					}
					packet.addField(newFieldObj);
				}
			} catch (Exception e) {
				logger.error("packet parse error. {}", parser.toJson(frame), e);
				packet = null;
				in.resetReaderIndex();
				continue;
			}
			
			if(in.isReadable()) {
				packet = null;
				in.resetReaderIndex();
				continue;
			}
		}
		
		if(packet == null) {
			ByteBufUtils.clear(in);
			logger.info("No matched frame.");
		} else if(packet != null) {
			out.add(packet);
		}
	}
}
