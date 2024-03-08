package com.onionsoftware.mk119.commtestsuite.enums;

public enum Policy {
    STATIC(1),
    RANDOM(2),
    ACCUMULATE(3);
    final private int value;

    Policy(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }
}
