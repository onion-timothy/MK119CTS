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
@Table(name = "devices")
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
    @Column(unique = true)
    private String name;
    private Integer enabled;
    @ManyToOne
    @JoinColumn(name = "group_id")
    private Group group;
    private Integer log;

    @Convert(converter = JsonStringConfigMapConverter.class)
    @Lob
    private Map<Object, Object> communicationSettings;

    public static final int DEVICE_ENABLED = 1;
    public static final int DEVICE_DISABLED = 0;

    /**
     * 패킷 로깅이 켜져 있는지. {@code log} 는 nullable 이므로 미지정은 꺼진 것으로 본다.
     * <p>
     * {@code getLog() == DEVICE_ENABLED} 처럼 직접 비교하면 언박싱 NPE 가 난다.
     * 그 NPE 는 IOServer 생성자에서 터져, DB insert 는 끝났는데 장비는 시작되지 않은
     * 상태를 만든다. 비교는 반드시 이 메서드로 한다.
     */
    @JsonIgnore
    public boolean isLogEnabled() {
        return log != null && log == DEVICE_ENABLED;
    }

    /**
     * 장비가 활성인지. {@code enabled} 도 nullable 이므로 미지정은 비활성으로 본다.
     * <p>
     * {@code @JsonIgnore} 필수: Jackson 은 {@code isXxx()} 를 boolean 프로퍼티 getter 로 보므로,
     * 빼면 Device JSON 에 deviceEnabled/logEnabled 가 새로 생긴다. 에디터 페이지는 받은 Device
     * JSON 을 그대로 되돌려 보내고, {@code WebController.saveSimulation} 의 ObjectMapper 는
     * 모르는 필드에서 UnrecognizedPropertyException 을 던진다.
     */
    @JsonIgnore
    public boolean isDeviceEnabled() {
        return enabled != null && enabled == DEVICE_ENABLED;
    }

    @OneToMany(mappedBy = "device", cascade = CascadeType.REMOVE)
    @JsonIgnore
    private List<Simulation> simulations;

    @OneToMany(mappedBy = "device", cascade = CascadeType.REMOVE)
    @JsonIgnore
    private List<Error> errors;
}