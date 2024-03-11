package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import java.lang.reflect.Method;

@Getter
@Setter
public class XGTData implements ServerData {
    private final byte[] mblock = new byte[65536];
    private final byte[] dblock = new byte[65536];
    private final byte[] pblock = new byte[65536];
    private final byte[] kblock = new byte[65536];
    private final byte[] sblock = new byte[65536];
    private final byte[] ublock = new byte[65536];

    public void setBlockData(String blockName, DataType dataType, Double value, int startByte) throws Exception {
        Method method = XGTData.class.getMethod("get" + blockName + "block");
        byte[] block = (byte[]) method.invoke(this);
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeData(buf, dataType, value);
        writeByteBufToBlock(buf, block, startByte);
    }

    private void writeByteBufToBlock(ByteBuf buf, byte[] registers, int startByte) {
        int byteCount = buf.readableBytes();
        for (int i = 0; i < byteCount; i++) {
            registers[startByte++] = buf.readByte();
        }
    }
}
