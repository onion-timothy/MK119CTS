package com.onionsoftware.mk119.commtestsuite.io.netty.generic;

import com.onionsoftware.mk119.commtestsuite.io.netty.packet.GenericTCPUDPPacket;
import com.onionsoftware.mk119.commtestsuite.io.netty.tool.ByteBufUtils;

import java.util.ArrayList;
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

    public void translateFields(GenericTCPUDPPacket packet) {
        List<TXField> fields = new ArrayList<>();
        for (TXField field : this.getFields()) {
            if (field.getField_type() != TXFieldType.ITERATION) {
                fields.add((TXField) field.clone());
            } else {
                int iteration_count = 0;
                if (field.getIteration_count().contains("@")) {
                    iteration_count = (int) ByteBufUtils.evalRefField(packet, field.getIteration_count());
                } else {
                    iteration_count = Integer.parseInt(field.getIteration_count());
                }
                for (int i = 0; i < iteration_count; i++) {
                    TXField f = (TXField) field.clone();
                    if (f.getValue() == null) {
                        f.setField_type(TXFieldType.SIMULATION);
                        f.setField_id(field.getField_id() + (i + 1));
                    } else if (f.getValue() != null && f.getValue().contains("@")) {
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
