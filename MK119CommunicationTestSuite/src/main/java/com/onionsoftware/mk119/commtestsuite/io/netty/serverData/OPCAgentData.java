package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;

@Getter
@Setter
public class OPCAgentData implements ServerData {
    private final double[] values;

    public OPCAgentData(int size) {
        this.values = new double[size];
    }

    public void setValue(int index, double value) {
        this.values[index] = value;
    }

    public double[] getValues(int start, int length) {
        return Arrays.copyOfRange(values, start, start + length);
    }
    @Override
    public void resetServerData() {
        Arrays.fill(values, 0);
    }
}
