package com.basicframework.module.system.service.auth;

import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;

/** 管理后台 MFA 登录与强制注册服务。 */
public interface MfaService {

    /**
     * 创建 MFA 登录挑战。
     *
     * @param user 已通过第一因子认证的用户
     * @param loginIdentity 登录时使用的账号或手机号
     * @param logType 登录日志类型
     * @return 需要 MFA 时返回挑战，否则返回 null
     */
    AuthLoginResultDTO beginAuthentication(AdminUserDO user, String loginIdentity, LoginLogTypeEnum logType);

    /**
     * 为必须注册 MFA 的登录主体开始 TOTP 注册。
     *
     * @param mfaToken 一次性登录挑战
     * @return TOTP 设置材料
     */
    MfaTotpSetupDTO beginRequiredTotpEnrollment(String mfaToken);

    /**
     * 完成强制 TOTP 注册。
     *
     * @param enrollmentToken 一次性注册挑战
     * @param code TOTP 验证码
     * @return 已验证主体及仅展示一次的恢复码
     */
    MfaVerifiedPrincipalDTO completeRequiredTotpEnrollment(String enrollmentToken, String code);

    /**
     * 完成当前登录用户的首次 TOTP 自助注册，并校验挑战归属。
     *
     * @param userId 当前用户编号
     * @param enrollmentToken 一次性注册挑战
     * @param code TOTP 验证码
     * @return 已验证主体及仅展示一次的恢复码
     */
    MfaVerifiedPrincipalDTO completeSelfTotpEnrollment(Long userId, String enrollmentToken, String code);

    /**
     * 使用 TOTP 完成登录。
     *
     * @param mfaToken 一次性登录挑战
     * @param code TOTP 验证码
     * @return 已验证主体
     */
    MfaVerifiedPrincipalDTO verifyTotp(String mfaToken, String code);

    /**
     * 使用恢复码完成登录。
     *
     * @param mfaToken 一次性登录挑战
     * @param recoveryCode 一次性恢复码
     * @return 已验证主体
     */
    MfaVerifiedPrincipalDTO verifyRecoveryCode(String mfaToken, String recoveryCode);

    /**
     * 为当前登录会话创建 MFA 二次验证挑战。
     *
     * @param userId 当前用户编号
     * @param accessToken 当前访问令牌
     * @return 二次验证挑战
     */
    AuthLoginResultDTO beginStepUp(Long userId, String accessToken);

    /**
     * 使用 TOTP 完成当前会话的二次验证。
     *
     * @param mfaToken 一次性二次验证挑战
     * @param code TOTP 验证码
     */
    void completeStepUpTotp(String mfaToken, String code);

    /**
     * 使用恢复码完成当前会话的二次验证。
     *
     * @param mfaToken 一次性二次验证挑战
     * @param recoveryCode 一次性恢复码
     */
    void completeStepUpRecoveryCode(String mfaToken, String recoveryCode);

    /**
     * 校验当前会话是否处于二次验证有效窗口。
     *
     * @param accessToken 当前访问令牌
     * @param userId 当前用户编号
     */
    void requireStepUp(String accessToken, Long userId);

    /**
     * 为尚未配置 MFA 的当前用户创建自助注册挑战。
     *
     * @param userId 当前用户编号
     * @param username 当前用户名
     * @return 一次性注册挑战
     */
    String beginSelfEnrollment(Long userId, String username);

    /**
     * 查询当前用户已启用的 MFA 方法。
     *
     * @param userId 当前用户编号
     * @return 已启用方法
     */
    java.util.List<String> getEnabledMethods(Long userId);

    /**
     * 查询当前部署允许注册的 MFA 方法。
     *
     * @return 可注册方法，按推荐顺序排列
     */
    java.util.List<String> getEnrollmentMethods();
}
