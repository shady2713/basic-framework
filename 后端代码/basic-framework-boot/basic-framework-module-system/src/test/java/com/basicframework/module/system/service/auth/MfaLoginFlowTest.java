package com.basicframework.module.system.service.auth;

import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CHALLENGE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 覆盖登录阶段 MFA 挑战编排的开关短路、注册/登录双分支与缺参防御。 */
@ExtendWith(MockitoExtension.class)
class MfaLoginFlowTest {

    @Mock
    private MfaMethodPolicy methodPolicy;

    @Mock
    private MfaChallengeManager challengeManager;

    @Mock
    private MfaCredentialVerifier credentialVerifier;

    @Mock
    private MfaAuthenticationAudit authenticationAudit;

    private MfaLoginFlow flow;

    @BeforeEach
    void setUp() {
        flow = new MfaLoginFlow(methodPolicy, challengeManager, credentialVerifier, authenticationAudit);
    }

    @Test
    void beginAuthentication_returnsNullWhenMfaCapabilityIsDisabled() {
        when(methodPolicy.isEnabled()).thenReturn(false);

        assertThat(flow.beginAuthentication(adminUser(), "admin", LoginLogTypeEnum.LOGIN_USERNAME))
                .isNull();

        verifyNoInteractions(challengeManager);
    }

    @Test
    void beginAuthentication_issuesAnEnrollmentChallengeForFactorlessSuperAdmins() {
        when(methodPolicy.isEnabled()).thenReturn(true);
        when(methodPolicy.enabledFactors(1L)).thenReturn(List.of());
        when(methodPolicy.requiresAuthentication(eq(1L), any())).thenReturn(true);
        when(methodPolicy.enrollmentMethods()).thenReturn(List.of("TOTP"));
        when(challengeManager.save(any(MfaChallengeDTO.class))).thenReturn("mfa-token");

        AuthLoginResultDTO result = flow.beginAuthentication(adminUser(), "admin", LoginLogTypeEnum.LOGIN_USERNAME);

        assertThat(result.getMfaRequired()).isTrue();
        assertThat(result.getMfaEnrollmentRequired()).isTrue();
        assertThat(result.getMfaToken()).isEqualTo("mfa-token");
        assertThat(result.getMfaMethods()).containsExactly("TOTP");
        assertThat(capturedChallenge().getPurpose()).isEqualTo(MfaChallengePurposeEnum.REQUIRED_ENROLLMENT);
    }

    @Test
    void beginAuthentication_issuesALoginChallengeWhenFactorsAlreadyExist() {
        when(methodPolicy.isEnabled()).thenReturn(true);
        List<MfaFactorDO> factors = List.of(MfaFactorDO.builder()
                .factorType(MfaFactorTypeEnum.TOTP.getType())
                .build());
        when(methodPolicy.enabledFactors(1L)).thenReturn(factors);
        when(methodPolicy.requiresAuthentication(eq(1L), any())).thenReturn(true);
        when(methodPolicy.availableMethods(factors)).thenReturn(List.of("TOTP", "RECOVERY_CODE"));
        when(challengeManager.save(any(MfaChallengeDTO.class))).thenReturn("mfa-token");

        AuthLoginResultDTO result = flow.beginAuthentication(adminUser(), "admin", LoginLogTypeEnum.LOGIN_USERNAME);

        assertThat(result.getMfaEnrollmentRequired()).isFalse();
        assertThat(result.getMfaMethods()).containsExactly("TOTP", "RECOVERY_CODE");
        assertThat(capturedChallenge().getPurpose()).isEqualTo(MfaChallengePurposeEnum.LOGIN);
    }

    @Test
    void verifyTotp_returnsThePrincipalBoundToTheConsumedChallenge() {
        MfaChallengeDTO challenge = loginChallenge();
        when(challengeManager.consume("mfa-token", MfaChallengePurposeEnum.LOGIN))
                .thenReturn(challenge);

        MfaVerifiedPrincipalDTO principal = flow.verifyTotp("mfa-token", "123456");

        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getUsername()).isEqualTo("admin");
        assertThat(principal.getLoginLogType()).isEqualTo(LoginLogTypeEnum.LOGIN_USERNAME.getType());
        verify(credentialVerifier).verifyTotp(1L, "123456");
    }

    @Test
    void beginSelfEnrollment_rejectsMissingUsersOrBlankUsernames() {
        assertThatThrownBy(() -> flow.beginSelfEnrollment(null, "admin"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_CHALLENGE_INVALID.getCode()));
        assertThatThrownBy(() -> flow.beginSelfEnrollment(1L, " "))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_CHALLENGE_INVALID.getCode()));

        verifyNoInteractions(challengeManager);
    }

    private MfaChallengeDTO capturedChallenge() {
        ArgumentCaptor<MfaChallengeDTO> captor = ArgumentCaptor.forClass(MfaChallengeDTO.class);
        verify(challengeManager).save(captor.capture());
        return captor.getValue();
    }

    private static MfaChallengeDTO loginChallenge() {
        return MfaChallengeDTO.builder()
                .userId(1L)
                .username("admin")
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.LOGIN)
                .build();
    }

    private static AdminUserDO adminUser() {
        return AdminUserDO.builder().id(1L).username("admin").build();
    }
}
