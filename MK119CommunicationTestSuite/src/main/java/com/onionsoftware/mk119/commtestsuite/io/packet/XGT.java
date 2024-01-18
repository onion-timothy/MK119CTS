package com.onionsoftware.mk119.commtestsuite.io.packet;

public class XGT {
	private int source;
	private int invokeId;
	private int length;
	private int code;
	private int dataType;
	private String variable;
	private int blockSize;

	public int getLength() {
		return length;
	}

	public void setLength(int length) {
		this.length = length;
	}

	public int getInvokeId() {
		return invokeId;
	}

	public void setInvokeId(int invokeId) {
		this.invokeId = invokeId;
	}

	public int getCode() {
		return code;
	}

	public void setCode(int code) {
		this.code = code;
	}

	public int getDataType() {
		return dataType;
	}

	public void setDataType(int dataType) {
		this.dataType = dataType;
	}

	public String getVariable() {
		return variable;
	}

	public void setVariable(String variable) {
		this.variable = variable;
	}

	public int getBlockSize() {
		return blockSize;
	}

	public void setBlockSize(int blockSize) {
		this.blockSize = blockSize;
	}

	public int getSource() {
		return source;
	}

	public void setSource(int source) {
		this.source = source;
	}
}
