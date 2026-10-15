package com.onionsoftware.mk119.commtestsuite.enums;

public enum ErrorMode {
    DISCARD(0),
    TIMEOUT(1),
    GARBAGE(2),
    DISCONNECT(3),
    SIMULATION(4);

    final private int value;

    ErrorMode(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }
}
