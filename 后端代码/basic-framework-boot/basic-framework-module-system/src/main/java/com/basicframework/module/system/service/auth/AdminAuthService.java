package com.basicframework.module.system.service.auth;

import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.AuthResetPasswordDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsSendDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import jakarta.validation.Valid;

/**
 * 管理后台的认证 Service 接口
 *
 * 提供用户的登录、登出的能力
 *
 */
public interface AdminAuthService {

    /**
     * 验证账号 + 密码。如果通过，则返回用户。未知账号与已锁定账号仍执行一次部署强度的假哈希校验，避免形成时序枚举旁路；启用账号的旧工作因子哈希会按当前强度条件升级。
     *
     * @param username 账号
     * @param password 密码
     * @return 用户
     */
    AdminUserDO authenticate(String username, String password);

    /**
     * 账号登录
     *
     * @param reqDTO 登录信息
     * @return 访问令牌
     */
    AuthLoginResultDTO login(@Valid AuthLoginDTO reqDTO);

    /**
     * 基于 token 退出登录
     *
     * @param token token
     * @param logType 登出类型
     */
    void logout(String token, Integer logType);

    /**
     * 管理控制面按内部会话编号强制登出，不暴露 bearer token。
     *
     * @param accessTokenId 访问令牌记录编号
     * @param logType 登出类型
     */
    void logoutByAccessTokenId(Long accessTokenId, Integer logType);

    /**
     * 基于刷新令牌退出登录。
     *
     * @param refreshToken 刷新令牌
     * @param logType 登出类型
     */
    void logoutByRefreshToken(String refreshToken, Integer logType);

    /**
     * 短信验证码发送。未知手机号不会发送验证码，但与已存在账号保持相同的外部响应。
     *
     * @param reqDTO 发送请求
     */
    void sendSmsCode(AuthSmsSendDTO reqDTO);

    /**
     * 短信登录
     *
     * @param reqDTO 登录信息
     * @return 访问令牌
     */
    AuthLoginResultDTO smsLogin(AuthSmsLoginDTO reqDTO);

    /**
     * MFA 成功后签发令牌并记录成功日志。
     *
     * @param principal 已验证主体
     * @return 登录结果
     */
    AuthLoginResultDTO completeMfaLogin(MfaVerifiedPrincipalDTO principal);

    /**
     * 刷新访问令牌
     *
     * @param refreshToken 刷新令牌
     * @return 访问令牌
     */
    UserSessionDO refreshToken(String refreshToken);

    /**
     * 重置密码。短信码校验先于账号查询；校验成功后账号已不存在时保持通用成功响应。
     *
     * @param reqDTO 验证码信息
     */
    void resetPassword(AuthResetPasswordDTO reqDTO);

    /**
     * 管理员解除指定用户的临时登录锁定。
     *
     * @param userId 用户编号
     */
    void unlockLogin(Long userId);
}
