package com.onionsoftware.mk119.commtestsuite.db.bean;

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
    @Lob
    private String target;
    @Convert(converter = JsonStringPolicyListConverter.class)
    @Lob
    private List<SimulationPolicy> policies;
    @Column(name="`interval`")
    private int interval;
    private int enabled;
    String name;
    /**
     * 제어 수용 시 시뮬레이션 정지 동작.
     * 0: 정지 없음.
     * N > 0: N초 동안 해당 target 을 쓰기 보호한다 (ServerData lock. 재시작 시 초기화).
     * N < 0: 영구 정지 (DB에 enabled=DISABLED 기록).
     */
    int stopOnControl;

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
