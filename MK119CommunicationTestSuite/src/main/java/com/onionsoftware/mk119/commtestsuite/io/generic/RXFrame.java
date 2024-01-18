package com.onionsoftware.mk119.commtestsuite.io.generic;

import java.util.List;

public class RXFrame {
	private String rx_id;
	private Integer length;
	private List<RXField> fields;

	public String getRx_id() {
		return rx_id;
	}

	public void setRx_id(String rx_id) {
		this.rx_id = rx_id;
	}

	public Integer getLength() {
		return length;
	}

	public void setLength(Integer length) {
		this.length = length;
	}

	public List<RXField> getFields() {
		return fields;
	}

	public void setFields(List<RXField> fields) {
		this.fields = fields;
	}
}
