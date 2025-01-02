package com.onionsoftware.mk119.commtestsuite.io.netty.tool;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.xml.bind.DatatypeConverter;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXField;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.RXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.TXField;
import com.onionsoftware.mk119.commtestsuite.io.netty.generic.TXFrame;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.*;
import com.onionsoftware.mk119.commtestsuite.web.bean.LogLevel;
import com.onionsoftware.mk119.commtestsuite.web.bean.MkLogger;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ByteBufUtils {
    public static void clear(ByteBuf in) {
        while (in.isReadable()) {
            in.readByte();
        }
    }

    public static void writeRXPacketLog(ByteBuf in, String className, Logger logger) {
        byte[] orig = new byte[in.readableBytes()];

        for (int i = 0; i < orig.length; i++)
            orig[i] = in.getByte(i);

        if(logger != null)
            logger.info("[{}] RX : [{}] {}", className, orig.length, DatatypeConverter.printHexBinary(orig));
    }

    public static void writeTXPacketLog(ByteBuf in, String className, Logger logger) {
        byte[] orig = new byte[in.readableBytes()];
        in.getBytes(0, orig);
        logger.info("[{}] TX : [{}] {}", className, orig.length, DatatypeConverter.printHexBinary(orig));
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
        return ((long) readSI32CB(in)) & 0xffffffffL;
    }

    public static double readUI32BC(ByteBuf in) {
        return ((long) readSI32BC(in)) & 0xffffffffL;
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
        int gh = (int) ((v) & 0xffff);
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
        int gh = (int) ((v) & 0xffff);
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
        int gh = (int) ((lv) & 0xffff);
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
        int gh = (int) ((lv) & 0xffff);
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

    public static byte[] printDataWithType(Double value, DataType type) {
        ByteBuf out = Unpooled.buffer();
        String methodName = "write" + type.name();
        try {
            Method method = ByteBufUtils.class.getMethod(methodName, ByteBuf.class, Double.class);
            method.invoke(null, out, value);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            //logger.error("method invoke failed.", e);
        }
        byte[] ret = null;
        int byteCount = 0;
        if (type.name().contains("8")) {
            byteCount = 1;
        } else if (type.name().contains("16")) {
            byteCount = 2;
        } else if (type.name().contains("32")) {
            byteCount = 4;
        } else if (type.name().contains("64")) {
            byteCount = 8;
        }
        if (byteCount != 0) {
            ret = new byte[byteCount];
            for (int i = 0; i < byteCount; i++) {
                ret[i] = out.getByte(i);
            }
        }
        return ret;
    }

    static ScriptEngine engine;

    static {
        ScriptEngineManager manager = new ScriptEngineManager();
        engine = manager.getEngineByName("js");
    }

    public static void _writeTXPacketByFrame(TXFrame frame, ByteBuf out, GenericTCPUDPPacket packet) {
        // 필드에 따른 버퍼 쓰기
        int length = calulateTXLength(frame.getFields());
        try {
            for (TXField field : frame.getFields()) {
                switch (field.getField_type()) {
                    case STATIC:
                        writeData(out, field.getData_type(), Double.parseDouble(field.getValue()));
                        break;
                    case REFERENCE:
                        writeData(out, field.getData_type(), evalRefField(packet, field.getValue()));
                        break;
                    case SIMULATION:
                        Double value = packet.getGenericData().getTxFieldsIdValueMap().get(field.getField_id());
                        if (value == null) value = 0d;
                        writeData(out, field.getData_type(), value);
                        break;
                    case CRC16:
                        int start = (int) Double.parseDouble(field.getValue());
                        byte[] toCRC = new byte[length - 2 - start];
                        out.getBytes(start, toCRC, 0, length - 2 - start);
                        short crc = CRC16.getCRC16(toCRC);
                        out.writeShort(crc);
                        break;
                }
            }
        } catch (Exception e) {
            MkLogger.log("io", e, "Generic Packet generation error.");
        }
    }

    public static double evalRefField(GenericTCPUDPPacket packet, String ref) {
        double ret = Double.NaN;
        Pattern pattern = Pattern.compile("@[a-zA-Z_]+");
        Matcher matcher = pattern.matcher(ref);
        while (matcher.find()) {
            String token = matcher.group(0);
            String id = token.replaceAll("@", "");
            Optional<RXField> optrx = packet.getFields().stream().filter(e -> e.getField_id().equals(id)).findFirst();
            if (optrx.isPresent()) {
                RXField field = optrx.get();
                ref = ref.replaceAll(token, Double.toString(field.getValue()));
            }
        }

        try {
            ret = (double) engine.eval(ref);
        } catch (ScriptException e1) {
            MkLogger.log("io", e1, "Field evaluation failed.");
        }
        return ret;
    }

    public static void writeData(ByteBuf out, DataType type, Double value) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        String methodName = "write" + type.name();
        Method method = ByteBufUtils.class.getMethod(methodName, ByteBuf.class, Double.class);
        method.invoke(null, out, value);
    }

    public static int calulateTXLength(List<TXField> fields) {
        int ret = 0;
        for (TXField field : fields) {
            int temp = 0;
            switch (field.getData_type()) {
                case SI8:
                case UI8:
                    temp++;
                    break;
                case UI16AB:
                case UI16BA:
                case SI16AB:
                case SI16BA:
                    temp += 2;
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
                    temp += 4;
                    break;
                case SI64AH:
                case SI64GB:
                case SI64BG:
                case SI64HA:
                case FP64AH:
                case FP64GB:
                case FP64BG:
                case FP64HA:
                    temp += 8;
                    break;
            }
            ret += temp;

        }
        return ret;
    }

    static Gson parser = new Gson();

    public static GenericTCPUDPPacket parsePacket(RXFrame frame, ByteBuf in) {
        GenericTCPUDPPacket ret;
        if (frame.getLength() != null && frame.getLength() != in.readableBytes()) {
            MkLogger.log("io", LogLevel.ERROR, "Designated length not matched for generic packet. {0}", parser.toJson(frame));
            return null;
        }
        try {
            ret = new GenericTCPUDPPacket(frame.getRx_id());
            for (RXField field : frame.getFields()) {
                String methodName = "read" + field.getData_type().name();
                Method method = ByteBufUtils.class.getMethod(methodName, ByteBuf.class);
                RXField newFieldObj = field.clone();
                newFieldObj.setValue((Double) method.invoke(null, in));

                if (field.getField_id().equals("CRC16") && field.getData_type() == DataType.SI16AB) {
                    int crcByteCount = in.readerIndex() - 2;
                    byte[] cpy = new byte[crcByteCount];
                    in.getBytes(0, cpy, 0, crcByteCount);
                    short crc16 = CRC16.getCRC16(cpy);
                    if (crc16 != (short) newFieldObj.getValue().doubleValue()) {
                        MkLogger.log("io", LogLevel.ERROR, "CRC doesn't match for generic packet. {0}", parser.toJson(frame));
                        return null;
                    }
                }
                if (!methodName.contains("FP") && newFieldObj.getRequired_value() != null && !newFieldObj.getRequired_value().equals(newFieldObj.getValue())) {
                    MkLogger.log("io", LogLevel.ERROR, "Designated value not matched for generic packet. {0}", parser.toJson(frame));
                    return null;
                }
                ret.getFields().add(newFieldObj);
            }
        } catch (Exception e) {
            MkLogger.log("io", e, "Cannot parse generic packet. {0}", parser.toJson(frame));
            return null;
        }

        if (in.isReadable()) {
            MkLogger.log("io", LogLevel.ERROR, "Buffer remaining after parse generic packet. {0}", parser.toJson(frame));
            return null;
        }

        return ret;
    }

    public static OPCAgent parseOPCAgentPacket(ByteBuf in) {
        OPCAgent agent = new OPCAgent();
        agent.setProtocolHeader(in.readByte());
        agent.setStart(in.readUnsignedShort());
        if (agent.getProtocolHeader() == 0 && in.readableBytes() == 0) {
            return agent;
        } else {
            MkLogger.log("io", LogLevel.ERROR, "OPC Agent packet parse error. {0} {1}", agent.getProtocolHeader(), agent.getStart());
            return null;
        }
    }

    public static Daeyun parseDaeyunPacket(ByteBuf in, List<RXFrame> acceptFrames) {
        byte[] _cpyBuf = new byte[in.readableBytes() - 2];
        in.getBytes(0, _cpyBuf, 0, in.readableBytes() - 2);
        short _crc = CRC16.getCRC16(_cpyBuf);

        Daeyun daeyun = new Daeyun();
        daeyun.setAddress(in.readByte());
        if (daeyun.getAddress() == Daeyun.CPU_ADDRESS) {
            daeyun.setFunction(in.readUnsignedByte());
            if (daeyun.getFunction() == Daeyun.AI_FUNCTION || daeyun.getFunction() == Daeyun.DI_FUNCTION) {
                // AI & DI
                daeyun.setCardAddress(in.readShort());
                if (daeyun.getCardAddress() != 0x0000) {
                    MkLogger.log("io", LogLevel.ERROR, "DaeyunRCU wrong card address. {0}", daeyun.getCardAddress());
                    return null;
                }
                daeyun.setInputChannelCount(in.readShort());
            } else if (daeyun.getFunction() == Daeyun.VI_FUNCTION) {
                // Version
                in.readByte();
            } else if (daeyun.getFunction() == Daeyun.HB_FUNCTION) {
                in.readByte();
                daeyun.setHeartBeatSeuqnce(in.readByte());

                return daeyun;
            } else if (daeyun.getFunction() == Daeyun.DO_FUNCTION) {
                // DO
                daeyun.setDOTarget(in.readShort());
                daeyun.setDOParam(in.readUnsignedShort());
                if (daeyun.getDOParam() != 0xff00 && daeyun.getDOParam() != 0x0000) {
                    MkLogger.log("io", LogLevel.ERROR, "DaeyunRCU wrong DO parameter. {0}", daeyun.getDOParam());
                    return null;
                }
            } else if (daeyun.getFunction() == Daeyun.RS_FUNCTION && acceptFrames != null) {
                // Serial
                daeyun.setPort(in.readUnsignedShort());
                daeyun.setLength(in.readUnsignedShort());
                byte[] payload = new byte[daeyun.getLength()];
                in.readBytes(payload);

                ByteBuf temp = Unpooled.copiedBuffer(payload);
                GenericTCPUDPPacket payloadPacket = null;
                for (RXFrame frame : acceptFrames) {
                    payloadPacket = ByteBufUtils.parsePacket(frame, temp);
                    if (payloadPacket == null) {
                        temp.resetReaderIndex();
                    } else break;
                }
                if (payloadPacket != null) {
                    daeyun.setPacket(payloadPacket);
                } else {
                    MkLogger.log("io", LogLevel.ERROR, "DaeyunRCU RS not matched generic parser.");
                    return null;
                }
            } else if (daeyun.getFunction() == Daeyun.RS_FUNCTION) {
                MkLogger.log("io", LogLevel.ERROR, "DaeyunRCU RS no generic parser.");
                return null;
            } else {
                MkLogger.log("io", LogLevel.ERROR, "DaeyunRCU Invalid function. {0}", daeyun.getFunction());
                return null;
            }

            short crcReceived = in.readShort();
            if (_crc != crcReceived) {
                MkLogger.log("io", LogLevel.ERROR, "DaeyunRCU CRC Error.");
                return null;
            }
            return daeyun;
        } else {
            MkLogger.log("io", LogLevel.ERROR, "DaeyunRCU Invalid address. {0}", daeyun.getAddress());
            return null;
        }
    }

    public static XGT parseXGTPacket(ByteBuf in) {
        byte[] buf = new byte[8];
        in.getBytes(0, buf, 0, buf.length);
        String cid = new String(buf);

        if (cid.equals("LSIS-XGT")) {
            XGT xgt = new XGT();
            in.skipBytes(13);
            xgt.setSource(in.readByte());
            if (xgt.getSource() != 0x33) {
                MkLogger.log("io", LogLevel.ERROR, "XGTPLC Invalid source. {0}", xgt.getSource());
                return null;
            }
            xgt.setInvokeId(in.readShortLE());
            xgt.setLength(in.readShortLE());
            in.skipBytes(2);

            xgt.setCode(in.readShortLE());
            if (xgt.getCode() != 0x54 && xgt.getCode() != 0x58) {
                MkLogger.log("io", LogLevel.ERROR, "XGTPLC Invalid function code. {0}", xgt.getCode());
                return null;
            }

            xgt.setDataType(in.readShortLE());
            if (xgt.getCode() == 0x54) {
                if (xgt.getDataType() != 0x14) {
                    MkLogger.log("io", LogLevel.ERROR, "XGTPLC Invalid data type. {0}", xgt.getDataType());
                    return null;
                }

                in.skipBytes(4);

                byte[] varBuff = new byte[in.readShortLE()];
                in.readBytes(varBuff);
                xgt.setVariable(new String(varBuff));
                xgt.setBlockSize(in.readShortLE());
            } else {
                in.skipBytes(2);
                xgt.setVar_count(in.readShortLE());
                xgt.setVariableList(new ArrayList<>(xgt.getVar_count()));
                for (int i = 0; i < xgt.getVar_count(); i++) {
                    XGTVariable variable = new XGTVariable();
                    variable.setVarname_length(in.readShortLE());
                    byte[] nameBuffer = new byte[variable.getVarname_length()];
                    in.readBytes(nameBuffer);
                    variable.setVarname(new String(nameBuffer));
                    xgt.getVariableList().add(variable);
                }

                for (int i = 0; i < xgt.getVar_count(); i++) {
                    XGTVariable variable = xgt.getVariableList().get(i);
                    in.skipBytes(2);
                    variable.setValue(in.readShortLE());
                }

            }
            return xgt;
        } else {
            MkLogger.log("io", LogLevel.ERROR, "XGTPLC Invalid header. {0}", cid);
            return null;
        }

    }

    public static Modbus parseModbusPacket(ByteBuf in, int uid, int type) {
        if (type == Modbus.MODBUS_TCP) {
            ModbusTCP modbusTcp = new ModbusTCP();
            modbusTcp.setTid(in.readShort());
            modbusTcp.setPid(in.readShort());
            if (modbusTcp.getPid() != ModbusTCP.PROTOCOL_ID) {
                MkLogger.log("io", LogLevel.ERROR, "Modbus Invalid PID. {0}", modbusTcp.getPid());
                return null;
            }

            modbusTcp.setTcpLength(in.readShort());
            /*
            if (modbusTcp.getTcpLength() != 6) {
                MkLogger.log("io", LogLevel.ERROR, "Modbus Invalid tcp length. {0}", modbusTcp.getTcpLength());
                return null;
            }
             */

            modbusTcp.setUid(in.readByte());
            if (uid != 0 && modbusTcp.getUid() != uid) {
                MkLogger.log("io", LogLevel.ERROR, "Modbus Invalid UID. {0}", modbusTcp.getUid());
                return null;
            }

            modbusTcp.setFc(in.readByte());
            if (!Modbus.SUPPORTED_FUNCTIONS.contains((int) modbusTcp.getFc())) {
                MkLogger.log("io", LogLevel.ERROR, "Modbus Invalid function code. {0}", modbusTcp.getFc());
                return null;
            }

            modbusTcp.setRegister(in.readUnsignedShort());
            if(modbusTcp.getFc() == Modbus.FC_16) {
                int paramSize_register = in.readShort();
                int paramSize_byte = in.readByte();
                if(paramSize_byte == paramSize_register * 2) {
                    byte[] parameter = new byte[paramSize_byte];
                    in.readBytes(parameter);
                    modbusTcp.setParamter(parameter);
                } else {
                    MkLogger.log("io", LogLevel.ERROR, "Modbus FC16 invalid paramter size. {0}, {1}", paramSize_register, paramSize_byte);
                    return null;
                }
            } else {
                modbusTcp.setRequestSize(in.readShort());
            }
            return modbusTcp;
        } else if (type == Modbus.MODBUS_RTU) {
            ModbusRTU modbusRtu = new ModbusRTU();
            byte[] cpyBuf = new byte[6];
            in.getBytes(0, cpyBuf, 0, 6);
            short crc = CRC16.getCRC16(cpyBuf);

            modbusRtu.setUid(in.readByte());
            if (uid != 0 && modbusRtu.getUid() != uid) {
                MkLogger.log("io", LogLevel.ERROR, "Modbus Invalid UID. {0}", modbusRtu.getUid());
                return null;
            }

            modbusRtu.setFc(in.readByte());
            if (!Modbus.SUPPORTED_FUNCTIONS.contains((int) modbusRtu.getFc())) {
                MkLogger.log("io", LogLevel.ERROR, "Modbus Invalid function code. {0}", modbusRtu.getFc());
                return null;
            }
            modbusRtu.setRegister(in.readShort());
            modbusRtu.setRequestSize(in.readShort());
            modbusRtu.setCrc(in.readShort());
            if (crc != modbusRtu.getCrc()) {
                MkLogger.log("io", LogLevel.ERROR, "Modbus CRC Error.");
                return null;
            }
            return modbusRtu;
        }
        return null;
    }
}
