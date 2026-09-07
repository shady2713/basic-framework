package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.servlet.ServletUtils.getClientIP;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.util.ObjectUtil;
import com.anji.captcha.model.common.ResponseModel;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.util.monitor.TracerUtils;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.module.system.convert.auth.AuthConvert;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.logger.LoginResultEnum;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.AuthResetPasswordDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsSendDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.logger.LoginLogService;
import com.basicframework.module.system.service.logger.dto.LoginLogCreateReqDTO;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.sms.SmsCodeService;
import com.basicframework.module.system.service.sms.dto.SmsCodeUseReqDTO;
import com.basicframework.module.system.service.user.AdminUserService;
import com.google.common.annotations.VisibleForTesting;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auth Service 实现类
 *
 */
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AdminUserService userService;

    private final LoginLogService loginLogService;

    private final UserSessionService userSessionService;

    private final SmsCodeService smsCodeService;

    private final MfaService mfaService;

    private final LoginProtectionService loginProtectionService;

    private final CaptchaVerificationService captchaVerificationService;

    private final PasswordTimingProtectionService passwordTimingProtectionService;

    @Override
    public AdminUserDO authenticate(String username, String password) {
        final LoginLogTypeEnum logTypeEnum = LoginLogTypeEnum.LOGIN_USERNAME;
        // 校验账号是否存在
        AdminUserDO user = userService.getUserByUsername(username);
        if (user == null) {
            passwordTimingProtectionService.verifyAgainstDummyHash(password);
            createLoginLog(null, username, logTypeEnum, LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (loginProtectionService.isLocked(user.getId())) {
            passwordTimingProtectionService.verifyAgainstDummyHash(password);
            createLoginLog(user.getId(), username, logTypeEnum, LoginResultEnum.ACCOUNT_LOCKED);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (!userService.isPasswordMatch(password, user.getPassword())) {
            boolean locked = loginProtectionService.recordFailure(user.getId());
            createLoginLog(
                    user.getId(),
                    username,
                    logTypeEnum,
                    locked ? LoginResultEnum.ACCOUNT_LOCKED : LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        // 校验是否禁用
        if (CommonStatusEnum.isDisable(user.getStatus())) {
            createLoginLog(user.getId(), username, logTypeEnum, LoginResultEnum.USER_DISABLED);
            throw exception(AUTH_LOGIN_USER_DISABLED);
        }
        userService.upgradePasswordEncodingIfNeeded(user.getId(), password, user.getPassword());
        loginProtectionService.clear(user.getId());
        return user;
    }

    @Override
    @DataPermission(enable = false)
    public AuthLoginResultDTO login(AuthLoginDTO reqDTO) {
        // 校验验证码
        validateCaptcha(reqDTO);

        // 使用账号密码，进行登录
        AdminUserDO user = authenticate(reqDTO.getUsername(), reqDTO.getPassword());

        AuthLoginResultDTO mfaResult =
                mfaService.beginAuthentication(user, reqDTO.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME);
        if (mfaResult != null) {
            return mfaResult;
        }

        // 创建 Token 令牌，记录登录日志
        return AuthLoginResultDTO.token(
                createTokenAfterLoginSuccess(user.getId(), reqDTO.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME));
    }

    @Override
    public void sendSmsCode(AuthSmsSendDTO reqDTO) {
        // 如果是重置密码场景，需要校验图形验证码是否正确
        if (Objects.equals(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene(), reqDTO.getScene())) {
            ResponseModel response = captchaVerificationService.verify(reqDTO);
            if (!response.isSuccess()) {
                throw exception(AUTH_REGISTER_CAPTCHA_CODE_ERROR, response.getRepMsg());
            }
        }

        // 匿名端点对未知手机号保持相同响应，避免泄露后台账号是否存在。
        if (userService.getUserByMobile(reqDTO.getMobile()) == null) {
            return;
        }
        // 发送验证码
        smsCodeService.sendSmsCode(AuthConvert.INSTANCE.convert(reqDTO).setCreateIp(getClientIP()));
    }

    @Override
    public AuthLoginResultDTO completeMfaLogin(MfaVerifiedPrincipalDTO principal) {
        LoginLogTypeEnum logType = java.util.Arrays.stream(LoginLogTypeEnum.values())
                .filter(value -> value.getType().equals(principal.getLoginLogType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知 MFA 登录日志类型"));
        AdminUserDO user = userService.getUser(principal.getUserId());
        if (user == null) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        if (CommonStatusEnum.isDisable(user.getStatus())) {
            createLoginLog(user.getId(), principal.getUsername(), logType, LoginResultEnum.USER_DISABLED);
            throw exception(AUTH_LOGIN_USER_DISABLED);
        }
        AuthLoginResultDTO result = AuthLoginResultDTO.token(
                createTokenAfterLoginSuccess(principal.getUserId(), principal.getUsername(), logType));
        result.setRecoveryCodes(principal.getRecoveryCodes());
        return result;
    }

    private void createLoginLog(
            Long userId, String username, LoginLogTypeEnum logTypeEnum, LoginResultEnum loginResult) {
        // 插入登录日志
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(logTypeEnum.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(getUserType().getValue());
        reqDTO.setUsername(username);
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(loginResult.getResult());
        loginLogService.createLoginLog(reqDTO);
        // 更新最后登录时间
        if (userId != null && Objects.equals(LoginResultEnum.SUCCESS.getResult(), loginResult.getResult())) {
            userService.updateUserLogin(userId, ServletUtils.getClientIP());
        }
    }

    @VisibleForTesting
    void validateCaptcha(AuthLoginDTO reqDTO) {
        ResponseModel response = captchaVerificationService.verify(reqDTO);
        // 校验验证码
        if (!response.isSuccess()) {
            // 创建登录失败日志（验证码不正确)
            createLoginLog(
                    null, reqDTO.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME, LoginResultEnum.CAPTCHA_CODE_ERROR);
            throw exception(AUTH_LOGIN_CAPTCHA_CODE_ERROR, response.getRepMsg());
        }
    }

    private UserSessionDO createTokenAfterLoginSuccess(Long userId, String username, LoginLogTypeEnum logType) {
        // 插入登陆日志
        createLoginLog(userId, username, logType, LoginResultEnum.SUCCESS);
        // 创建访问令牌
        return userSessionService.createSession(userId, getUserType().getValue());
    }

    @Override
    public UserSessionDO refreshToken(String refreshToken) {
        return userSessionService.refreshSession(refreshToken);
    }

    @Override
    public void logout(String token, Integer logType) {
        // 删除访问令牌
        UserSessionDO session = userSessionService.removeSessionByAccessToken(token);
        if (session == null) {
            return;
        }
        // 删除成功，则记录登出日志
        createLogoutLog(session.getUserId(), session.getUserType(), logType);
    }

    @Override
    public void logoutByAccessTokenId(Long accessTokenId, Integer logType) {
        UserSessionDO session = userSessionService.removeSessionById(accessTokenId);
        if (session != null) {
            createLogoutLog(session.getUserId(), session.getUserType(), logType);
        }
    }

    @Override
    public void logoutByRefreshToken(String refreshToken, Integer logType) {
        UserSessionDO session = userSessionService.removeSessionByRefreshToken(refreshToken);
        if (session != null) {
            createLogoutLog(session.getUserId(), session.getUserType(), logType);
        }
    }

    private void createLogoutLog(Long userId, Integer userType, Integer logType) {
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(logType);
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(userType);
        if (userId != null && ObjectUtil.equal(getUserType().getValue(), userType)) {
            reqDTO.setUsername(getUsername(userId));
        }
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(LoginResultEnum.SUCCESS.getResult());
        loginLogService.createLoginLog(reqDTO);
    }

    private String getUsername(Long userId) {
        AdminUserDO user = userService.getUser(userId);
        return user != null ? user.getUsername() : null;
    }

    private UserTypeEnum getUserType() {
        return UserTypeEnum.ADMIN;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(AuthResetPasswordDTO reqDTO) {
        smsCodeService.useSmsCode(new SmsCodeUseReqDTO()
                .setCode(reqDTO.getCode())
                .setMobile(reqDTO.getMobile())
                .setScene(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene())
                .setUsedIp(getClientIP()));

        // 短信码校验成功但账号已删除时仍返回相同结果，避免形成账号枚举旁路。
        AdminUserDO userByMobile = userService.getUserByMobile(reqDTO.getMobile());
        if (userByMobile == null) {
            return;
        }
        userService.updateUserPassword(userByMobile.getId(), reqDTO.getPassword());
        loginProtectionService.clear(userByMobile.getId());
    }

    @Override
    public void unlockLogin(Long userId) {
        if (userService.getUser(userId) == null) {
            throw exception(USER_NOT_EXISTS);
        }
        loginProtectionService.clear(userId);
    }
}
