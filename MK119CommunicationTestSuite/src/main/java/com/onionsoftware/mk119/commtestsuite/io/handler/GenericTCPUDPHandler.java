package com.onionsoftware.mk119.commtestsuite.io.handler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import com.onionsoftware.mk119.commtestsuite.io.tool.ByteBufUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.io.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.generic.RXField;
import com.onionsoftware.mk119.commtestsuite.io.generic.TXField;
import com.onionsoftware.mk119.commtestsuite.io.generic.TXFrame;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class GenericTCPUDPHandler extends ChannelInboundHandlerAdapter {
	static Logger logger = LoggerFactory.getLogger("io");
	private List<TXFrame> sendFrames;
	static Gson parser = new Gson();
	ScriptEngine engine;

	public GenericTCPUDPHandler(List<String> sendFrames) {
		this.sendFrames = new ArrayList<TXFrame>();
		for (String sendFrame : sendFrames) {
			TXFrame frame = parser.fromJson(sendFrame, TXFrame.class);
			this.sendFrames.add(frame);
		}
		ScriptEngineManager manager = new ScriptEngineManager();
		engine = manager.getEngineByName("js");
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		ByteBuf out = null;
		if (msg instanceof GenericTCPUDPPacket) {
			GenericTCPUDPPacket packet = (GenericTCPUDPPacket) msg;
			Optional<TXFrame> optionalTX = sendFrames.stream().filter(e -> e.getTx_id().equals(packet.getRx_id())).findFirst();
			if (optionalTX.isPresent()) {
				TXFrame tx = optionalTX.get();
				out = ctx.alloc().buffer(calulateTXLength(tx));
				Double toWrite = null;
				for (TXField field : tx.getFields()) {
					switch (field.getField_type()) {
						case STATIC:
							if (field.getValue().contains("rand")) {
								int bound = Integer.parseInt(field.getValue().replaceAll("[(rand)\\(\\)]", ""));
								if (field.getData_type().name().contains("FP32")) {
									toWrite = (double) (new Random().nextFloat() * bound);
								} else if (field.getData_type().name().contains("FP64")) {
									toWrite = new Random().nextDouble() * bound;
								} else {
									toWrite = (double) new Random().nextInt(bound);
								}
							} else {
								toWrite = Double.parseDouble(field.getValue());
							}
							if(toWrite != null){
								writeData(out, field.getData_type(), toWrite);
							} else logger.error("value not set {}.", field.getField_id());
							break;
						case REF:
							if (field.getValue().contains("$")) {
								Pattern pattern = Pattern.compile("\\$\\{.*?\\}");
								Matcher matcher = pattern.matcher(field.getValue());
								while (matcher.find()) {
									String token = matcher.group(0);
									String id = token.replaceAll("[\\$\\{\\}]", "");
									Optional<RXField> optrx = packet.getFields().stream().filter(e -> e.getField_id().equals(id)).findFirst();
									if (optrx.isPresent()) {
										field.setValue(field.getValue().replace(token, Double.toString(optrx.get().getValue())));
									}
								}
								try {
									toWrite = (Double) engine.eval(field.getValue());
								} catch (ScriptException e1) {
									e1.printStackTrace();
								}
							}
							if(toWrite != null){
								writeData(out, field.getData_type(), toWrite);
							} else logger.error("value not set {}.", field.getField_id());
							break;
						case ITERATION:
							int iterationCount = 0;
							if (field.getIteration_count().contains("$")) {
								String id = field.getIteration_count().replaceAll("[\\$\\{\\}]", "");
								Optional<RXField> optrx = packet.getFields().stream().filter(e -> e.getField_id().equals(id)).findFirst();
								if (optrx.isPresent()) {
									iterationCount = optrx.get().getValue().intValue();
								}
							} else {
								iterationCount = Integer.parseInt(field.getIteration_count());
							}
							for (int i = 0; i < iterationCount; i++) {
								if (field.getValue().contains("rand")) {
									int bound = Integer.parseInt(field.getValue().replaceAll("[(rand)\\(\\)]", ""));
									if (field.getData_type().name().contains("FP32")) {
										toWrite = (double) (new Random().nextFloat() * bound);
									} else if (field.getData_type().name().contains("FP64")) {
										toWrite = new Random().nextDouble() * bound;
									} else {
										toWrite = (double) new Random().nextInt(bound);
									}
								} else {
									toWrite = Double.parseDouble(field.getValue());
								}
								if(toWrite != null){
									writeData(out, field.getData_type(), toWrite);
								} else logger.error("value not set {}.", field.getField_id());
							}
							break;
					}
				}
			}
		}

		if (out != null)
			ctx.writeAndFlush(out);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();
		ctx.close();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		super.channelInactive(ctx);
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		super.channelActive(ctx);
	}

	private int calulateTXLength(TXFrame sendFrame) {
		int ret = 0;
		for (TXField field : sendFrame.getFields()) {
			switch (field.getData_type()) {
			case SI8:
			case UI8:
				ret++;
				break;
			case UI16AB:
			case UI16BA:
			case SI16AB:
			case SI16BA:
				ret += 2;
				break;
			case UI32AD:
			case UI32CB:
			case UI32BC:
			case UI32DA:
			case SI32AD:
			case SI32CB:
			case SI32BC:
			case SI32DA:
			case FP32AD:
			case FP32CB:
			case FP32BC:
			case FP32DA:
				ret += 4;
				break;
			case SI64AH:
			case SI64GB:
			case SI64BG:
			case SI64HA:
			case FP64AH:
			case FP64GB:
			case FP64BG:
			case FP64HA:
				ret += 8;
				break;
			}
		}
		return ret;
	}
	
	private void writeData(ByteBuf out, DataType type, Double value) {
		try {
			String methodName = "write" + type.name();
			Method method = ByteBufUtils.class.getMethod(methodName, ByteBuf.class, Double.class);
			method.invoke(null, out, value);
		} catch (Exception e){
			logger.error("Write Data Error.", e);
		}
	}
}
