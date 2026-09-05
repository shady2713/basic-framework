package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anji.captcha.model.common.ResponseModel;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.AuthResetPasswordDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthSmsSendDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.logger.LoginLogService;
import com.basicframework.module.system.service.logger.dto.LoginLogCreateReqDTO;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.sms.SmsCodeService;
import com.basicframework.module.system.service.sms.dto.SmsCodeSendReqDTO;
import com.basicframework.module.system.service.sms.dto.SmsCodeUseReqDTO;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceImplTest {

    @InjectMocks
    private AdminAuthServiceImpl authService;

    @Mock
    private AdminUserService userService;

    @Mock
    private LoginLogService loginLogService;

    @Mock
    private UserSessionService userSessionService;

    @Mock
    private SmsCodeService smsCodeService;

    @Mock
    private MfaService mfaService;

    @Mock
    private LoginProtectionService loginProtectionService;

    @Mock
    private CaptchaVerificationService captchaVerificationService;

    @Mock
    private PasswordTimingProtectionService passwordTimingProtectionService;

    @Test
    void login_doesNotIssueSessionBeforeMfaCompletion() {
        AuthLoginDTO request = new AuthLoginDTO();
        request.setUsername("admin");
        request.setPassword("correct-password");
        when(captchaVerificationService.verify(request)).thenReturn(ResponseModel.success());
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(0)
                .password("hash")
                .build();
        AuthLoginResultDTO challenge = AuthLoginResultDTO.builder()
                .userId(1L)
                .mfaRequired(true)
                .mfaEnrollmentRequired(false)
                .mfaToken("one-time-token")
                .build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("correct-password", "hash")).thenReturn(true);
        when(mfaService.beginAuthentication(user, "admin", LoginLogTypeEnum.LOGIN_USERNAME))
                .thenReturn(challenge);

        AuthLoginResultDTO result = authService.login(request);

        assertThat(result).isSameAs(challenge);
        assertThat(result.getAccessToken()).isNull();
        verifyNoInteractions(userSessionService, loginLogService);
    }

    @Test
    void login_rejectsCaptchaBeforeLookingUpOrAuthenticatingAccount() {
        AuthLoginDTO request = new AuthLoginDTO();
        request.setUsername("admin");
        ResponseModel rejectedCaptcha = mock(ResponseModel.class);
        when(rejectedCaptcha.isSuccess()).thenReturn(false);
        when(rejectedCaptcha.getRepMsg()).thenReturn("captcha invalid");
        when(captchaVerificationService.verify(request)).thenReturn(rejectedCaptcha);

        assertThatThrownBy(() -> authService.login(request)).hasMessageContaining("验证码不正确");

        verifyNoInteractions(userSessionService, loginProtectionService, mfaService);
    }

    @Test
    void login_issuesSessionOnlyAfterCaptchaPasswordAndMfaChecksSucceed() {
        AuthLoginDTO request = new AuthLoginDTO();
        request.setUsername("admin");
        request.setPassword("correct-password");
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(0)
                .password("hash")
                .build();
        UserSessionDO session =
                new UserSessionDO().setUserId(1L).setAccessToken("access-token").setRefreshToken("refresh-token");
        when(captchaVerificationService.verify(request)).thenReturn(ResponseModel.success());
        when(userService.getUserByUsername(request.getUsername())).thenReturn(user);
        when(userService.isPasswordMatch(request.getPassword(), user.getPassword()))
                .thenReturn(true);
        when(mfaService.beginAuthentication(user, request.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME))
                .thenReturn(null);
        when(userSessionService.createSession(1L, UserTypeEnum.ADMIN.getValue()))
                .thenReturn(session);

        AuthLoginResultDTO result = authService.login(request);

        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
        verify(userSessionService).createSession(1L, UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void completeMfaLogin_rejectsDeletedUserBeforeIssuingToken() {
        MfaVerifiedPrincipalDTO principal = verifiedPrincipal();
        when(userService.getUser(1L)).thenReturn(null);

        assertThatThrownBy(() -> authService.completeMfaLogin(principal)).hasMessageContaining("MFA 验证已失效");
        verifyNoInteractions(userSessionService);
    }

    @Test
    void authenticate_rejectsLockedAccountBeforePasswordCheck() {
        AdminUserDO user =
                AdminUserDO.builder().id(1L).username("admin").password("hash").build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(loginProtectionService.isLocked(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate("admin", "password")).hasMessageContaining("账号密码不正确");

        verify(passwordTimingProtectionService).verifyAgainstDummyHash("password");
        verify(loginLogService).createLoginLog(org.mockito.ArgumentMatchers.any(LoginLogCreateReqDTO.class));
    }

    @Test
    void authenticate_rejectsUnknownUsernameWithTheSameBadCredentialsResponse() {
        when(userService.getUserByUsername("unknown")).thenReturn(null);

        assertThatThrownBy(() -> authService.authenticate("unknown", "password"))
                .hasMessageContaining("账号密码不正确");

        verify(passwordTimingProtectionService).verifyAgainstDummyHash("password");
        verifyNoInteractions(loginProtectionService);
    }

    @Test
    void authenticate_fifthFailureLocksAccount() {
        AdminUserDO user =
                AdminUserDO.builder().id(1L).username("admin").password("hash").build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("wrong-password", "hash")).thenReturn(false);
        when(loginProtectionService.recordFailure(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate("admin", "wrong-password"))
                .hasMessageContaining("账号密码不正确");

        verify(loginProtectionService).recordFailure(1L);
    }

    @Test
    void authenticate_recordsOrdinaryBadCredentialsBeforeTheLockThreshold() {
        AdminUserDO user =
                AdminUserDO.builder().id(1L).username("admin").password("hash").build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("wrong-password", "hash")).thenReturn(false);
        when(loginProtectionService.recordFailure(1L)).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate("admin", "wrong-password"))
                .hasMessageContaining("账号密码不正确");

        verify(loginProtectionService).recordFailure(1L);
    }

    @Test
    void authenticate_successClearsFailures() {
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(0)
                .password("hash")
                .build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("correct-password", "hash")).thenReturn(true);

        assertThat(authService.authenticate("admin", "correct-password")).isSameAs(user);

        verify(userService).upgradePasswordEncodingIfNeeded(1L, "correct-password", "hash");
        verify(loginProtectionService).clear(1L);
    }

    @Test
    void authenticate_rejectsDisabledAccountAfterVerifyingPassword() {
        AdminUserDO user = AdminUserDO.builder()
                .id(1L)
                .username("admin")
                .status(1)
                .password("hash")
                .build();
        when(userService.getUserByUsername("admin")).thenReturn(user);
        when(userService.isPasswordMatch("correct-password", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate("admin", "correct-password"))
                .hasMessageContaining("账号被禁用");

        verify(userService, never())
                .upgradePasswordEncodingIfNeeded(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void completeMfaLogin_rejectsDisabledUserBeforeIssuingToken() {
        MfaVerifiedPrincipalDTO principal = verifiedPrincipal();
        when(userService.getUser(1L))
                .thenReturn(
                        AdminUserDO.builder().id(1L).username("admin").status(1).build());

        assertThatThrownBy(() -> authService.completeMfaLogin(principal)).hasMessageContaining("账号被禁用");
        verifyNoInteractions(userSessionService);
    }

    @Test
    void smsLogin_rejectsDisabledUserBeforeClearingFailuresOrIssuingSession() {
        AuthSmsLoginDTO request = new AuthSmsLoginDTO();
        request.setMobile("13900000001");
        request.setCode("123456");
        when(userService.getUserByMobile("13900000001"))
                .thenReturn(AdminUserDO.builder().id(1L).status(1).build());

        assertThatThrownBy(() -> authService.smsLogin(request)).hasMessageContaining("账号被禁用");

        verifyNoInteractions(loginProtectionService, mfaService, userSessionService);
    }

    @Test
    void smsLogin_consumesOnlyLoginSceneThenDefersSessionUntilMfaCompletion() {
        AuthSmsLoginDTO request = new AuthSmsLoginDTO();
        request.setMobile("13900000001");
        request.setCode("123456");
        AdminUserDO user = AdminUserDO.builder().id(1L).status(0).build();
        AuthLoginResultDTO challenge = AuthLoginResultDTO.builder()
                .mfaRequired(true)
                .mfaToken("mfa-token")
                .build();
        when(userService.getUserByMobile(request.getMobile())).thenReturn(user);
        when(mfaService.beginAuthentication(user, request.getMobile(), LoginLogTypeEnum.LOGIN_MOBILE))
                .thenReturn(challenge);

        AuthLoginResultDTO result = authService.smsLogin(request);

        ArgumentCaptor<SmsCodeUseReqDTO> smsCodeCaptor = ArgumentCaptor.forClass(SmsCodeUseReqDTO.class);
        verify(smsCodeService).useSmsCode(smsCodeCaptor.capture());
        assertThat(smsCodeCaptor.getValue().getScene()).isEqualTo(SmsSceneEnum.ADMIN_MEMBER_LOGIN.getScene());
        assertThat(smsCodeCaptor.getValue().getMobile()).isEqualTo(request.getMobile());
        verify(loginProtectionService).clear(1L);
        verifyNoInteractions(userSessionService);
        assertThat(result).isSameAs(challenge);
    }

    @Test
    void smsLogin_rejectsMissingUserAndIssuesSessionOnlyAfterAValidUser() {
        AuthSmsLoginDTO missingUserRequest = new AuthSmsLoginDTO();
        missingUserRequest.setMobile("13900000001");
        missingUserRequest.setCode("123456");
        when(userService.getUserByMobile(missingUserRequest.getMobile())).thenReturn(null);

        assertThatThrownBy(() -> authService.smsLogin(missingUserRequest)).hasMessageContaining("用户不存在");

        AuthSmsLoginDTO validRequest = new AuthSmsLoginDTO();
        validRequest.setMobile("13900000002");
        validRequest.setCode("654321");
        AdminUserDO user = AdminUserDO.builder().id(2L).status(0).build();
        UserSessionDO session = new UserSessionDO()
                .setUserId(2L)
                .setAccessToken("sms-access-token")
                .setRefreshToken("sms-refresh-token");
        when(userService.getUserByMobile(validRequest.getMobile())).thenReturn(user);
        when(mfaService.beginAuthentication(user, validRequest.getMobile(), LoginLogTypeEnum.LOGIN_MOBILE))
                .thenReturn(null);
        when(userSessionService.createSession(2L, UserTypeEnum.ADMIN.getValue()))
                .thenReturn(session);

        AuthLoginResultDTO result = authService.smsLogin(validRequest);

        assertThat(result.getAccessToken()).isEqualTo("sms-access-token");
        verify(userSessionService).createSession(2L, UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void sendSmsCode_rejectsResetPasswordRequestWhenCaptchaFailsBeforeAccountLookup() {
        AuthSmsSendDTO request = new AuthSmsSendDTO();
        request.setMobile("13900000001");
        request.setScene(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
        ResponseModel rejectedCaptcha = mock(ResponseModel.class);
        when(rejectedCaptcha.isSuccess()).thenReturn(false);
        when(rejectedCaptcha.getRepMsg()).thenReturn("captcha invalid");
        when(captchaVerificationService.verify(request)).thenReturn(rejectedCaptcha);

        assertThatThrownBy(() -> authService.sendSmsCode(request)).hasMessageContaining("验证码不正确");

        verifyNoInteractions(userService, smsCodeService);
    }

    @Test
    void sendSmsCode_returnsGenericAcceptanceForUnknownAccountAndSendsOnlyForExistingAccount() {
        AuthSmsSendDTO missingUserRequest = new AuthSmsSendDTO();
        missingUserRequest.setMobile("13900000001");
        missingUserRequest.setScene(SmsSceneEnum.ADMIN_MEMBER_LOGIN.getScene());
        when(userService.getUserByMobile(missingUserRequest.getMobile())).thenReturn(null);

        authService.sendSmsCode(missingUserRequest);

        verifyNoInteractions(smsCodeService);

        AuthSmsSendDTO validRequest = new AuthSmsSendDTO();
        validRequest.setMobile("13900000002");
        validRequest.setScene(SmsSceneEnum.ADMIN_MEMBER_LOGIN.getScene());
        when(userService.getUserByMobile(validRequest.getMobile()))
                .thenReturn(AdminUserDO.builder().id(2L).build());

        authService.sendSmsCode(validRequest);

        ArgumentCaptor<SmsCodeSendReqDTO> smsCodeCaptor = ArgumentCaptor.forClass(SmsCodeSendReqDTO.class);
        verify(smsCodeService).sendSmsCode(smsCodeCaptor.capture());
        assertThat(smsCodeCaptor.getValue().getMobile()).isEqualTo(validRequest.getMobile());
        assertThat(smsCodeCaptor.getValue().getScene()).isEqualTo(SmsSceneEnum.ADMIN_MEMBER_LOGIN.getScene());
    }

    @Test
    void logoutByAccessTokenId_recordsTheRevokedUser() {
        UserSessionDO session = new UserSessionDO().setId(10L).setUserId(1L).setUserType(UserTypeEnum.ADMIN.getValue());
        when(userSessionService.removeSessionById(10L)).thenReturn(session);
        when(userService.getUser(1L))
                .thenReturn(AdminUserDO.builder().id(1L).username("admin").build());

        authService.logoutByAccessTokenId(10L, LoginLogTypeEnum.LOGOUT_DELETE.getType());

        ArgumentCaptor<LoginLogCreateReqDTO> log = ArgumentCaptor.forClass(LoginLogCreateReqDTO.class);
        verify(loginLogService).createLoginLog(log.capture());
        assertThat(log.getValue().getUserId()).isEqualTo(1L);
        assertThat(log.getValue().getUsername()).isEqualTo("admin");
    }

    @Test
    void refreshToken_delegatesToSessionService() {
        UserSessionDO refreshedSession = new UserSessionDO().setId(10L);
        when(userSessionService.refreshSession("refresh-token")).thenReturn(refreshedSession);

        UserSessionDO result = authService.refreshToken("refresh-token");

        assertThat(result).isSameAs(refreshedSession);
        verify(userSessionService).refreshSession("refresh-token");
    }

    @Test
    void completeMfaLogin_issuesSessionOnlyAfterVerifiedPrincipalAndKeepsRecoveryCodes() {
        MfaVerifiedPrincipalDTO principal = verifiedPrincipal();
        principal.setRecoveryCodes(List.of("recovery-code"));
        UserSessionDO session = new UserSessionDO()
                .setUserId(1L)
                .setAccessToken("access-token")
                .setRefreshToken("refresh-token")
                .setAccessExpiresTime(LocalDateTime.of(2026, 9, 1, 12, 0));
        when(userService.getUser(1L))
                .thenReturn(
                        AdminUserDO.builder().id(1L).username("admin").status(0).build());
        when(userSessionService.createSession(1L, UserTypeEnum.ADMIN.getValue()))
                .thenReturn(session);

        AuthLoginResultDTO result = authService.completeMfaLogin(principal);

        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(result.getRecoveryCodes()).containsExactly("recovery-code");
        verify(userSessionService).createSession(1L, UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void completeMfaLogin_rejectsUnknownLoginLogTypeBeforeLookingUpUser() {
        MfaVerifiedPrincipalDTO principal = MfaVerifiedPrincipalDTO.builder()
                .userId(1L)
                .username("admin")
                .loginLogType(-1)
                .build();

        assertThatThrownBy(() -> authService.completeMfaLogin(principal)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(userService, userSessionService);
    }

    @Test
    void logout_removesSessionFirstAndRecordsOnlySuccessfulRevocations() {
        UserSessionDO session = new UserSessionDO().setUserId(1L).setUserType(UserTypeEnum.ADMIN.getValue());
        when(userSessionService.removeSessionByAccessToken("missing")).thenReturn(null);
        when(userSessionService.removeSessionByAccessToken("active")).thenReturn(session);
        when(userService.getUser(1L))
                .thenReturn(AdminUserDO.builder().id(1L).username("admin").build());

        authService.logout("missing", LoginLogTypeEnum.LOGOUT_SELF.getType());
        verifyNoInteractions(loginLogService);

        authService.logout("active", LoginLogTypeEnum.LOGOUT_SELF.getType());

        verify(loginLogService).createLoginLog(org.mockito.ArgumentMatchers.any(LoginLogCreateReqDTO.class));
    }

    @Test
    void logoutByRefreshToken_recordsOnlyTheSessionActuallyRevoked() {
        UserSessionDO session = new UserSessionDO().setUserId(1L).setUserType(UserTypeEnum.ADMIN.getValue());
        when(userSessionService.removeSessionByRefreshToken("refresh-token")).thenReturn(session);
        when(userService.getUser(1L))
                .thenReturn(AdminUserDO.builder().id(1L).username("admin").build());

        authService.logoutByRefreshToken("refresh-token", LoginLogTypeEnum.LOGOUT_SELF.getType());

        verify(loginLogService).createLoginLog(org.mockito.ArgumentMatchers.any(LoginLogCreateReqDTO.class));
    }

    @Test
    void resetPassword_consumesResetSmsCodeAndClearsAccountLock() {
        AuthResetPasswordDTO request = new AuthResetPasswordDTO();
        request.setMobile("13900000001");
        request.setCode("123456");
        request.setPassword("new-password");
        when(userService.getUserByMobile(request.getMobile()))
                .thenReturn(AdminUserDO.builder().id(1L).build());

        authService.resetPassword(request);

        ArgumentCaptor<SmsCodeUseReqDTO> smsCodeCaptor = ArgumentCaptor.forClass(SmsCodeUseReqDTO.class);
        verify(smsCodeService).useSmsCode(smsCodeCaptor.capture());
        assertThat(smsCodeCaptor.getValue().getMobile()).isEqualTo(request.getMobile());
        assertThat(smsCodeCaptor.getValue().getCode()).isEqualTo(request.getCode());
        assertThat(smsCodeCaptor.getValue().getScene()).isEqualTo(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
        verify(userService).updateUserPassword(1L, request.getPassword());
        verify(loginProtectionService).clear(1L);
    }

    @Test
    void resetPassword_consumesCodeBeforeLookupAndReturnsGenericSuccessWhenAccountWasDeleted() {
        AuthResetPasswordDTO request = new AuthResetPasswordDTO();
        request.setMobile("13900000001");
        request.setCode("123456");
        request.setPassword("new-password");
        when(userService.getUserByMobile(request.getMobile())).thenReturn(null);

        authService.resetPassword(request);

        ArgumentCaptor<SmsCodeUseReqDTO> smsCodeCaptor = ArgumentCaptor.forClass(SmsCodeUseReqDTO.class);
        InOrder order = inOrder(smsCodeService, userService);
        order.verify(smsCodeService).useSmsCode(smsCodeCaptor.capture());
        order.verify(userService).getUserByMobile(request.getMobile());
        assertThat(smsCodeCaptor.getValue().getScene()).isEqualTo(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene());
        verify(userService, never())
                .updateUserPassword(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(loginProtectionService);
    }

    @Test
    void unlockLogin_onlyClearsLockForExistingUser() {
        when(userService.getUser(1L)).thenReturn(AdminUserDO.builder().id(1L).build());

        authService.unlockLogin(1L);

        verify(loginProtectionService).clear(1L);
        assertThatThrownBy(() -> authService.unlockLogin(2L)).hasMessageContaining("用户不存在");
    }

    private static MfaVerifiedPrincipalDTO verifiedPrincipal() {
        return MfaVerifiedPrincipalDTO.builder()
                .userId(1L)
                .username("admin")
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .build();
    }
}
