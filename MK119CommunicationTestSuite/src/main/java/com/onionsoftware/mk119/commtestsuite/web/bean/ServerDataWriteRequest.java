package com.onionsoftware.mk119.commtestsuite.web.bean;

import lombok.Getter;
import lombok.Setter;

/**
 * REST 요청 body: {@code POST /api/devices/{id}/serverdata}.
 * 외부 시스템 연동용 서버데이터 쓰기.
 */
@Getter
@Setter
public class ServerDataWriteRequest {
    /** Protocol-specific target (예: Modbus "3_10", XGT "%DW200", OPC "12", SNMP OID). */
    private String target;
    /** 쓸 값. dataType 에 따라 해석된다. */
    private double value;
    /** (선택) DataType enum 이름. 여러 register 를 쓰는 경우 필수 (예: FP32AD). */
    private String dataType;
    /**
     * (선택) 이 쓰기가 simulation 덮어쓰기를 막는 시간(초).
     * <ul>
     *   <li>지정하지 않거나 0 : lock 하지 않으므로 다음 interval 에 simulation 이 값을 되돌린다.</li>
     *   <li>양수 : 해당 초 후 자동 해제. 세션이 없는 외부 스크립트/CI 연동용 기본 선택지.</li>
     *   <li>음수(-1 이 표준값) : 만료 없는 lock. ServerData 페이지가 보내며, 편집 세션이 끝날 때 함께 풀린다.
     *       해당 디바이스에 살아있는 편집 세션이 없으면 409 로 거절된다.</li>
     * </ul>
     */
    private Integer lockSeconds;
}
