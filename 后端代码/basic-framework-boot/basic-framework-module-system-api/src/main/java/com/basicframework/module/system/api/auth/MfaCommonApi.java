package com.basicframework.module.system.api.auth;

/** MFA 跨模块安全契约。 */
public interface MfaCommonApi {

    /**
     * 校验当前访问令牌是否在短时窗口内完成过 MFA 二次验证。
     *
     * @param accessToken 当前访问令牌
     * @param userId 当前用户编号
     */
    void requireStepUp(String accessToken, Long userId);
}
