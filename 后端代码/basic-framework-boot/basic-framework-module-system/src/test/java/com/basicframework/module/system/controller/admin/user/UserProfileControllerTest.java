package com.basicframework.module.system.controller.admin.user;

import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_PASSWORD_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaTotpSetupRespVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaTotpVerifyReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaWebAuthnFinishReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaWebAuthnOptionsRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileMfaEnrollmentStartReqVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileMfaFactorRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileMfaRecoveryCodesRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.service.auth.MfaFactorManagementService;
import com.basicframework.module.system.service.auth.MfaService;
import com.basicframework.module.system.service.auth.dto.MfaFactorDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;

class UserProfileControllerTest {

    private static final Long USER_ID = 23L;
    private static final String CURRENT_PASSWORD = "CurrentPassword1";

    private final AdminUserService userService = mock(AdminUserService.class);
    private final DeptService deptService = mock(DeptService.class);
    private final PostService postService = mock(PostService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final MfaService mfaService = mock(MfaService.class);
    private final MfaFactorManagementService mfaFactorManagementService = mock(MfaFactorManagementService.class);
    private final UserProfileController controller = new UserProfileController(
            userService,
            deptService,
            postService,
            permissionService,
            roleService,
            mfaService,
            mfaFactorManagementService);

    @Test
    void getUserProfile_mapsRolesAndOptionalOrganizationData() {
        AdminUserDO userWithOrganization = user();
        userWithOrganization.setDeptId(3L);
        userWithOrganization.setPostIds(Set.of(4L));
        AdminUserDO userWithoutOrganization = user();
        userWithoutOrganization.setUsername("standalone");
        RoleDO role = new RoleDO().setId(1L).setName("管理员").setCode("admin");
        DeptDO dept = new DeptDO().setId(3L).setName("研发部");
        PostDO post = new PostDO().setId(4L).setName("架构师").setCode("architect");
        when(userService.getUser(USER_ID)).thenReturn(userWithOrganization, userWithoutOrganization);
        when(permissionService.getUserRoleIdListByUserId(USER_ID)).thenReturn(Set.of(1L), Set.of());
        when(roleService.getRoleListFromCache(any())).thenReturn(List.of(role), List.of());
        when(deptService.getDept(3L)).thenReturn(dept);
        when(postService.getPostList(Set.of(4L))).thenReturn(List.of(post));

        CommonResult<UserProfileRespVO> organizedResult;
        CommonResult<UserProfileRespVO> standaloneResult;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            organizedResult = controller.getUserProfile();
            standaloneResult = controller.getUserProfile();
        }

        assertThat(organizedResult.getData().getRoles())
                .singleElement()
                .extracting(roleVO -> roleVO.getName())
                .isEqualTo("管理员");
        assertThat(organizedResult.getData().getDept().getName()).isEqualTo("研发部");
        assertThat(organizedResult.getData().getPosts())
                .singleElement()
                .extracting(postVO -> postVO.getName())
                .isEqualTo("架构师");
        assertThat(standaloneResult.getData().getUsername()).isEqualTo("standalone");
        assertThat(standaloneResult.getData().getDept()).isNull();
        assertThat(standaloneResult.getData().getPosts()).isNull();
    }

    @Test
    void profileUpdateAndPasswordUpdateBindTheCurrentUser() {
        UserProfileUpdateReqVO profileRequest = new UserProfileUpdateReqVO();
        profileRequest.setNickname("新昵称");
        profileRequest.setEmail("admin@example.com");
        profileRequest.setMobile("13800138000");
        profileRequest.setSex(1);
        profileRequest.setAvatar("https://example.com/avatar.png");
        UserProfileUpdatePasswordReqVO passwordRequest = new UserProfileUpdatePasswordReqVO();
        passwordRequest.setOldPassword("OldPassword1");
        passwordRequest.setNewPassword("NewPassword1");

        CommonResult<Boolean> updateResult;
        CommonResult<Boolean> passwordResult;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            updateResult = controller.updateUserProfile(profileRequest);
            passwordResult = controller.updateUserProfilePassword(passwordRequest);
        }

        assertThat(updateResult.getData()).isTrue();
        assertThat(passwordResult.getData()).isTrue();
        verify(userService)
                .updateUserProfile(
                        eq(USER_ID),
                        argThat(updated -> "新昵称".equals(updated.getNickname())
                                && "admin@example.com".equals(updated.getEmail())
                                && "13800138000".equals(updated.getMobile())
                                && Integer.valueOf(1).equals(updated.getSex())
                                && "https://example.com/avatar.png".equals(updated.getAvatar())));
        verify(userService).updateUserPassword(USER_ID, "OldPassword1", "NewPassword1");
    }

    @Test
    void mfaReadEndpoints_returnOnlyConfiguredMethodsAndSafeFactorSummaries() {
        MfaFactorDTO factor = MfaFactorDTO.builder()
                .id(8L)
                .type("WEBAUTHN")
                .name("MacBook Touch ID")
                .createTime(LocalDateTime.of(2026, 8, 31, 9, 0))
                .build();
        when(mfaService.getEnabledMethods(USER_ID)).thenReturn(List.of("totp", "webauthn"));
        when(mfaService.getEnrollmentMethods()).thenReturn(List.of("totp", "webauthn"));
        when(mfaFactorManagementService.getFactors(USER_ID)).thenReturn(List.of(factor));

        CommonResult<List<String>> methodsResult;
        CommonResult<List<String>> enrollmentMethodsResult;
        CommonResult<List<UserProfileMfaFactorRespVO>> factorsResult;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            methodsResult = controller.getMfaMethods();
            enrollmentMethodsResult = controller.getMfaEnrollmentMethods();
            factorsResult = controller.getMfaFactors();
        }

        assertThat(methodsResult.getData()).containsExactly("totp", "webauthn");
        assertThat(enrollmentMethodsResult.getData()).containsExactly("totp", "webauthn");
        assertThat(factorsResult.getData())
                .singleElement()
                .extracting(UserProfileMfaFactorRespVO::getName)
                .isEqualTo("MacBook Touch ID");
    }

    @Test
    void selfTotpEnrollment_requiresCurrentPasswordAndDisablesSensitiveResponseCaching() {
        AdminUserDO user = user();
        MfaTotpSetupDTO setup = MfaTotpSetupDTO.builder()
                .enrollmentToken("enrollment-token")
                .secret("secret")
                .otpauthUri("otpauth://totp/example")
                .build();
        when(userService.getUser(USER_ID)).thenReturn(user);
        when(userService.isPasswordMatch(CURRENT_PASSWORD, user.getPassword())).thenReturn(true);
        when(mfaService.beginSelfEnrollment(USER_ID, "admin")).thenReturn("enrollment-challenge");
        when(mfaService.beginRequiredTotpEnrollment("enrollment-challenge")).thenReturn(setup);
        MockHttpServletResponse response = new MockHttpServletResponse();

        CommonResult<AuthMfaTotpSetupRespVO> result;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            result = controller.startMfaTotpEnrollment(enrollmentStartRequest(), response);
        }

        assertThat(result.getData().getEnrollmentToken()).isEqualTo("enrollment-token");
        assertSensitiveResponseCachingDisabled(response);
        verify(mfaService).beginSelfEnrollment(USER_ID, "admin");
        verify(mfaService).beginRequiredTotpEnrollment("enrollment-challenge");
    }

    @Test
    void selfTotpEnrollment_rejectsAnInvalidCurrentPasswordBeforeCreatingAChallenge() {
        AdminUserDO user = user();
        when(userService.getUser(USER_ID)).thenReturn(user);
        when(userService.isPasswordMatch(CURRENT_PASSWORD, user.getPassword())).thenReturn(false);

        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            assertThatThrownBy(() ->
                            controller.startMfaTotpEnrollment(enrollmentStartRequest(), new MockHttpServletResponse()))
                    .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                            .isEqualTo(USER_PASSWORD_FAILED.getCode()));
        }

        verify(mfaService, never()).beginSelfEnrollment(any(), any());
    }

    @Test
    void selfTotpEnrollment_rejectsADeletedCurrentUserBeforeCheckingThePassword() {
        when(userService.getUser(USER_ID)).thenReturn(null);

        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            assertThatThrownBy(() ->
                            controller.startMfaTotpEnrollment(enrollmentStartRequest(), new MockHttpServletResponse()))
                    .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                            .isEqualTo(USER_PASSWORD_FAILED.getCode()));
        }

        verify(userService, never()).isPasswordMatch(any(), any());
        verify(mfaService, never()).beginSelfEnrollment(any(), any());
    }

    @Test
    void selfMfaEnrollmentCompletionReturnsRecoveryCodesOnlyInTheSensitiveResponse() {
        MfaVerifiedPrincipalDTO principal = MfaVerifiedPrincipalDTO.builder()
                .userId(USER_ID)
                .recoveryCodes(List.of("ABCD-EFGH-IJKL-MNOP"))
                .build();
        when(mfaService.completeSelfTotpEnrollment(USER_ID, "mfa-token", "123456"))
                .thenReturn(principal);
        when(mfaService.completeSelfWebAuthnEnrollment(USER_ID, "ceremony-token", "{\"id\":\"credential\"}"))
                .thenReturn(principal);
        MockHttpServletResponse totpResponse = new MockHttpServletResponse();
        MockHttpServletResponse webAuthnResponse = new MockHttpServletResponse();

        CommonResult<UserProfileMfaRecoveryCodesRespVO> totpResult;
        CommonResult<UserProfileMfaRecoveryCodesRespVO> webAuthnResult;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            totpResult = controller.finishMfaTotpEnrollment(totpRequest("mfa-token", "123456"), totpResponse);
            webAuthnResult = controller.finishMfaWebAuthnEnrollment(
                    webAuthnFinishRequest("ceremony-token", "{\"id\":\"credential\"}"), webAuthnResponse);
        }

        assertThat(totpResult.getData().getRecoveryCodes()).containsExactly("ABCD-EFGH-IJKL-MNOP");
        assertThat(webAuthnResult.getData().getRecoveryCodes()).containsExactly("ABCD-EFGH-IJKL-MNOP");
        assertSensitiveResponseCachingDisabled(totpResponse);
        assertSensitiveResponseCachingDisabled(webAuthnResponse);
    }

    @Test
    void selfWebAuthnEnrollment_requiresCurrentPasswordAndMapsBrowserOptions() {
        AdminUserDO user = user();
        MfaWebAuthnOptionsDTO options = MfaWebAuthnOptionsDTO.builder()
                .ceremonyToken("ceremony-token")
                .optionsJson("{\"challenge\":\"example\"}")
                .build();
        when(userService.getUser(USER_ID)).thenReturn(user);
        when(userService.isPasswordMatch(CURRENT_PASSWORD, user.getPassword())).thenReturn(true);
        when(mfaService.beginSelfEnrollment(USER_ID, "admin")).thenReturn("enrollment-challenge");
        when(mfaService.beginRequiredWebAuthnEnrollment("enrollment-challenge")).thenReturn(options);
        MockHttpServletResponse response = new MockHttpServletResponse();

        CommonResult<AuthMfaWebAuthnOptionsRespVO> result;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            result = controller.startMfaWebAuthnEnrollment(enrollmentStartRequest(), response);
        }

        assertThat(result.getData().getCeremonyToken()).isEqualTo("ceremony-token");
        assertThat(result.getData().getOptionsJson()).isEqualTo("{\"challenge\":\"example\"}");
        assertSensitiveResponseCachingDisabled(response);
    }

    @Test
    void managedTotpEnrollmentDelegatesToTheFactorManagementService() {
        AdminUserDO user = user();
        MfaTotpSetupDTO setup = MfaTotpSetupDTO.builder()
                .enrollmentToken("enrollment-token")
                .secret("secret")
                .otpauthUri("otpauth://totp/example")
                .build();
        when(userService.getUser(USER_ID)).thenReturn(user);
        when(mfaFactorManagementService.beginTotpEnrollment(USER_ID, "admin")).thenReturn(setup);
        when(mfaFactorManagementService.completeTotpEnrollment(USER_ID, "mfa-token", "123456"))
                .thenReturn(List.of("ABCD-EFGH-IJKL-MNOP"));
        MockHttpServletResponse startResponse = new MockHttpServletResponse();
        MockHttpServletResponse finishResponse = new MockHttpServletResponse();

        CommonResult<AuthMfaTotpSetupRespVO> startResult;
        CommonResult<UserProfileMfaRecoveryCodesRespVO> finishResult;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            startResult = controller.startManagedTotpEnrollment(startResponse);
            finishResult = controller.finishManagedTotpEnrollment(totpRequest("mfa-token", "123456"), finishResponse);
        }

        assertThat(startResult.getData().getSecret()).isEqualTo("secret");
        assertThat(finishResult.getData().getRecoveryCodes()).containsExactly("ABCD-EFGH-IJKL-MNOP");
        assertSensitiveResponseCachingDisabled(startResponse);
        assertSensitiveResponseCachingDisabled(finishResponse);
        verify(mfaFactorManagementService).beginTotpEnrollment(USER_ID, "admin");
        verify(mfaFactorManagementService).completeTotpEnrollment(USER_ID, "mfa-token", "123456");
    }

    @Test
    void managedWebAuthnEnrollmentAndFactorMaintenanceUseTheCurrentUser() {
        AdminUserDO user = user();
        MfaWebAuthnOptionsDTO options = MfaWebAuthnOptionsDTO.builder()
                .ceremonyToken("ceremony-token")
                .optionsJson("{\"challenge\":\"example\"}")
                .build();
        when(userService.getUser(USER_ID)).thenReturn(user);
        when(mfaFactorManagementService.beginWebAuthnEnrollment(USER_ID, "admin"))
                .thenReturn(options);
        when(mfaFactorManagementService.resetRecoveryCodes(USER_ID)).thenReturn(List.of("ABCD-EFGH-IJKL-MNOP"));
        MockHttpServletResponse startResponse = new MockHttpServletResponse();
        MockHttpServletResponse resetResponse = new MockHttpServletResponse();

        CommonResult<AuthMfaWebAuthnOptionsRespVO> startResult;
        CommonResult<Boolean> finishResult;
        CommonResult<Boolean> removeResult;
        CommonResult<UserProfileMfaRecoveryCodesRespVO> resetResult;
        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockCurrentUser()) {
            startResult = controller.startManagedWebAuthnEnrollment(startResponse);
            finishResult = controller.finishManagedWebAuthnEnrollment(
                    webAuthnFinishRequest("ceremony-token", "{\"id\":\"credential\"}"));
            removeResult = controller.removeMfaFactor(8L);
            resetResult = controller.resetMfaRecoveryCodes(resetResponse);
        }

        assertThat(startResult.getData().getCeremonyToken()).isEqualTo("ceremony-token");
        assertThat(finishResult.getData()).isTrue();
        assertThat(removeResult.getData()).isTrue();
        assertThat(resetResult.getData().getRecoveryCodes()).containsExactly("ABCD-EFGH-IJKL-MNOP");
        assertSensitiveResponseCachingDisabled(startResponse);
        assertSensitiveResponseCachingDisabled(resetResponse);
        verify(mfaFactorManagementService)
                .completeWebAuthnEnrollment(USER_ID, "ceremony-token", "{\"id\":\"credential\"}");
        verify(mfaFactorManagementService).removeFactor(USER_ID, 8L);
        verify(mfaFactorManagementService).resetRecoveryCodes(USER_ID);
    }

    private static AdminUserDO user() {
        return new AdminUserDO().setId(USER_ID).setUsername("admin").setPassword("stored-password-hash");
    }

    private static UserProfileMfaEnrollmentStartReqVO enrollmentStartRequest() {
        UserProfileMfaEnrollmentStartReqVO request = new UserProfileMfaEnrollmentStartReqVO();
        request.setPassword(CURRENT_PASSWORD);
        return request;
    }

    private static AuthMfaTotpVerifyReqVO totpRequest(String mfaToken, String code) {
        AuthMfaTotpVerifyReqVO request = new AuthMfaTotpVerifyReqVO();
        request.setMfaToken(mfaToken);
        request.setCode(code);
        return request;
    }

    private static AuthMfaWebAuthnFinishReqVO webAuthnFinishRequest(String ceremonyToken, String credentialJson) {
        AuthMfaWebAuthnFinishReqVO request = new AuthMfaWebAuthnFinishReqVO();
        request.setCeremonyToken(ceremonyToken);
        request.setCredentialJson(credentialJson);
        return request;
    }

    private static MockedStatic<SecurityFrameworkUtils> mockCurrentUser() {
        MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class);
        securityUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);
        return securityUtils;
    }

    private static void assertSensitiveResponseCachingDisabled(MockHttpServletResponse response) {
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }
}
