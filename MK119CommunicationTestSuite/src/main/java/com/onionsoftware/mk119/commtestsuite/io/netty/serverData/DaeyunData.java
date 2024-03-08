package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

public class DaeyunData implements ServerData {
    private final Map<String, Double> txFieldsIdValueMap = new HashMap<>();
    private final short[] analogChannelValues = new short[32];
    private final BitSet digitalChannelValues = new BitSet(32);

    public Map<String, Double> getTxFieldsIdValueMap() {
        return this.txFieldsIdValueMap;
    }

    public Boolean getDigitalChannelValue(int channel) {
        return this.digitalChannelValues.get(channel);
    }

    public void setDigitalChannelValues(int channel, boolean set) {
        this.digitalChannelValues.set(channel, set);
    }

    public short[] getAnalogChannelValues(int channels){
        return Arrays.copyOfRange(this.analogChannelValues, 0, channels);
    }

    public void setAnalogChannelValues(int channel, short value){
        this.analogChannelValues[channel] = value;
    }
}
