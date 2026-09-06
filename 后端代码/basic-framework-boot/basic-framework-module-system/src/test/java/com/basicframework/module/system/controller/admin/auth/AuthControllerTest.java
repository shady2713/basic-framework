package com.basicframework.module.system.controller.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.controller.admin.auth.vo.AuthLoginReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthLoginRespVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaRecoveryVerifyReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaTokenReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaTotpSetupRespVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaTotpVerifyReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaWebAuthnFinishReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaWebAuthnOptionsRespVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthResetPasswordReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthSmsLoginReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthSmsSendReqVO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.auth.MfaService;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import com.basicframework.module.system.service.permission.MenuService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthControllerTest {

    private static final Long USER_ID = 23L;
    private static final String ACCESS_TOKEN = "access-token";
    private static final String REFRESH_TOKEN = "refresh-token";

    private final AdminAuthService authService = mock(AdminAuthService.class);
    private final AdminUserService userService = mock(AdminUserService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final MenuService menuService = mock(MenuService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SecurityProperties securityProperties = new SecurityProperties();
    private final MfaService mfaService = mock(MfaService.class);
    private final AuthRefreshTokenCookieManager refreshTokenCookieManager = mock(AuthRefreshTokenCookieManager.class);
    private final AuthController controller = new AuthController(
            authService,
            userService,
            roleService,
            menuService,
            permissionService,
            securityProperties,
            mfaService,
            refreshTokenCookieManager);

    @Test
    void login_issuesRefreshTokenAndDisablesAuthenticationCaching() {
        AuthLoginReqVO request = AuthLoginReqVO.builder()
                .username("admin")
                .password("CorrectPassword1")
                .build();
        when(authService.login(any(AuthLoginDTO.class))).thenReturn(tokenResult());
        MockHttpServletResponse response = new MockHttpServletResponse();

        CommonResult<AuthLoginRespVO> result = controller.login(request, response);

        assertThat(result.getData().getUserId()).isEqualTo(USER_ID);
        assertThat(result.getData().getAccessToken()).isEqualTo(ACCESS_TOKEN);
        assertAuthenticationCachingDisabled(response);
        verify(authService)
                .login(argThat(login ->
                        "admin".equals(login.getUsername()) && "CorrectPassword1".equals(login.getPassword())));
        verify(refreshTokenCookieManager).issue(response, REFRESH_TOKEN);
    }

    @Test
    void requiredTotpEndpoints_delegateChallengeAndDoNotExposeRefreshToken() {
        MfaTotpSetupDTO setup = MfaTotpSetupDTO.builder()
                .enrollmentToken("enrollment-token")
                .secret("secret")
                .otpauthUri("otpauth://totp/example")
                .build();
        MfaVerifiedPrincipalDTO principal =
                MfaVerifiedPrincipalDTO.builder().userId(USER_ID).build();
        when(mfaService.beginRequiredTotpEnrollment("mfa-token")).thenReturn(setup);
        when(mfaService.completeRequiredTotpEnrollment("mfa-token", "123456")).thenReturn(principal);
        when(mfaService.verifyTotp("mfa-token", "123456")).thenReturn(principal);
        when(mfaService.verifyRecoveryCode("mfa-token", "ABCD-EFGH-IJKL-MNOP")).thenReturn(principal);
        when(authService.completeMfaLogin(principal)).thenReturn(mfaChallengeResult());

        MockHttpServletResponse startResponse = new MockHttpServletResponse();
        CommonResult<AuthMfaTotpSetupRespVO> startResult =
                controller.startRequiredTotpEnrollment(tokenRequest("mfa-token"), startResponse);
        CommonResult<AuthLoginRespVO> enrollResult = controller.finishRequiredTotpEnrollment(
                totpRequest("mfa-token", "123456"), new MockHttpServletResponse());
        CommonResult<AuthLoginRespVO> totpResult =
                controller.verifyTotp(totpRequest("mfa-token", "123456"), new MockHttpServletResponse());
        CommonResult<AuthLoginRespVO> recoveryResult = controller.verifyRecoveryCode(
                recoveryRequest("mfa-token", "ABCD-EFGH-IJKL-MNOP"), new MockHttpServletResponse());

        assertThat(startResult.getData().getEnrollmentToken()).isEqualTo("enrollment-token");
        assertThat(enrollResult.getData().getMfaToken()).isEqualTo("next-mfa-token");
        assertThat(totpResult.getData().getAccessToken()).isNull();
        assertThat(recoveryResult.getData().getMfaRequired()).isTrue();
        assertAuthenticationCachingDisabled(startResponse);
        verify(mfaService).beginRequiredTotpEnrollment("mfa-token");
        verify(mfaService).completeRequiredTotpEnrollment("mfa-token", "123456");
        verify(mfaService).verifyTotp("mfa-token", "123456");
        verify(mfaService).verifyRecoveryCode("mfa-token", "ABCD-EFGH-IJKL-MNOP");
        verify(refreshTokenCookieManager, never()).issue(any(), anyString());
    }

    @Test
    void webAuthnEndpoints_delegateCeremonyTokensAndCredentialPayloads() {
        MfaWebAuthnOptionsDTO options = MfaWebAuthnOptionsDTO.builder()
                .ceremonyToken("ceremony-token")
                .optionsJson("{\"challenge\":\"example\"}")
                .build();
        MfaVerifiedPrincipalDTO principal =
                MfaVerifiedPrincipalDTO.builder().userId(USER_ID).build();
        when(mfaService.beginRequiredWebAuthnEnrollment("mfa-token")).thenReturn(options);
        when(mfaService.completeRequiredWebAuthnEnrollment("ceremony-token", "{\"id\":\"credential\"}"))
                .thenReturn(principal);
        when(mfaService.beginWebAuthnAuthentication("mfa-token")).thenReturn(options);
        when(mfaService.verifyWebAuthn("ceremony-token", "{\"id\":\"credential\"}"))
                .thenReturn(principal);
        when(authService.completeMfaLogin(principal)).thenReturn(mfaChallengeResult());

        MockHttpServletResponse enrollStartResponse = new MockHttpServletResponse();
        CommonResult<AuthMfaWebAuthnOptionsRespVO> enrollStartResult =
                controller.startRequiredWebAuthnEnrollment(tokenRequest("mfa-token"), enrollStartResponse);
        CommonResult<AuthLoginRespVO> enrollFinishResult = controller.finishRequiredWebAuthnEnrollment(
                webAuthnFinishRequest("ceremony-token", "{\"id\":\"credential\"}"), new MockHttpServletResponse());
        CommonResult<AuthMfaWebAuthnOptionsRespVO> authenticationStartResult =
                controller.startWebAuthnAuthentication(tokenRequest("mfa-token"), new MockHttpServletResponse());
        CommonResult<AuthLoginRespVO> authenticationFinishResult = controller.finishWebAuthnAuthentication(
                webAuthnFinishRequest("ceremony-token", "{\"id\":\"credential\"}"), new MockHttpServletResponse());

        assertThat(enrollStartResult.getData().getCeremonyToken()).isEqualTo("ceremony-token");
        assertThat(enrollFinishResult.getData().getMfaToken()).isEqualTo("next-mfa-token");
        assertThat(authenticationStartResult.getData().getOptionsJson()).isEqualTo("{\"challenge\":\"example\"}");
        assertThat(authenticationFinishResult.getData().getMfaRequired()).isTrue();
        assertAuthenticationCachingDisabled(enrollStartResponse);
        verify(mfaService).beginRequiredWebAuthnEnrollment("mfa-token");
        verify(mfaService).completeRequiredWebAuthnEnrollment("ceremony-token", "{\"id\":\"credential\"}");
        verify(mfaService).beginWebAuthnAuthentication("mfa-token");
        verify(mfaService).verifyWebAuthn("ceremony-token", "{\"id\":\"credential\"}");
    }

    @Test
    void stepUpEndpoints_bindTheChallengeToCurrentAccessToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MfaWebAuthnOptionsDTO options = MfaWebAuthnOptionsDTO.builder()
                .ceremonyToken("ceremony-token")
                .optionsJson("{\"challenge\":\"example\"}")
                .build();
        when(mfaService.beginStepUp(USER_ID, ACCESS_TOKEN)).thenReturn(mfaChallengeResult());
        when(mfaService.beginStepUpWebAuthn("mfa-token")).thenReturn(options);

        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class)) {
            securityUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);
            securityUtils
                    .when(() ->
                            SecurityFrameworkUtils.obtainAuthorization(request, securityProperties.getTokenHeader()))
                    .thenReturn(ACCESS_TOKEN);

            MockHttpServletResponse startResponse = new MockHttpServletResponse();
            CommonResult<AuthLoginRespVO> startResult = controller.startStepUp(request, startResponse);
            CommonResult<Boolean> totpResult =
                    controller.finishStepUpTotp(totpRequest("mfa-token", "123456"), new MockHttpServletResponse());
            CommonResult<Boolean> recoveryResult = controller.finishStepUpRecoveryCode(
                    recoveryRequest("mfa-token", "ABCD-EFGH-IJKL-MNOP"), new MockHttpServletResponse());
            CommonResult<AuthMfaWebAuthnOptionsRespVO> webAuthnStartResult =
                    controller.startStepUpWebAuthn(tokenRequest("mfa-token"), new MockHttpServletResponse());
            CommonResult<Boolean> webAuthnFinishResult = controller.finishStepUpWebAuthn(
                    webAuthnFinishRequest("ceremony-token", "{\"id\":\"credential\"}"), new MockHttpServletResponse());

            assertThat(startResult.getData().getMfaToken()).isEqualTo("next-mfa-token");
            assertThat(totpResult.getData()).isTrue();
            assertThat(recoveryResult.getData()).isTrue();
            assertThat(webAuthnStartResult.getData().getCeremonyToken()).isEqualTo("ceremony-token");
            assertThat(webAuthnFinishResult.getData()).isTrue();
            assertAuthenticationCachingDisabled(startResponse);
        }

        verify(mfaService).beginStepUp(USER_ID, ACCESS_TOKEN);
        verify(mfaService).completeStepUpTotp("mfa-token", "123456");
        verify(mfaService).completeStepUpRecoveryCode("mfa-token", "ABCD-EFGH-IJKL-MNOP");
        verify(mfaService).beginStepUpWebAuthn("mfa-token");
        verify(mfaService).completeStepUpWebAuthn("ceremony-token", "{\"id\":\"credential\"}");
    }

    @Test
    void logout_revokesBothCredentialKindsAndClearsRefreshCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(refreshTokenCookieManager.read(request)).thenReturn(REFRESH_TOKEN);

        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class)) {
            securityUtils
                    .when(() ->
                            SecurityFrameworkUtils.obtainAuthorization(request, securityProperties.getTokenHeader()))
                    .thenReturn(ACCESS_TOKEN);

            CommonResult<Boolean> result = controller.logout(request, response);

            assertThat(result.getData()).isTrue();
        }

        verify(authService).logout(ACCESS_TOKEN, LoginLogTypeEnum.LOGOUT_SELF.getType());
        verify(authService).logoutByRefreshToken(REFRESH_TOKEN, LoginLogTypeEnum.LOGOUT_SELF.getType());
        verify(refreshTokenCookieManager).validateBrowserOrigin(request);
        verify(refreshTokenCookieManager).clear(response);
    }

    @Test
    void logout_stillClearsCookieWhenTheRequestHasNoCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(refreshTokenCookieManager.read(request)).thenReturn(null);

        try (MockedStatic<SecurityFrameworkUtils> ignored = mockStatic(SecurityFrameworkUtils.class)) {
            CommonResult<Boolean> result = controller.logout(request, response);

            assertThat(result.getData()).isTrue();
        }

        verify(authService, never()).logout(anyString(), any());
        verify(authService, never()).logoutByRefreshToken(anyString(), any());
        verify(refreshTokenCookieManager, never()).validateBrowserOrigin(request);
        verify(refreshTokenCookieManager).clear(response);
    }

    @Test
    void refreshToken_requiresCookieAndRotatesTheNewRefreshToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserSessionDO session = new UserSessionDO();
        session.setUserId(USER_ID);
        session.setAccessToken(ACCESS_TOKEN);
        session.setRefreshToken(REFRESH_TOKEN);
        session.setAccessExpiresTime(LocalDateTime.of(2026, 8, 31, 17, 0));
        when(refreshTokenCookieManager.require(request)).thenReturn(REFRESH_TOKEN);
        when(authService.refreshToken(REFRESH_TOKEN)).thenReturn(session);

        CommonResult<AuthLoginRespVO> result = controller.refreshToken(request, response);

        assertThat(result.getData().getAccessToken()).isEqualTo(ACCESS_TOKEN);
        assertAuthenticationCachingDisabled(response);
        verify(authService).refreshToken(REFRESH_TOKEN);
        verify(refreshTokenCookieManager).validateBrowserOrigin(request);
        verify(refreshTokenCookieManager).issue(response, REFRESH_TOKEN);
    }

    @Test
    void getPermissionInfo_returnsNullWhenTheCurrentUserNoLongerExists() {
        when(userService.getUser(USER_ID)).thenReturn(null);

        CommonResult<AuthPermissionInfoRespVO> result;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            result = controller.getPermissionInfo();
        }

        assertThat(result.getData()).isNull();
    }

    @Test
    void getPermissionInfo_returnsAnEmptyPermissionSetWithoutRoles() {
        when(userService.getUser(USER_ID)).thenReturn(user());
        when(permissionService.getUserRoleIdListByUserId(USER_ID)).thenReturn(Set.of());

        CommonResult<AuthPermissionInfoRespVO> result;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            result = controller.getPermissionInfo();
        }

        assertThat(result.getData().getRoles()).isEmpty();
        assertThat(result.getData().getPermissions()).isEmpty();
        assertThat(result.getData().getMenus()).isEmpty();
    }

    @Test
    void getPermissionInfo_filtersDisabledRolesAndBuildsAllowedMenuTree() {
        RoleDO enabledRole = role(1L, "admin", CommonStatusEnum.ENABLE.getStatus());
        RoleDO disabledRole = role(2L, "disabled", CommonStatusEnum.DISABLE.getStatus());
        MenuDO menu = new MenuDO();
        menu.setId(3L);
        menu.setParentId(MenuDO.ID_ROOT);
        menu.setType(MenuTypeEnum.MENU.getType());
        menu.setSort(1);
        menu.setName("Dashboard");
        menu.setPermission("system:dashboard:read");
        List<MenuDO> menus = new ArrayList<>(List.of(menu));
        when(userService.getUser(USER_ID)).thenReturn(user());
        when(permissionService.getUserRoleIdListByUserId(USER_ID)).thenReturn(Set.of(1L, 2L));
        when(roleService.getRoleList(Set.of(1L, 2L))).thenReturn(List.of(enabledRole, disabledRole));
        when(permissionService.getRoleMenuListByRoleId(Set.of(1L))).thenReturn(Set.of(3L));
        when(menuService.getMenuList(Set.of(3L))).thenReturn(menus);
        when(menuService.filterDisableMenus(menus)).thenReturn(menus);

        CommonResult<AuthPermissionInfoRespVO> result;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            result = controller.getPermissionInfo();
        }

        assertThat(result.getData().getRoles()).containsExactly("admin");
        assertThat(result.getData().getPermissions()).containsExactly("system:dashboard:read");
        assertThat(result.getData().getMenus())
                .singleElement()
                .extracting(AuthPermissionInfoRespVO.MenuVO::getName)
                .isEqualTo("Dashboard");
        verify(permissionService).getRoleMenuListByRoleId(Set.of(1L));
        verify(menuService).filterDisableMenus(menus);
    }

    @Test
    void smsAndPasswordResetEndpointsMapInputAndKeepAuthenticationResponsesPrivate() {
        AuthSmsLoginReqVO smsLoginRequest =
                AuthSmsLoginReqVO.builder().mobile("13800138000").code("123456").build();
        AuthSmsSendReqVO smsSendRequest =
                AuthSmsSendReqVO.builder().mobile("13800138000").scene(1).build();
        AuthResetPasswordReqVO resetPasswordRequest = AuthResetPasswordReqVO.builder()
                .mobile("13800138000")
                .code("123456")
                .password("CorrectPassword1")
                .build();
        when(authService.smsLogin(any())).thenReturn(tokenResult());
        MockHttpServletResponse response = new MockHttpServletResponse();

        CommonResult<AuthLoginRespVO> loginResult = controller.smsLogin(smsLoginRequest, response);
        CommonResult<Boolean> sendResult = controller.sendLoginSmsCode(smsSendRequest);
        CommonResult<Boolean> resetResult = controller.resetPassword(resetPasswordRequest);

        assertThat(loginResult.getData().getAccessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(sendResult.getData()).isTrue();
        assertThat(resetResult.getData()).isTrue();
        assertAuthenticationCachingDisabled(response);
        verify(authService)
                .smsLogin(
                        argThat(login -> "13800138000".equals(login.getMobile()) && "123456".equals(login.getCode())));
        verify(authService)
                .sendSmsCode(argThat(send -> "13800138000".equals(send.getMobile()) && send.getScene() == 1));
        verify(authService)
                .resetPassword(argThat(reset -> "CorrectPassword1".equals(reset.getPassword())
                        && "13800138000".equals(reset.getMobile())
                        && "123456".equals(reset.getCode())));
    }

    private static AuthLoginResultDTO tokenResult() {
        return AuthLoginResultDTO.builder()
                .userId(USER_ID)
                .accessToken(ACCESS_TOKEN)
                .refreshToken(REFRESH_TOKEN)
                .expiresTime(LocalDateTime.of(2026, 8, 31, 17, 0))
                .mfaRequired(false)
                .mfaEnrollmentRequired(false)
                .build();
    }

    private static AuthLoginResultDTO mfaChallengeResult() {
        return AuthLoginResultDTO.builder()
                .userId(USER_ID)
                .mfaRequired(true)
                .mfaEnrollmentRequired(false)
                .mfaToken("next-mfa-token")
                .mfaMethods(List.of("totp", "webauthn"))
                .build();
    }

    private static AuthMfaTokenReqVO tokenRequest(String token) {
        AuthMfaTokenReqVO request = new AuthMfaTokenReqVO();
        request.setMfaToken(token);
        return request;
    }

    private static AuthMfaTotpVerifyReqVO totpRequest(String token, String code) {
        AuthMfaTotpVerifyReqVO request = new AuthMfaTotpVerifyReqVO();
        request.setMfaToken(token);
        request.setCode(code);
        return request;
    }

    private static AuthMfaRecoveryVerifyReqVO recoveryRequest(String token, String recoveryCode) {
        AuthMfaRecoveryVerifyReqVO request = new AuthMfaRecoveryVerifyReqVO();
        request.setMfaToken(token);
        request.setRecoveryCode(recoveryCode);
        return request;
    }

    private static AuthMfaWebAuthnFinishReqVO webAuthnFinishRequest(String ceremonyToken, String credentialJson) {
        AuthMfaWebAuthnFinishReqVO request = new AuthMfaWebAuthnFinishReqVO();
        request.setCeremonyToken(ceremonyToken);
        request.setCredentialJson(credentialJson);
        return request;
    }

    private static AdminUserDO user() {
        AdminUserDO user = new AdminUserDO();
        user.setId(USER_ID);
        user.setUsername("admin");
        user.setNickname("管理员");
        return user;
    }

    private static RoleDO role(Long id, String code, Integer status) {
        RoleDO role = new RoleDO();
        role.setId(id);
        role.setCode(code);
        role.setStatus(status);
        return role;
    }

    private static MockedStatic<SecurityFrameworkUtils> mockCurrentUser() {
        MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class);
        securityUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);
        return securityUtils;
    }

    private static void assertAuthenticationCachingDisabled(MockHttpServletResponse response) {
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }
}
