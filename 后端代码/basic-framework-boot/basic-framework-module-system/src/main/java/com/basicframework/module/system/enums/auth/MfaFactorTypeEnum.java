package com.basicframework.module.system.enums.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** MFA 因子类型。WebAuthn 编号预留为首选因子，TOTP 作为兜底。 */
@Getter
@AllArgsConstructor
public enum MfaFactorTypeEnum {
    WEBAUTHN(1),
    TOTP(2);

    private final Integer type;
}
