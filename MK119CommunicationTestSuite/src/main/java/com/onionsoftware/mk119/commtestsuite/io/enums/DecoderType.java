package com.onionsoftware.mk119.commtestsuite.io.enums;

public enum DecoderType {
	MODBUS_RTU(1), 
	MODBUS_TCP(2), 
	XGT(3), 
	DAEYUN(4);

	final private int value;

	DecoderType(int value) {
		this.value = value;
	}

	public int value() {
		return this.value;
	}

}
