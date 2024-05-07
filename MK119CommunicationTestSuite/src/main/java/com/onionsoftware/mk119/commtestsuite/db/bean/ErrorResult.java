package com.onionsoftware.mk119.commtestsuite.db.bean;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErrorResult {
    public static final String RESULT_TYPE_TIMEOUT = "timeout";
    public static final String RESULT_TYPE_DISCARD = "discard";
    public static final String RESULT_TYPE_GARBAGE = "garbage";
    public static final String RESULT_TYPE_DISCONNECT = "disconnect";

    private String resultType;
    private ByteBuf bufToWrite;
}
