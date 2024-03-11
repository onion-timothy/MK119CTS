package com.onionsoftware.mk119.commtestsuite.io.db;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import lombok.Getter;
import lombok.Setter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@Getter
@Setter
public class DBClientDevice {
    private Device device;
    private Connection connection;
    private String driver;
    private String url;
    private String username;
    private String password;

    public DBClientDevice(Device device) throws ClassNotFoundException, SQLException {
        this.setDevice(device);
        this.setDriver(device.getCommunicationSettings().get("dbdriver").toString());
        this.setUrl(device.getCommunicationSettings().get("dbUrl").toString());
        this.setUsername(device.getCommunicationSettings().get("dbUsername").toString());
        this.setPassword(device.getCommunicationSettings().get("dbPassword").toString());

        Class.forName(this.getDriver());
        connection = DriverManager.getConnection(this.getUrl(), this.getUsername(), this.getPassword());
    }
}
