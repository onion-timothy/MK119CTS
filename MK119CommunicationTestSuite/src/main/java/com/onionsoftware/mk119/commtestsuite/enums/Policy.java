package com.onionsoftware.mk119.commtestsuite.enums;

public enum Policy {
    STATIC(1),
    RANDOM(2),
    ACCUMULATE(3),
    /** value 에 담긴 숫자 배열을 interval 마다 한 칸씩 진행하고, 끝에 닿으면 처음으로 되돌아간다. */
    SEQUENCE(4);
    final private int value;

    Policy(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }
}
