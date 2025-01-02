package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.onionsoftware.mk119.commtestsuite.db.bean.converter.JsonStringConfigMapConverter;
import com.onionsoftware.mk119.commtestsuite.db.bean.converter.ProtocolIntegerConverter;
import com.onionsoftware.mk119.commtestsuite.db.bean.converter.TransportIntegerConverter;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import javax.persistence.*;
import java.util.List;
import java.util.Map;

@Component
@Getter
@Setter
@Entity
@Table(name = "devices", uniqueConstraints = {@UniqueConstraint(columnNames = {"name", "group_id"})})
public class Device {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Convert(converter = TransportIntegerConverter.class)
    private Transport transport;
    @Convert(converter = ProtocolIntegerConverter.class)
    private Protocol protocol;
    private String host;
    private Integer port;
    private String name;
    private Integer enabled;
    @ManyToOne
    @JoinColumn(name = "group_id")
    private Group group;
    private Integer log;

    @Convert(converter = JsonStringConfigMapConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<Object, Object> communicationSettings;

    public static final int DEVICE_ENABLED = 1;
    public static final int DEVICE_DISABLED = 0;

    @OneToMany(mappedBy = "device", cascade = CascadeType.REMOVE)
    @JsonIgnore
    private List<Simulation> simulations;

    @OneToMany(mappedBy = "device", cascade = CascadeType.REMOVE)
    @JsonIgnore
    private List<Error> errors;
}