package com.onionsoftware.mk119.commtestsuite.io.enums;

public enum TXFieldType {
	STATIC		(1),
	REF			(2),
	ITERATION	(3);
	
	final private int value;
	TXFieldType(int value) {
		this.value = value;
	}

	public int value() {
		return this.value;
	}
}
