package com.onionsoftware.mk119.commtestsuite.io.netty.generic;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class TXFrame {
    private String tx_id;
    private List<TXField> fields;

    public void translateFields(GenericTCPUDPPacket packet) {
        List<TXField> fields = new ArrayList<>();
        for (TXField field : this.getFields()) {
            if (field.getField_type() != TXFieldType.ITERATION) {
                fields.add(field.clone());
            } else {
                int iteration_count;
                if (field.getIteration_count().contains("@")) {
                    iteration_count = (int) ByteBufUtils.evalRefField(packet, field.getIteration_count());
                } else {
                    iteration_count = Integer.parseInt(field.getIteration_count());
                }
                for (int i = 0; i < iteration_count; i++) {
                    TXField f = field.clone();
                    if (f.getValue() == null) {
                        f.setField_type(TXFieldType.SIMULATION);
                        f.setField_id(field.getField_id() + (i + 1));
                    } else if (f.getValue().contains("@")) {
                        f.setField_type(TXFieldType.REFERENCE);
                    } else {
                        f.setField_type(TXFieldType.STATIC);
                    }
                    fields.add(f);
                }
            }
        }
        this.setFields(fields);
    }
}
