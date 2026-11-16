package com.onionsoftware.mk119.commtestsuite.io.netty.tool;


import org.slf4j.LoggerFactory;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.xml.bind.DatatypeConverter;

import com.google.gson.Gson;
import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.packet.*;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;

import io.netty.buffer.ByteBuf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
        writeInt32AD(out, value.intValue());
    }

    public static void writeSI32CB(ByteBuf out, Double value) {
        writeInt32CB(out, value.intValue());
    }

    public static void writeSI32BC(ByteBuf out, Double value) {
        writeInt32BC(out, value.intValue());
    }

    public static void writeSI32DA(ByteBuf out, Double value) {
        writeInt32DA(out, value.intValue());
    }

    // UI32 는 long 으로 바꾼 뒤 하위 32비트를 쓴다. 예전에는 SI32 와 같이 intValue() 를 써서
    // 2147483647 을 넘는 값이 전부 0x7FFFFFFF 로 잘렸다 (double -> int 변환은 범위 밖을 끝값으로 붙인다).
    public static void writeUI32AD(ByteBuf out, Double value) {
        writeInt32AD(out, (int) value.longValue());
    }

    public static void writeUI32CB(ByteBuf out, Double value) {
        writeInt32CB(out, (int) value.longValue());
    }

    public static void writeUI32BC(ByteBuf out, Double value) {
        writeInt32BC(out, (int) value.longValue());
    }

    public static void writeUI32DA(ByteBuf out, Double value) {
        writeInt32DA(out, (int) value.longValue());
    }

    private static void writeInt32AD(ByteBuf out, int v) {
        out.writeInt(v);
    }

    private static void writeInt32CB(ByteBuf out, int v) {
        int a = (v >> 16) & 0xffff;
        int b = v & 0xffff;
        out.writeShort(b);
        out.writeShort(a);
    }

    private static void writeInt32BC(ByteBuf out, int v) {
        int a = (v >> 16) & 0xffff;
        int b = v & 0xffff;
        out.writeShortLE(a);
        out.writeShortLE(b);
    }

    private static void writeInt32DA(ByteBuf out, int v) {
        out.writeIntLE(v);
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

    public static void writeData(ByteBuf out, DataType type, Double value) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        String methodName = "write" + type.name();
        Method method = ByteBufUtils.class.getMethod(methodName, ByteBuf.class, Double.class);
        method.invoke(null, out, value);
    }

    static Gson parser = new Gson();

    public static OPCAgent parseOPCAgentPacket(ByteBuf in) {
        OPCAgent agent = new OPCAgent();
        agent.setProtocolHeader(in.readByte());
        agent.setStart(in.readUnsignedShort());
        if (agent.getProtocolHeader() == 0 && in.readableBytes() == 0) {
            return agent;
        } else {
            LoggerFactory.getLogger("io").error("OPC Agent packet parse error. {} {}", agent.getProtocolHeader(), agent.getStart());
            return null;
        }
    }

    /** RS(0x0A) 본문은 해석하지 않고 담아 둔다 — DaeyunHandler 가 프로토콜 정의 엔진에 넘긴다 */
    public static Daeyun parseDaeyunPacket(ByteBuf in) {
        byte[] _cpyBuf = new byte[in.readableBytes() - 2];
        in.getBytes(0, _cpyBuf, 0, in.readableBytes() - 2);
        short _crc = Checksum.getCRC16(_cpyBuf);

        Daeyun daeyun = new Daeyun();
        daeyun.setAddress(in.readByte());
        if (daeyun.getAddress() == Daeyun.CPU_ADDRESS) {
            daeyun.setFunction(in.readUnsignedByte());
            if (daeyun.getFunction() == Daeyun.AI_FUNCTION || daeyun.getFunction() == Daeyun.DI_FUNCTION) {
                // AI & DI
                daeyun.setCardAddress(in.readShort());
                if (daeyun.getCardAddress() != 0x0000) {
                    LoggerFactory.getLogger("io").error("DaeyunRCU wrong card address. {}", daeyun.getCardAddress());
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
                    LoggerFactory.getLogger("io").error("DaeyunRCU wrong DO parameter. {}", daeyun.getDOParam());
                    return null;
                }
            } else if (daeyun.getFunction() == Daeyun.RS_FUNCTION) {
                // Serial — 본문은 프로토콜 정의가 해석한다
                daeyun.setPort(in.readUnsignedShort());
                daeyun.setLength(in.readUnsignedShort());
                byte[] payload = new byte[daeyun.getLength()];
                in.readBytes(payload);
                daeyun.setPayload(payload);
            } else {
                LoggerFactory.getLogger("io").error("DaeyunRCU Invalid function. {}", daeyun.getFunction());
                return null;
            }

            short crcReceived = in.readShort();
            if (_crc != crcReceived) {
                LoggerFactory.getLogger("io").error("DaeyunRCU CRC Error.");
                return null;
            }
            return daeyun;
        } else {
            LoggerFactory.getLogger("io").error("DaeyunRCU Invalid address. {}", daeyun.getAddress());
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
                LoggerFactory.getLogger("io").error("XGTPLC Invalid source. {}", xgt.getSource());
                return null;
            }
            xgt.setInvokeId(in.readShortLE());
            xgt.setLength(in.readShortLE());
            in.skipBytes(2);

            xgt.setCode(in.readShortLE());
            if (xgt.getCode() != 0x54 && xgt.getCode() != 0x58) {
                LoggerFactory.getLogger("io").error("XGTPLC Invalid function code. {}", xgt.getCode());
                return null;
            }

            xgt.setDataType(in.readShortLE());
            if (xgt.getCode() == 0x54) {
                if (xgt.getDataType() != 0x14) {
                    LoggerFactory.getLogger("io").error("XGTPLC Invalid data type. {}", xgt.getDataType());
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
            LoggerFactory.getLogger("io").error("XGTPLC Invalid header. {}", cid);
            return null;
        }

    }

    public static Modbus parseModbusPacket(ByteBuf in, int uid, int type) {
        if (type == Modbus.MODBUS_TCP) {
            ModbusTCP modbusTcp = new ModbusTCP();
            modbusTcp.setTid(in.readShort());
            modbusTcp.setPid(in.readShort());
            if (modbusTcp.getPid() != ModbusTCP.PROTOCOL_ID) {
                LoggerFactory.getLogger("io").error("Modbus Invalid PID. {}", modbusTcp.getPid());
                return null;
            }

            modbusTcp.setTcpLength(in.readShort());
            /*
            if (modbusTcp.getTcpLength() != 6) {
                LoggerFactory.getLogger("io").error("Modbus Invalid tcp length. {}", modbusTcp.getTcpLength());
                return null;
            }
             */

            modbusTcp.setUid(in.readByte());
            if (uid != 0 && modbusTcp.getUid() != uid) {
                LoggerFactory.getLogger("io").error("Modbus Invalid UID. {}", modbusTcp.getUid());
                return null;
            }

            modbusTcp.setFc(in.readByte());
            if (!Modbus.SUPPORTED_FUNCTIONS.contains((int) modbusTcp.getFc())) {
                LoggerFactory.getLogger("io").error("Modbus Invalid function code. {}", modbusTcp.getFc());
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
                    LoggerFactory.getLogger("io").error("Modbus FC16 invalid paramter size. {}, {}", paramSize_register, paramSize_byte);
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
            short crc = Checksum.getCRC16(cpyBuf);

            modbusRtu.setUid(in.readByte());
            if (uid != 0 && modbusRtu.getUid() != uid) {
                LoggerFactory.getLogger("io").error("Modbus Invalid UID. {}", modbusRtu.getUid());
                return null;
            }

            modbusRtu.setFc(in.readByte());
            if (!Modbus.SUPPORTED_FUNCTIONS.contains((int) modbusRtu.getFc())) {
                LoggerFactory.getLogger("io").error("Modbus Invalid function code. {}", modbusRtu.getFc());
                return null;
            }
            modbusRtu.setRegister(in.readShort());
            modbusRtu.setRequestSize(in.readShort());
            modbusRtu.setCrc(in.readShort());
            if (crc != modbusRtu.getCrc()) {
                LoggerFactory.getLogger("io").error("Modbus CRC Error.");
                return null;
            }
            return modbusRtu;
        }
        return null;
    }
}
