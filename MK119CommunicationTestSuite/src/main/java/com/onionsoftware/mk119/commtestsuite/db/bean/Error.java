package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.onionsoftware.mk119.commtestsuite.db.bean.converter.ErrorModeIntegerConverter;
import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import javax.persistence.*;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Getter
@Setter
@Entity
@Table(name = "errors")
public class Error {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "device_id")
    private Device device;

    @Convert(converter = ErrorModeIntegerConverter.class)
    private ErrorMode mode;

    private int frequency;
    private int enabled;
    private int timeout;
    private int garbageLength;

    @ManyToOne
    @JoinColumn(name = "simulation_id")
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private Simulation simulation;
    private Double errorValue;
    private Integer simulation_stop;

    /**
     * 에러 시뮬레이션의 확률 판정 주기 (초).
     * 이 간격마다 주사위를 굴려 에러 발생 여부를 판정한다.
     */
    @Column(name = "`interval`")
    private int interval;

    /**
     * 시스템 에러(DISCARD, TIMEOUT, GARBAGE, DISCONNECT)의 유지 시간 (초).
     * 에러가 활성화되면 이 시간 동안 해당 에러 동작이 유지된다.
     */
    @Column(name = "error_duration")
    private int errorDuration;

    public static final int ERROR_DISABLED = 0;
    public static final int ERROR_ENABLED = 1;

    /**
     * frequency(0~100 percent)를 기반으로 이번 tick에 에러가 발동할지 확률 판정한다.
     * frequency ≤ 0 이면 항상 false, ≥ 100 이면 항상 true.
     */
    public boolean shouldFire() {
        if (frequency <= 0) return false;
        if (frequency >= 100) return true;
        return ThreadLocalRandom.current().nextInt(100) < frequency;
    }
}
