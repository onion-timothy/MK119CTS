package com.onionsoftware.mk119.commtestsuite.io.netty.generic;

public enum TXFieldType {
    STATIC(1),
    REFERENCE(2),
    SIMULATION(3),
    ITERATION(4),
    CRC16(5);

    final private int value;

    TXFieldType(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }
}
