package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import java.util.Arrays;

public class OPCAgentData implements ServerData {
    private final double[] values;

    public OPCAgentData(int size) {
        this.values = new double[size];
    }

    public void setValue(int index, double value){
        this.values[index] = value;
    }

    public double[] getValues(){
        return values;
    }

    public double[] getValues(int start, int length){
        return Arrays.copyOfRange(values, start, start + length);
    }
}
