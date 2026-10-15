package com.onionsoftware.mk119.commtestsuite.io.netty.serverData;

@FunctionalInterface
public interface ServerDataListener {
    void onServerDataChanged(ServerDataEvent event);
}
