package com.onionsoftware.mk119.commtestsuite.io.netty.decoder;

import io.netty.channel.ChannelHandlerContext;

public interface MkDecoderInterface {
    ChannelHandlerContext getContext();
    long getRxCounter();
}
