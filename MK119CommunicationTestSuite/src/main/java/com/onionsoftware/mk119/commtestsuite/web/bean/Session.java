package com.onionsoftware.mk119.commtestsuite.web.bean;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Session {
    private String remoteHost;
    private int remotePort;
    private long tx;
    private long rx;
    private long uptime;
}
