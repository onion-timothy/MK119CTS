package com.onionsoftware.mk119.commtestsuite.enums;

public enum Transport {
    TCP(1),
    UDP(2);
    final private int value;

    Transport(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }
}
