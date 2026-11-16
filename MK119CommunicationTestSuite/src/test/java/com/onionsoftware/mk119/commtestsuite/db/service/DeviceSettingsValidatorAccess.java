package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.protocol.Definition;

/** 시험에서 DeviceSettingsValidator 의 패키지 안 검사를 부르는 통로 */
public final class DeviceSettingsValidatorAccess {
    private DeviceSettingsValidatorAccess() {}

    public static String paramProblem(Definition.Param p, String text) {
        return DeviceSettingsValidator.paramProblem(p, text);
    }
}
