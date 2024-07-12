package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServer;
import etherip.protocol.Encapsulation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

@Component("ethernet_ip_decoder_tcp")
@Scope("prototype")
public class EthernetIPDecoder extends MkDecoder {
    int cpuSlotNumber;
    ByteBuffer brokenBuffer = null;

    public EthernetIPDecoder(IOServer ioServer) {
        super(ioServer);
        cpuSlotNumber = Integer.parseInt(ioServer.getDevice().getCommunicationSettings().get("cpuSlot").toString());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decode(ctx, in, out);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        try {
            ByteBuffer rx = in.nioBuffer();
            if (brokenBuffer != null) {
                buffer.put(brokenBuffer);
                brokenBuffer = null;
            }
            buffer.put(rx);
            buffer.flip();
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            Encapsulation encapsulation = Encapsulation.fromBuffer(buffer);
            out.add(encapsulation);
        } catch (Exception e) {
            buffer.rewind();
            brokenBuffer = buffer;
        } finally {
            in.clear();
        }
    }
}
