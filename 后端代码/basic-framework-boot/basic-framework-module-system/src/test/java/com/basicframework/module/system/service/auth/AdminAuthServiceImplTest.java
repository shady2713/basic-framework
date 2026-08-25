package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anji.captcha.service.CaptchaService;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.system.api.logger.dto.LoginLogCreateReqDTO;
import com.basicframework.module.system.api.sms.SmsCodeApi;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginDTO;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.logger.LoginLogService;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.user.AdminUserService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private Validator validator;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private SmsCodeApi smsCodeApi;

    @Mock
    private MfaService mfaService;

    @Test
    void login_doesNotIssueSessionBeforeMfaCompletion() {
        authService.setCaptchaEnable(false);
        AuthLoginDTO request = new AuthLoginDTO();
        request.setUsername("admin");
        request.setPassword("correct-password");
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
    void completeMfaLogin_rejectsDeletedUserBeforeIssuingToken() {
        MfaVerifiedPrincipalDTO principal = verifiedPrincipal();
        when(userService.getUser(1L)).thenReturn(null);

        assertThatThrownBy(() -> authService.completeMfaLogin(principal)).hasMessageContaining("MFA 验证已失效");
        verifyNoInteractions(userSessionService);
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

    private static MfaVerifiedPrincipalDTO verifiedPrincipal() {
        return MfaVerifiedPrincipalDTO.builder()
                .userId(1L)
                .username("admin")
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .build();
    }
}
