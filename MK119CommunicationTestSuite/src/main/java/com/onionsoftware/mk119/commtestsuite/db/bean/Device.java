package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.Map;

@Component
@Getter
@Setter
public class Device {
    private int id;
    private Transport transport;
    private int intTransport;
    private Protocol protocol;
    private int intProtocol;
    private String host;
    private int port;
    private String name;
    private int enabled;
    private int group_id;
    private int log;

    private String strCommunicationSettings;
    private Map<Object, Object> communicationSettings;

    static Gson parser = new Gson();

    public void setIntTransport(int transport) {
        this.transport = Transport.values()[transport - 1];
        this.intTransport = transport;
    }

    public void setIntProtocol(int protocol) {
        this.protocol = Protocol.values()[protocol - 1];
        this.intProtocol = protocol;
    }

    public void setStrCommunicationSettings(String strCommunicationSettings) {
        this.strCommunicationSettings = strCommunicationSettings;
        Type map = new TypeToken<Map<Object, Object>>() {}.getType();
        this.communicationSettings = parser.fromJson(this.strCommunicationSettings, map);
    }

    public static final int DEVICE_ENABLED = 1;
    public static final int DEVICE_DISABLED = 0;
}