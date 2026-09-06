package com.basicframework.module.system.service.auth;

import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CHALLENGE_INVALID;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_NOT_CONFIGURED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_STEP_UP_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.redis.auth.MfaStepUpRedisDAO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 覆盖敏感操作二次验证通道的参数防御、失败审计与挑战绑定校验。 */
@ExtendWith(MockitoExtension.class)
class MfaStepUpFlowTest {

    @Mock
    private MfaMethodPolicy methodPolicy;

    @Mock
    private MfaChallengeManager challengeManager;

    @Mock
    private MfaStepUpRedisDAO stepUpRedisDAO;

    @Mock
    private MfaCredentialVerifier credentialVerifier;

    @Mock
    private MfaAuthenticationAudit authenticationAudit;

    private MfaStepUpFlow flow;

    @BeforeEach
    void setUp() {
        flow = new MfaStepUpFlow(
                methodPolicy, challengeManager, stepUpRedisDAO, credentialVerifier, authenticationAudit);
    }

    @Test
    void begin_requiresAnExplicitUserIdAndAccessTokenBeforeIssuingAChallenge() {
        assertThatThrownBy(() -> flow.begin(null, "access-token"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_STEP_UP_REQUIRED.getCode()));
        assertThatThrownBy(() -> flow.begin(1L, " "))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_STEP_UP_REQUIRED.getCode()));

        verifyNoInteractions(challengeManager);
    }

    @Test
    void begin_rejectsUsersWithoutAnyEnabledFactor() {
        when(methodPolicy.enabledFactors(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> flow.begin(1L, "access-token"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_NOT_CONFIGURED.getCode()));

        verifyNoInteractions(challengeManager);
    }

    @Test
    void completeTotp_recordsAuditEntryAndRethrowsWhenVerificationFails() {
        MfaChallengeDTO challenge = stepUpChallenge();
        when(challengeManager.consume("mfa-token", MfaChallengePurposeEnum.STEP_UP))
                .thenReturn(challenge);
        doThrow(new IllegalStateException("totp mismatch"))
                .when(credentialVerifier)
                .verifyTotp(1L, "123456");

        assertThatThrownBy(() -> flow.completeTotp("mfa-token", "123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("totp mismatch");

        verify(authenticationAudit).recordFailure(challenge);
        verify(stepUpRedisDAO, never()).setByHash(any(), any());
    }

    @Test
    void completeRecoveryCode_recordsAuditEntryAndRethrowsWhenConsumptionFails() {
        MfaChallengeDTO challenge = stepUpChallenge();
        when(challengeManager.consume("mfa-token", MfaChallengePurposeEnum.STEP_UP))
                .thenReturn(challenge);
        doThrow(new IllegalStateException("recovery code invalid"))
                .when(credentialVerifier)
                .consumeRecoveryCode(1L, "ABCD-2345-EFGH-67YZ");

        assertThatThrownBy(() -> flow.completeRecoveryCode("mfa-token", "ABCD-2345-EFGH-67YZ"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recovery code invalid");

        verify(authenticationAudit).recordFailure(challenge);
        verify(stepUpRedisDAO, never()).setByHash(any(), any());
    }

    @Test
    void completeTotp_rejectsChallengesWithoutAnAccessTokenBinding() {
        MfaChallengeDTO challenge = stepUpChallenge();
        when(challengeManager.consume("mfa-token", MfaChallengePurposeEnum.STEP_UP))
                .thenReturn(challenge);

        assertThatThrownBy(() -> flow.completeTotp("mfa-token", "123456"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_CHALLENGE_INVALID.getCode()));

        verify(credentialVerifier).verifyTotp(1L, "123456");
        verifyNoInteractions(stepUpRedisDAO);
    }

    @Test
    void require_passesThroughWhenMfaCapabilityIsDisabled() {
        when(methodPolicy.isEnabled()).thenReturn(false);

        flow.require("access-token", 1L);

        verifyNoInteractions(stepUpRedisDAO);
    }

    @Test
    void require_passesThroughWhenTheStepUpMarkerMatchesTheSession() {
        when(methodPolicy.isEnabled()).thenReturn(true);
        when(stepUpRedisDAO.matches("access-token", 1L)).thenReturn(true);

        flow.require("access-token", 1L);

        verify(stepUpRedisDAO).matches("access-token", 1L);
    }

    private static MfaChallengeDTO stepUpChallenge() {
        return MfaChallengeDTO.builder()
                .userId(1L)
                .username("admin")
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.STEP_UP)
                .build();
    }
}
