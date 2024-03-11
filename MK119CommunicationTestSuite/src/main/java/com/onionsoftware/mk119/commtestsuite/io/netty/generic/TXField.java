package com.onionsoftware.mk119.commtestsuite.io.netty.generic;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TXField implements Cloneable {
    private String field_id;
    private TXFieldType field_type;
    private DataType data_type;
    private String value;
    private String iteration_count;

    @Override
    public TXField clone() {
        try {
            Object obj = super.clone();
            TXField ret = (TXField) obj;
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
