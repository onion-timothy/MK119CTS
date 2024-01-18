package com.onionsoftware.mk119.commtestsuite.io.packet;

public class Modbus {
    protected byte uid;
    protected byte fc;
    protected short register;
    protected short requestSize;

    public int getUid() {
        return uid;
    }

    public void setUid(byte uid) {
        this.uid = uid;
    }

    public byte getFc() {
        return fc;
    }

    public void setFc(byte fc) {
        this.fc = fc;
    }

    public short getRegister() {
        return register;
    }

    public void setRegister(short register) {
        this.register = register;
    }

    public short getRequestSize() {
        return requestSize;
    }

    public void setRequestSize(short requestSize) {
        this.requestSize = requestSize;
    }

    public static final int FC_01 = 1;
    public static final int FC_02 = 2;
    public static final int FC_03 = 3;
    public static final int FC_04 = 4;
}
