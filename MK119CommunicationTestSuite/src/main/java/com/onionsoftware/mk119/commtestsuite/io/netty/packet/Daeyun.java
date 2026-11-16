package com.onionsoftware.mk119.commtestsuite.io.netty.packet;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Daeyun {
    private byte address;
    private short function;
    private int length;
    /** RS(0x0A) 시리얼 중계 본문. 프로토콜 정의 엔진이 해석한다 (docs/PROTOCOL_ENGINE_DESIGN.md 10.2) */
    private byte[] payload;
    private int port;
    private short cardAddress;
    private short inputChannelCount;
    private short DOTarget;
    private int DOParam;

    private int heartBeatSeuqnce;

    public static final byte CPU_ADDRESS = 0x01;
    public static final byte DO_FUNCTION = 0x05;
    public static final byte DI_FUNCTION = 0x02;
    public static final byte AI_FUNCTION = 0x03;
    public static final byte RS_FUNCTION = 0x0A;
    public static final int VI_FUNCTION = 254; // Version Info
    public static final int HB_FUNCTION = 255; // Heart Beat
}
