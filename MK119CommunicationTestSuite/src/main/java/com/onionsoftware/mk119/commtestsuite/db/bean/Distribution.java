package com.onionsoftware.mk119.commtestsuite.db.bean;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * RANDOM 정책에 부착되어 값 생성 분포를 정의한다.
 * type=BINS 인 경우 range를 binCount개의 segment로 나눈 뒤 weights 비율로 segment를 선택,
 * 선택된 segment 내부에서 균등 샘플링한다. distribution이 null이면 기존 균등 분포가 그대로 사용된다.
 *
 * sizes가 null/비어있으면 segment를 균등 분할하며, 값이 주어지면 각 segment의 상대 폭으로 해석되어
 * 정규화된 비율로 range를 분할한다.
 */
@Getter
@Setter
@EqualsAndHashCode
public class Distribution {
    private String type;
    private Integer binCount;
    private List<Double> weights;
    private List<Double> sizes;
    // Normal preset 파라미터 (있으면 UI 가 정규분포 모드로 복원). 샘플링은 weights 기준이라 백엔드 로직 변화 없음.
    private Double mean;
    private Double std;
}
