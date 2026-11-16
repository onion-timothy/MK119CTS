package com.onionsoftware.mk119.commtestsuite.web.dto;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.bean.SimulationPolicy;

import java.util.List;

/**
 * 한 장비의 시뮬레이션 목록 한 줄.
 *
 * Simulation 엔티티를 그대로 내보내면 줄마다 device 가 통째로 따라붙는다 — group 과
 * communicationSettings 까지. 장비 하나로 조회하는 목록이라 그 값은 5851줄이 모두 같고,
 * 화면은 이미 그 장비를 알고 있다. 한 줄 524바이트 중 ~290바이트가 그 중복이었다
 * (device 2012 기준 2.7MB 중 절반).
 *
 * 그래서 목록 응답만 이 모양으로 바꾼다. 엔티티에 @JsonIgnore 를 거는 쪽은 쓰지 않았다 —
 * 저장·단건 조회 등 device 가 필요한 경로까지 같이 끊긴다.
 */
public class SimulationListItem {
    private final Integer id;
    private final String target;
    private final List<SimulationPolicy> policies;
    private final int interval;
    private final int enabled;
    private final String name;
    private final int stopOnControl;

    public SimulationListItem(Simulation s) {
        this.id = s.getId();
        this.target = s.getTarget();
        this.policies = s.getPolicies();
        this.interval = s.getInterval();
        this.enabled = s.getEnabled();
        this.name = s.getName();
        this.stopOnControl = s.getStopOnControl();
    }

    public Integer getId() { return id; }
    public String getTarget() { return target; }
    public List<SimulationPolicy> getPolicies() { return policies; }
    public int getInterval() { return interval; }
    public int getEnabled() { return enabled; }
    public String getName() { return name; }
    public int getStopOnControl() { return stopOnControl; }
}
