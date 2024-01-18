package com.onionsoftware.mk119.commtestsuite.io.packet;

public class ModbusTCP extends Modbus {
    private short tid;
    private short pid;
    private short tcpLength;

    public short getTid() {
        return tid;
    }

    public void setTid(short tid) {
        this.tid = tid;
    }

    public short getPid() {
        return pid;
    }

    public void setPid(short pid) {
        this.pid = pid;
    }

    public short getTcpLength() {
        return tcpLength;
    }

    public void setTcpLength(short length) {
        this.tcpLength = length;
    }

    public static final short PROTOCOL_ID = 0x0000;
}
