package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import java.lang.reflect.Method;
import java.util.Arrays;

@Getter
@Setter
public class XGTData implements ServerData {
    private byte[] mblock;
    private byte[] dblock;
    private byte[] pblock;
    private byte[] kblock;
    private byte[] sblock;
    private byte[] ublock;

    public XGTData(int registerCount){
        mblock = new byte[registerCount];
        dblock = new byte[registerCount];
        pblock = new byte[registerCount];
        kblock = new byte[registerCount];
        sblock = new byte[registerCount];
        ublock = new byte[registerCount];
    }

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

    @Override
    public void resetServerData() {
        Arrays.fill(mblock, (byte) 0);
        Arrays.fill(dblock, (byte) 0);
        Arrays.fill(pblock, (byte) 0);
        Arrays.fill(kblock, (byte) 0);
        Arrays.fill(sblock, (byte) 0);
        Arrays.fill(ublock, (byte) 0);
    }
}
