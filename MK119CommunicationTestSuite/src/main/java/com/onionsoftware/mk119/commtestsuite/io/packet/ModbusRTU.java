package com.onionsoftware.mk119.commtestsuite.io.packet;

public class ModbusRTU extends Modbus {

    private short crc;

    public short getCrc() {
        return this.crc;
    }

    public void setCrc(short crc) {
        this.crc = crc;
    }
}
