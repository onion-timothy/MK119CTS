package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Type;
import java.util.Map;

@Getter
@Setter
public class Simulation {
    private int id;
    private int device_id;
    private String target;
    private String strPolicy;
    private SimulationPolicy policy;
    private int interval;
    private int enabled;

    static Gson parser = new Gson();

    public void setStrPolicy(String strPolicy) {
        this.strPolicy = strPolicy;
        Type policyType = new TypeToken<SimulationPolicy>(){}.getType();
        this.policy = parser.fromJson(this.strPolicy, policyType);
    }

    public static final int ENABLED = 1;
    public static final int DISABLED = 0;
}
