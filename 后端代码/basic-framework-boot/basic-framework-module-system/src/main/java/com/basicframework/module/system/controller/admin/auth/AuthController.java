package com.basicframework.module.system.controller.admin.auth;

import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertSet;
import static com.basicframework.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.controller.admin.auth.vo.*;
import com.basicframework.module.system.convert.auth.AuthConvert;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.auth.MfaService;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.AuthResetPasswordDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsSendDTO;
import com.basicframework.module.system.service.permission.MenuService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.user.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/** 管理后台的认证 Controller，提供登录、登出、获取用户信息等能力 */
@Tag(name = "管理后台 - 认证")
@RestController
@RequestMapping("/system/auth")
@Slf4j
public class AuthController {

    @Resource
    private AdminAuthService authService;

    @Resource
    private AdminUserService userService;

    @Resource
    private RoleService roleService;

    @Resource
    private MenuService menuService;

    @Resource
    private PermissionService permissionService;

    @Resource
    private SecurityProperties securityProperties;

    @Resource
    private MfaService mfaService;

    @Resource
    private AuthRefreshTokenCookieManager refreshTokenCookieManager;

    @PostMapping("/login")
    @PermitAll
    @Operation(summary = "使用账号密码登录")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<AuthLoginRespVO> login(@RequestBody @Valid AuthLoginReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(authService.login(BeanUtils.toBean(reqVO, AuthLoginDTO.class)), response);
    }

    @PostMapping("/mfa/totp/enroll/start")
    @PermitAll
    @Operation(summary = "开始强制 TOTP 注册")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "otpauthUri"})
    public CommonResult<AuthMfaTotpSetupRespVO> startRequiredTotpEnrollment(
            @RequestBody @Valid AuthMfaTokenReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return success(BeanUtils.toBean(
                mfaService.beginRequiredTotpEnrollment(reqVO.getMfaToken()), AuthMfaTotpSetupRespVO.class));
    }

    @PostMapping("/mfa/totp/enroll/finish")
    @PermitAll
    @Operation(summary = "完成强制 TOTP 注册并登录")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "code", "recoveryCodes"})
    public CommonResult<AuthLoginRespVO> finishRequiredTotpEnrollment(
            @RequestBody @Valid AuthMfaTotpVerifyReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(
                authService.completeMfaLogin(
                        mfaService.completeRequiredTotpEnrollment(reqVO.getMfaToken(), reqVO.getCode())),
                response);
    }

    @PostMapping("/mfa/totp/verify")
    @PermitAll
    @Operation(summary = "使用 TOTP 完成登录")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "code"})
    public CommonResult<AuthLoginRespVO> verifyTotp(
            @RequestBody @Valid AuthMfaTotpVerifyReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(
                authService.completeMfaLogin(mfaService.verifyTotp(reqVO.getMfaToken(), reqVO.getCode())), response);
    }

    @PostMapping("/mfa/recovery/verify")
    @PermitAll
    @Operation(summary = "使用一次性恢复码完成登录")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "recoveryCode"})
    public CommonResult<AuthLoginRespVO> verifyRecoveryCode(
            @RequestBody @Valid AuthMfaRecoveryVerifyReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(
                authService.completeMfaLogin(
                        mfaService.verifyRecoveryCode(reqVO.getMfaToken(), reqVO.getRecoveryCode())),
                response);
    }

    @PostMapping("/mfa/webauthn/enroll/start")
    @PermitAll
    @Operation(summary = "开始强制 WebAuthn 注册")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "ceremonyToken", "optionsJson"})
    public CommonResult<AuthMfaWebAuthnOptionsRespVO> startRequiredWebAuthnEnrollment(
            @RequestBody @Valid AuthMfaTokenReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return success(BeanUtils.toBean(
                mfaService.beginRequiredWebAuthnEnrollment(reqVO.getMfaToken()), AuthMfaWebAuthnOptionsRespVO.class));
    }

    @PostMapping("/mfa/webauthn/enroll/finish")
    @PermitAll
    @Operation(summary = "完成强制 WebAuthn 注册并登录")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"ceremonyToken", "credentialJson", "recoveryCodes"})
    public CommonResult<AuthLoginRespVO> finishRequiredWebAuthnEnrollment(
            @RequestBody @Valid AuthMfaWebAuthnFinishReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(
                authService.completeMfaLogin(mfaService.completeRequiredWebAuthnEnrollment(
                        reqVO.getCeremonyToken(), reqVO.getCredentialJson())),
                response);
    }

    @PostMapping("/mfa/webauthn/authenticate/start")
    @PermitAll
    @Operation(summary = "开始 WebAuthn 登录认证")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "ceremonyToken", "optionsJson"})
    public CommonResult<AuthMfaWebAuthnOptionsRespVO> startWebAuthnAuthentication(
            @RequestBody @Valid AuthMfaTokenReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return success(BeanUtils.toBean(
                mfaService.beginWebAuthnAuthentication(reqVO.getMfaToken()), AuthMfaWebAuthnOptionsRespVO.class));
    }

    @PostMapping("/mfa/webauthn/authenticate/finish")
    @PermitAll
    @Operation(summary = "完成 WebAuthn 登录认证")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"ceremonyToken", "credentialJson"})
    public CommonResult<AuthLoginRespVO> finishWebAuthnAuthentication(
            @RequestBody @Valid AuthMfaWebAuthnFinishReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(
                authService.completeMfaLogin(
                        mfaService.verifyWebAuthn(reqVO.getCeremonyToken(), reqVO.getCredentialJson())),
                response);
    }

    @PostMapping("/mfa/step-up/start")
    @Operation(summary = "开始当前会话的 MFA 二次验证")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = "mfaToken")
    public CommonResult<AuthLoginRespVO> startStepUp(HttpServletRequest request, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(
                mfaService.beginStepUp(getLoginUserId(), obtainCurrentAccessToken(request)), response);
    }

    @PostMapping("/mfa/step-up/totp/finish")
    @Operation(summary = "使用 TOTP 完成当前会话的 MFA 二次验证")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "code"})
    public CommonResult<Boolean> finishStepUpTotp(
            @RequestBody @Valid AuthMfaTotpVerifyReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        mfaService.completeStepUpTotp(reqVO.getMfaToken(), reqVO.getCode());
        return success(true);
    }

    @PostMapping("/mfa/step-up/recovery/finish")
    @Operation(summary = "使用恢复码完成当前会话的 MFA 二次验证")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "recoveryCode"})
    public CommonResult<Boolean> finishStepUpRecoveryCode(
            @RequestBody @Valid AuthMfaRecoveryVerifyReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        mfaService.completeStepUpRecoveryCode(reqVO.getMfaToken(), reqVO.getRecoveryCode());
        return success(true);
    }

    @PostMapping("/mfa/step-up/webauthn/start")
    @Operation(summary = "开始当前会话的 WebAuthn 二次验证")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "ceremonyToken", "optionsJson"})
    public CommonResult<AuthMfaWebAuthnOptionsRespVO> startStepUpWebAuthn(
            @RequestBody @Valid AuthMfaTokenReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return success(BeanUtils.toBean(
                mfaService.beginStepUpWebAuthn(reqVO.getMfaToken()), AuthMfaWebAuthnOptionsRespVO.class));
    }

    @PostMapping("/mfa/step-up/webauthn/finish")
    @Operation(summary = "完成当前会话的 WebAuthn 二次验证")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"ceremonyToken", "credentialJson"})
    public CommonResult<Boolean> finishStepUpWebAuthn(
            @RequestBody @Valid AuthMfaWebAuthnFinishReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        mfaService.completeStepUpWebAuthn(reqVO.getCeremonyToken(), reqVO.getCredentialJson());
        return success(true);
    }

    @PostMapping("/logout")
    @PermitAll
    @Operation(summary = "登出系统")
    public CommonResult<Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = SecurityFrameworkUtils.obtainAuthorization(
                request, securityProperties.getTokenHeader(), securityProperties.getTokenParameter());
        if (StrUtil.isNotBlank(token)) {
            authService.logout(token, LoginLogTypeEnum.LOGOUT_SELF.getType());
        }
        String refreshToken = refreshTokenCookieManager.read(request);
        if (StrUtil.isNotBlank(refreshToken)) {
            authService.logoutByRefreshToken(refreshToken, LoginLogTypeEnum.LOGOUT_SELF.getType());
        }
        refreshTokenCookieManager.clear(response);
        return success(true);
    }

    @PostMapping("/refresh-token")
    @PermitAll
    @Operation(summary = "刷新令牌")
    @RateLimiter(time = 60, count = 30, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<AuthLoginRespVO> refreshToken(HttpServletRequest request, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        String refreshToken = refreshTokenCookieManager.require(request);
        return authenticationSuccess(AuthLoginResultDTO.token(authService.refreshToken(refreshToken)), response);
    }

    @GetMapping("/get-permission-info")
    @Operation(summary = "获取登录用户的权限信息")
    public CommonResult<AuthPermissionInfoRespVO> getPermissionInfo() {
        // 1.1 获得用户信息
        AdminUserDO user = userService.getUser(getLoginUserId());
        if (user == null) {
            return success(null);
        }

        // 1.2 获得角色列表
        Set<Long> roleIds = permissionService.getUserRoleIdListByUserId(getLoginUserId());
        if (CollUtil.isEmpty(roleIds)) {
            return success(AuthConvert.INSTANCE.convert(user, Collections.emptyList(), Collections.emptyList()));
        }
        List<RoleDO> roles = roleService.getRoleList(roleIds).stream()
                .filter(role -> CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus()))
                .toList();

        // 1.3 获得菜单列表
        Set<Long> menuIds = permissionService.getRoleMenuListByRoleId(convertSet(roles, RoleDO::getId));
        List<MenuDO> menuList = menuService.getMenuList(menuIds);
        menuList = menuService.filterDisableMenus(menuList);

        // 2. 拼接结果返回
        return success(AuthConvert.INSTANCE.convert(user, roles, menuList));
    }

    // ========== 短信登录相关 ==========

    @PostMapping("/sms-login")
    @PermitAll
    @Operation(summary = "使用短信验证码登录")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = "code")
    public CommonResult<AuthLoginRespVO> smsLogin(
            @RequestBody @Valid AuthSmsLoginReqVO reqVO, HttpServletResponse response) {
        disableAuthenticationResponseCaching(response);
        return authenticationSuccess(authService.smsLogin(BeanUtils.toBean(reqVO, AuthSmsLoginDTO.class)), response);
    }

    @PostMapping("/send-sms-code")
    @PermitAll
    @Operation(summary = "发送手机验证码")
    @RateLimiter(time = 60, count = 5, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<Boolean> sendLoginSmsCode(@RequestBody @Valid AuthSmsSendReqVO reqVO) {
        authService.sendSmsCode(BeanUtils.toBean(reqVO, AuthSmsSendDTO.class));
        return success(true);
    }

    @PostMapping("/reset-password")
    @PermitAll
    @Operation(summary = "重置密码")
    @RateLimiter(time = 60, count = 5, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = "code")
    public CommonResult<Boolean> resetPassword(@RequestBody @Valid AuthResetPasswordReqVO reqVO) {
        authService.resetPassword(BeanUtils.toBean(reqVO, AuthResetPasswordDTO.class));
        return success(true);
    }

    private static void disableAuthenticationResponseCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }

    private CommonResult<AuthLoginRespVO> authenticationSuccess(
            AuthLoginResultDTO result, HttpServletResponse response) {
        if (StrUtil.isNotBlank(result.getRefreshToken())) {
            refreshTokenCookieManager.issue(response, result.getRefreshToken());
        }
        return success(BeanUtils.toBean(result, AuthLoginRespVO.class));
    }

    private String obtainCurrentAccessToken(HttpServletRequest request) {
        return SecurityFrameworkUtils.obtainAuthorization(
                request, securityProperties.getTokenHeader(), securityProperties.getTokenParameter());
    }
}
