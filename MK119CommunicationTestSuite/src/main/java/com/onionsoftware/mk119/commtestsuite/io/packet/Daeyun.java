package com.onionsoftware.mk119.commtestsuite.io.packet;

public class Daeyun {
	private byte address;
	private byte function;
	private int length;
	private byte[] payload;
	private int port;
	private short cardAddress;
	private short inputChannelCount;
	private short DOTarget;
	private short DOParam;

	public int getLength() {
		return length;
	}

	public void setLength(int length) {
		this.length = length;
	}

	public byte[] getPayload() {
		return payload;
	}

	public void setPayload(byte[] payload) {
		this.payload = payload;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public byte getFunction() {
		return function;
	}

	public void setFunction(byte function) {
		this.function = function;
	}

	public byte getAddress() {
		return address;
	}

	public void setAddress(byte address) {
		this.address = address;
	}

	public short getDOTarget() {
		return DOTarget;
	}

	public void setDOTarget(short dOTarget) {
		DOTarget = dOTarget;
	}

	public short getDOParam() {
		return DOParam;
	}

	public void setDOParam(short dOParam) {
		DOParam = dOParam;
	}

	public short getInputChannelCount() {
		return inputChannelCount;
	}

	public void setInputChannelCount(short inputChannelCount) {
		this.inputChannelCount = inputChannelCount;
	}

	public short getCardAddress() {
		return cardAddress;
	}

	public void setCardAddress(short cardAddress) {
		this.cardAddress = cardAddress;
	}
}
