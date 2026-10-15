package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.onionsoftware.mk119.commtestsuite.enums.DataType;
import com.onionsoftware.mk119.commtestsuite.enums.Policy;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulation.policies 는 AttributeConverter 로 저장되는 값 객체다.
 * equals/hashCode 가 없으면 Hibernate 가 변경 여부를 판단할 수 없어
 * flush 마다 '변경됨'으로 보고 불필요한 UPDATE 를 발행한다.
 */
@Getter
@Setter
@EqualsAndHashCode
public class SimulationPolicy {
    private String id;
    private Policy policy;
    private DataType data_type;
    /**
     * STATIC 은 단일 숫자, SEQUENCE 는 숫자 배열을 담는다.
     * 두 형태를 한 필드에서 다루려고 Object 로 두었으므로 직접 쓰지 말고
     * {@link #numericValue()} / {@link #sequenceValues()} 로 읽는다.
     * (Gson 역직렬화 결과는 숫자면 Double, 배열이면 List 다)
     */
    private Object value;
    private List<Double> range;
    private Distribution distribution;

    /** STATIC 용 단일 숫자. 숫자가 아니면 null. */
    public Double numericValue() {
        return (value instanceof Number) ? ((Number) value).doubleValue() : null;
    }

    /** SEQUENCE 용 숫자 배열. 배열이 아니거나 숫자가 아닌 원소가 있으면 null. */
    public List<Double> sequenceValues() {
        if (!(value instanceof List)) return null;
        List<?> raw = (List<?>) value;
        if (raw.isEmpty()) return null;
        List<Double> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (!(o instanceof Number)) return null;
            out.add(((Number) o).doubleValue());
        }
        return out;
    }
}
