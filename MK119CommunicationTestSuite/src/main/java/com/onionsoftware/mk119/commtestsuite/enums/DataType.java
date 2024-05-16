package com.onionsoftware.mk119.commtestsuite.enums;

public enum DataType {
    UI8(1),
    SI8(2),
    UI16AB(3),
    UI16BA(4),
    SI16AB(5),
    SI16BA(6),
    UI32AD(7),    // A B C D
    UI32CB(8),    // C D A B
    UI32BC(9),    // B A D C
    UI32DA(10),    // D C B A
    SI32AD(11),    // A B C D
    SI32CB(12),    // C D A B
    SI32BC(13),    // B A D C
    SI32DA(14),    // D C B A
    SI64AH(15),    // A B C D E F G H
    SI64GB(16),    // G H E F C D A B
    SI64BG(17),    // B A D C F E H G
    SI64HA(18),    // H G F E D C B A
    FP32AD(19),    // A B C D
    FP32CB(20),    // C D A B
    FP32BC(21),    // B A D C
    FP32DA(22),    // D C B A
    FP64AH(23),    // A B C D E F G H
    FP64GB(24),    // G H E F C D A B
    FP64BG(25),    // B A D C F E H G
    FP64HA(26);    // H G F E D C B A

    final private int value;

    DataType(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }

    public int getByteCount() {
        if (name().contains("8")) return 1;
        if (name().contains("16")) return 2;
        if (name().contains("32")) return 3;
        if (name().contains("64")) return 4;
        return 0;
    }
}
