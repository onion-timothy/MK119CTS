package com.onionsoftware.mk119.commtestsuite.io.netty.generic;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;

public class RXField implements Cloneable {
	private String field_id;
	private DataType data_type;
	private Double required_value;
	private Double value;

	public String getField_id() {
		return field_id;
	}

	public void setField_id(String field_id) {
		this.field_id = field_id;
	}

	public DataType getData_type() {
		return data_type;
	}

	public void setData_type(DataType data_type) {
		this.data_type = data_type;
	}

	public Double getRequired_value() {
		return required_value;
	}

	public void setRequired_value(Double required_value) {
		this.required_value = required_value;
	}

	public Double getValue() {
		return value;
	}

	public void setValue(Double value) {
		this.value = value;
	}

	@Override
	public Object clone() throws CloneNotSupportedException {
		RXField ret = new RXField();
		ret.field_id = this.field_id;
		ret.data_type = this.data_type;
		ret.required_value = this.required_value;
		ret.value = null;
		return ret;
	}
}
