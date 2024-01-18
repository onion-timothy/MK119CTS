package com.onionsoftware.mk119.commtestsuite.io.packet;

import com.onionsoftware.mk119.commtestsuite.io.generic.RXField;

import java.util.LinkedList;
import java.util.List;

public class GenericTCPUDPPacket {
	private String rx_id;
	private List<RXField> fields;

	public GenericTCPUDPPacket(String rx_id) {
		setRx_id(rx_id);
		setFields(new LinkedList<RXField>());
	}

	public String getRx_id() {
		return rx_id;
	}

	public void setRx_id(String rx_id) {
		this.rx_id = rx_id;
	}

	public List<RXField> getFields() {
		return fields;
	}

	public void setFields(List<RXField> fields) {
		this.fields = fields;
	}
	
	public void addField(RXField field) {
		this.fields.add(field);
	}
}
