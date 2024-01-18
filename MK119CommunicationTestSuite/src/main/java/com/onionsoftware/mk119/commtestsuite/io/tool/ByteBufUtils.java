package com.onionsoftware.mk119.commtestsuite.io.tool;

import javax.xml.bind.DatatypeConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;

public class ByteBufUtils {
	static Logger logger = LoggerFactory.getLogger("io");

	public static void clear(ByteBuf in) {
		while (in.isReadable()) {
			in.readByte();
		}
	}

	public static void writeRXPacketLog(ByteBuf in) {
		byte[] orig = new byte[in.readableBytes()];

		for (int i = 0; i < orig.length; i++)
			orig[i] = in.getByte(i);
		logger.info("RX : {}", DatatypeConverter.printHexBinary(orig));
	}

	public static double readSI8(ByteBuf in) {
		return in.readByte();
	}

	public static double readUI8(ByteBuf in) {
		return in.readUnsignedByte();
	}

	public static double readSI16AB(ByteBuf in) {
		return in.readShort();
	}

	public static double readSI16BA(ByteBuf in) {
		return in.readShortLE();
	}

	public static double readUI16AB(ByteBuf in) {
		return in.readUnsignedShort();
	}

	public static double readUI16BA(ByteBuf in) {
		return in.readUnsignedShortLE();
	}

	public static double readSI32AD(ByteBuf in) {
		return in.readInt();
	}

	public static double readSI32CB(ByteBuf in) {
		int ab = in.readUnsignedShort();
		int cd = in.readUnsignedShort();
		return (cd << 16) + ab;
	}

	public static double readSI32BC(ByteBuf in) {
		int ba = in.readUnsignedShortLE();
		int dc = in.readUnsignedShortLE();
		return (ba << 16) + dc;
	}

	public static double readSI32DA(ByteBuf in) {
		return in.readIntLE();
	}

	public static double readUI32AD(ByteBuf in) {
		return in.readUnsignedInt();
	}

	public static double readUI32CB(ByteBuf in) {
		return ((long) readSI32CB(in)) & 0xffffffffl;
	}

	public static double readUI32BC(ByteBuf in) {
		return ((long) readSI32BC(in)) & 0xffffffffl;
	}

	public static double readUI32DA(ByteBuf in) {
		return in.readUnsignedIntLE();
	}

	public static double readSI64AH(ByteBuf in) {
		return in.readLong();
	}

	public static double readSI64GB(ByteBuf in) {
		long ab = in.readUnsignedShort();
		long cd = in.readUnsignedShort();
		long ef = in.readUnsignedShort();
		long gh = in.readUnsignedShort();
		return (gh << 48) + (ef << 32) + (cd << 16) + ab;
	}

	public static double readSI64BG(ByteBuf in) {
		long ba = in.readUnsignedShortLE();
		long dc = in.readUnsignedShortLE();
		long fe = in.readUnsignedShortLE();
		long hg = in.readUnsignedShortLE();
		return (ba << 48) + (dc << 32) + (fe << 16) + hg;
	}

	public static double readSI64HA(ByteBuf in) {
		return in.readLongLE();
	}

	public static double readFP32AD(ByteBuf in) {
		return in.readFloat();
	}

	public static double readFP32CB(ByteBuf in) {
		return Float.intBitsToFloat((int) readSI32CB(in));
	}

	public static double readFP32BC(ByteBuf in) {
		return Float.intBitsToFloat((int) readSI32BC(in));
	}

	public static double readFP32DA(ByteBuf in) {
		return in.readFloatLE();
	}

	public static double readFP64AH(ByteBuf in) {
		return in.readDouble();
	}

	public static double readFP64GB(ByteBuf in) {
		return Double.longBitsToDouble((long) readSI64GB(in));
	}

	public static double readFP64BG(ByteBuf in) {
		return Double.longBitsToDouble((long) readSI64BG(in));
	}

	public static double readFP64HA(ByteBuf in) {
		return in.readDoubleLE();
	}

	public static void writeSI8(ByteBuf out, Double value) {
		out.writeByte(value.byteValue());
	}

	public static void writeUI8(ByteBuf out, Double value) {
		writeSI8(out, value);
	}

	public static void writeSI16AB(ByteBuf out, Double value) {
		out.writeShort(value.shortValue());
	}

	public static void writeUI16AB(ByteBuf out, Double value) {
		writeSI16AB(out, value);
	}

	public static void writeSI16BA(ByteBuf out, Double value) {
		out.writeShortLE(value.shortValue());
	}

	public static void writeUI16BA(ByteBuf out, Double value) {
		writeSI16BA(out, value);
	}

	public static void writeSI32AD(ByteBuf out, Double value) {
		out.writeInt(value.intValue());
	}

	public static void writeSI32CB(ByteBuf out, Double value) {
		int v = value.intValue();
		int a = (v >> 16) & 0xffff;
		int b = v & 0xffff;
		out.writeShort(b);
		out.writeShort(a);
	}

	public static void writeSI32BC(ByteBuf out, Double value) {
		int v = value.intValue();
		int a = (v >> 16) & 0xffff;
		int b = v & 0xffff;
		out.writeShortLE(a);
		out.writeShortLE(b);
	}

	public static void writeSI32DA(ByteBuf out, Double value) {
		out.writeIntLE(value.intValue());
	}

	public static void writeUI32AD(ByteBuf out, Double value) {
		writeSI32AD(out, value);
	}

	public static void writeUI32CB(ByteBuf out, Double value) {
		writeSI32CB(out, value);
	}

	public static void writeUI32BC(ByteBuf out, Double value) {
		writeSI32BC(out, value);
	}

	public static void writeUI32DA(ByteBuf out, Double value) {
		writeSI32DA(out, value);
	}

	public static void writeSI64AH(ByteBuf out, Double value) {
		out.writeLong(value.longValue());
	}

	public static void writeSI64GB(ByteBuf out, Double value) {
		long v = value.longValue();
		int gh = (int) ((v >> 0) & 0xffff);
		int ef = (int) ((v >> 16) & 0xffff);
		int cd = (int) ((v >> 32) & 0xffff);
		int ab = (int) ((v >> 48) & 0xffff);

		out.writeShort(gh);
		out.writeShort(ef);
		out.writeShort(cd);
		out.writeShort(ab);
	}

	public static void writeSI64BG(ByteBuf out, Double value) {
		long v = value.longValue();
		int gh = (int) ((v >> 0) & 0xffff);
		int ef = (int) ((v >> 16) & 0xffff);
		int cd = (int) ((v >> 32) & 0xffff);
		int ab = (int) ((v >> 48) & 0xffff);

		out.writeShortLE(ab);
		out.writeShortLE(cd);
		out.writeShortLE(gh);
		out.writeShortLE(ef);
	}

	public static void writeSI64HA(ByteBuf out, Double value) {
		out.writeLongLE(value.longValue());
	}

	public static void writeFP32AD(ByteBuf out, Double value) {
		out.writeFloat(value.floatValue());
	}

	public static void writeFP32CB(ByteBuf out, Double value) {
		int iv = Float.floatToIntBits(value.floatValue());
		int a = (iv >> 16) & 0xffff;
		int b = iv & 0xffff;
		out.writeShort(b);
		out.writeShort(a);
	}

	public static void writeFP32BC(ByteBuf out, Double value) {
		int iv = Float.floatToIntBits(value.floatValue());
		int a = (iv >> 16) & 0xffff;
		int b = iv & 0xffff;
		out.writeShortLE(a);
		out.writeShortLE(b);
	}

	public static void writeFP32DA(ByteBuf out, Double value) {
		out.writeFloatLE(value.floatValue());
	}

	public static void writeFP64AH(ByteBuf out, Double value) {
		out.writeDouble(value);
	}

	public static void writeFP64GB(ByteBuf out, Double value) {
		long lv = Double.doubleToRawLongBits(value);
		int gh = (int) ((lv >> 0) & 0xffff);
		int ef = (int) ((lv >> 16) & 0xffff);
		int cd = (int) ((lv >> 32) & 0xffff);
		int ab = (int) ((lv >> 48) & 0xffff);

		out.writeShort(gh);
		out.writeShort(ef);
		out.writeShort(cd);
		out.writeShort(ab);
	}

	public static void writeFP64BG(ByteBuf out, Double value) {
		long lv = Double.doubleToRawLongBits(value);
		int gh = (int) ((lv >> 0) & 0xffff);
		int ef = (int) ((lv >> 16) & 0xffff);
		int cd = (int) ((lv >> 32) & 0xffff);
		int ab = (int) ((lv >> 48) & 0xffff);
		
		out.writeShortLE(ab);
		out.writeShortLE(cd);
		out.writeShortLE(ef);
		out.writeShortLE(gh);
	}

	public static void writeFP64HA(ByteBuf out, Double value) {
		out.writeDoubleLE(value);
	}
}
