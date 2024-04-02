package com.onionsoftware.mk119.commtestsuite.io.netty.packet.bacnet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service("bacnetPacketFactory")
public class BACnetPacketFactory {
    @Bean
    @Scope("prototype")
    public BACnetReadProperty bacnetReadProperty(BACnetPacket packet) {
        return new BACnetReadProperty(packet);
    }

    @Bean
    @Scope("prototype")
    public BACnetReadPropertyMultiple bacnetReadPropertyMultiple(BACnetPacket packet) {
        return new BACnetReadPropertyMultiple(packet);
    }

    @Bean
    @Scope("prototype")
    public BACnetWriteProperty bacnetWriteProperty(BACnetPacket packet) {
        return new BACnetWriteProperty(packet);
    }
}
