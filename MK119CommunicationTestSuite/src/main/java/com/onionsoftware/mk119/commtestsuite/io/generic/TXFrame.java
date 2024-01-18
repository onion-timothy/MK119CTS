package com.onionsoftware.mk119.commtestsuite.io.generic;

import java.util.List;

public class TXFrame {
	private String tx_id;
	private List<TXField> fields;

	public String getTx_id() {
		return tx_id;
	}

	public void setTx_id(String tx_id) {
		this.tx_id = tx_id;
	}

	public List<TXField> getFields() {
		return fields;
	}

	public void setFields(List<TXField> fields) {
		this.fields = fields;
	}
}
