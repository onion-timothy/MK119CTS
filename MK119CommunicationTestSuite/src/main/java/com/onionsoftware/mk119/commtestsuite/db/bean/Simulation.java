package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.onionsoftware.mk119.commtestsuite.db.bean.converter.JsonStringPolicyListConverter;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import javax.persistence.*;
import java.util.List;

@Getter
@Setter
@Component
@Entity
@Table(name = "simulations", uniqueConstraints = {@UniqueConstraint(columnNames = {"name", "device_id"})})
public class Simulation implements Cloneable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @ManyToOne
    @JoinColumn(name = "device_id")
    Device device;
    private String target;
    @Convert(converter = JsonStringPolicyListConverter.class)
    private List<SimulationPolicy> policies;
    @Column(name="`interval`")
    private int interval;
    private int enabled;
    String name;
    boolean stopOnControl;

    @OneToOne(mappedBy = "simulation", cascade = CascadeType.REMOVE)
    @JsonIgnore
    private Error error;

    public static final int ENABLED = 1;
    public static final int DISABLED = 0;

    @Override
    public Simulation clone() {
        try {
            Simulation clone = (Simulation) super.clone();
            clone.id = null;
            clone.device = this.device;
            clone.target = this.target;
            clone.policies = this.policies;
            clone.interval = this.interval;
            clone.enabled = this.enabled;
            clone.stopOnControl = this.stopOnControl;
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
