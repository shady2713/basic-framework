package com.basicframework.module.system.enums.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** MFA 因子类型。TOTP 是唯一可注册的认证器因子，恢复码由独立表管理不占用因子类型。 */
@Getter
@AllArgsConstructor
public enum MfaFactorTypeEnum {
    TOTP(2);

    private final Integer type;
}
