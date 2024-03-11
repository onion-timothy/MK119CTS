package com.onionsoftware.mk119.commtestsuite.io.netty.generic;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RXField implements Cloneable {
    private String field_id;
    private DataType data_type;
    private Double required_value;
    private Double value;

    @Override
    public RXField clone() throws CloneNotSupportedException {
        Object obj = super.clone();
        RXField ret = (RXField) obj;
        ret.field_id = this.field_id;
        ret.data_type = this.data_type;
        ret.required_value = this.required_value;
        ret.value = null;
        return ret;
    }
}
