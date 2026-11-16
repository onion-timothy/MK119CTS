package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class DaeyunData extends ServerData {
    private final Map<String, Double> txFieldsIdValueMap = new java.util.concurrent.ConcurrentHashMap<>();   // RS 본문용. Value Simulation 과 패킷 처리가 함께 쓴다
    private final short[] analogChannelValues = new short[32];
    private final BitSet digitalChannelValues = new BitSet(32);

    public Boolean getDigitalChannelValue(int channel) {
        return this.digitalChannelValues.get(channel);
    }

    public void setDigitalChannelValues(int channel, boolean set) {
        this.digitalChannelValues.set(channel, set);
    }

    public short[] getAnalogChannelValues(int channels) {
        return Arrays.copyOfRange(this.analogChannelValues, 0, channels);
    }

    public void setAnalogChannelValues(int channel, short value) {
        this.analogChannelValues[channel] = value;
    }

    @Override
    public void resetServerData() {
        txFieldsIdValueMap.clear();
        Arrays.fill(analogChannelValues, (short) 0);
        digitalChannelValues.clear();
    }
}
