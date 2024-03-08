package com.onionsoftware.mk119.commtestsuite.io.netty.generic;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;

public class TXField implements Cloneable {
	private String field_id;
	private TXFieldType field_type;
	private DataType data_type;
	private String value;
	private String iteration_count;

	public String getField_id() {
		return field_id;
	}

	public void setField_id(String field_id) {
		this.field_id = field_id;
	}

	public TXFieldType getField_type() {
		return field_type;
	}

	public void setField_type(TXFieldType field_type) {
		this.field_type = field_type;
	}

	public DataType getData_type() {
		return data_type;
	}

	public void setData_type(DataType data_type) {
		this.data_type = data_type;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public String getIteration_count() {
		return iteration_count;
	}

	public void setIteration_count(String iteration_count) {
		this.iteration_count = iteration_count;
	}

	@Override
	public Object clone() {
		try {
			Object clone = super.clone();
			clone = new TXField();
			TXField ret = (TXField) clone;
			ret.field_id = this.field_id;
			ret.field_type = this.field_type;
			ret.data_type = this.data_type;
			ret.iteration_count = this.iteration_count;
			ret.value = this.value;
			return ret;
		} catch (CloneNotSupportedException e) {
			throw new RuntimeException(e);
		}
	}
}
