package com.basicframework.module.system.enums.auth;

/** MFA 一次性挑战用途。 */
public enum MfaChallengePurposeEnum {
    LOGIN,
    REQUIRED_ENROLLMENT,
    TOTP_ENROLLMENT,
    STEP_UP,
    TOTP_MANAGEMENT_ENROLLMENT
}
