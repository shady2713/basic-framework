package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CHALLENGE_INVALID;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_NOT_CONFIGURED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_STEP_UP_REQUIRED;

import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.redis.auth.MfaStepUpRedisDAO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 编排与当前访问令牌摘要绑定的敏感操作二次验证。 */
@Component
public class MfaStepUpFlow {

    private final MfaMethodPolicy methodPolicy;
    private final MfaChallengeManager challengeManager;
    private final MfaStepUpRedisDAO stepUpRedisDAO;
    private final MfaCredentialVerifier credentialVerifier;
    private final MfaAuthenticationAudit authenticationAudit;

    public MfaStepUpFlow(
            MfaMethodPolicy methodPolicy,
            MfaChallengeManager challengeManager,
            MfaStepUpRedisDAO stepUpRedisDAO,
            MfaCredentialVerifier credentialVerifier,
            MfaAuthenticationAudit authenticationAudit) {
        this.methodPolicy = methodPolicy;
        this.challengeManager = challengeManager;
        this.stepUpRedisDAO = stepUpRedisDAO;
        this.credentialVerifier = credentialVerifier;
        this.authenticationAudit = authenticationAudit;
    }

    public AuthLoginResultDTO begin(Long userId, String accessToken) {
        methodPolicy.requireEnabled();
        if (userId == null || !StringUtils.hasText(accessToken)) {
            throw exception(AUTH_MFA_STEP_UP_REQUIRED);
        }
        List<MfaFactorDO> factors = methodPolicy.enabledFactors(userId);
        if (factors.isEmpty()) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        String token = challengeManager.save(MfaChallengeDTO.builder()
                .userId(userId)
                .username(String.valueOf(userId))
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.STEP_UP)
                .accessTokenHash(MfaStepUpRedisDAO.tokenHash(accessToken))
                .build());
        return AuthLoginResultDTO.builder()
                .userId(userId)
                .mfaRequired(true)
                .mfaEnrollmentRequired(false)
                .mfaToken(token)
                .mfaMethods(methodPolicy.availableMethods(factors))
                .build();
    }

    public void completeTotp(String mfaToken, String code) {
        methodPolicy.requireEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(mfaToken, MfaChallengePurposeEnum.STEP_UP);
        verify(challenge, () -> credentialVerifier.verifyTotp(challenge.getUserId(), code));
        mark(challenge);
    }

    public void completeRecoveryCode(String mfaToken, String recoveryCode) {
        methodPolicy.requireEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(mfaToken, MfaChallengePurposeEnum.STEP_UP);
        verify(challenge, () -> credentialVerifier.consumeRecoveryCode(challenge.getUserId(), recoveryCode));
        mark(challenge);
    }

    public MfaWebAuthnOptionsDTO beginWebAuthn(String mfaToken) {
        methodPolicy.requireWebAuthnEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(mfaToken, MfaChallengePurposeEnum.STEP_UP);
        WebAuthnService.CeremonyOptions options = credentialVerifier.beginWebAuthn(challenge.getUserId());
        String ceremonyToken = challengeManager.save(MfaChallengeDTO.builder()
                .userId(challenge.getUserId())
                .username(challenge.getUsername())
                .loginLogType(challenge.getLoginLogType())
                .purpose(MfaChallengePurposeEnum.WEBAUTHN_STEP_UP)
                .webAuthnRequestJson(options.requestJson())
                .accessTokenHash(challenge.getAccessTokenHash())
                .build());
        return MfaWebAuthnOptionsDTO.builder()
                .ceremonyToken(ceremonyToken)
                .optionsJson(options.browserOptionsJson())
                .build();
    }

    public void completeWebAuthn(String ceremonyToken, String credentialJson) {
        methodPolicy.requireWebAuthnEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(ceremonyToken, MfaChallengePurposeEnum.WEBAUTHN_STEP_UP);
        verify(challenge, () -> credentialVerifier.verifyWebAuthn(challenge, credentialJson));
        mark(challenge);
    }

    public void require(String accessToken, Long userId) {
        if (!methodPolicy.isEnabled()) {
            return;
        }
        if (!stepUpRedisDAO.matches(accessToken, userId)) {
            throw exception(AUTH_MFA_STEP_UP_REQUIRED);
        }
    }

    private void verify(MfaChallengeDTO challenge, Runnable verification) {
        try {
            verification.run();
        } catch (RuntimeException failure) {
            authenticationAudit.recordFailure(challenge);
            throw failure;
        }
    }

    private void mark(MfaChallengeDTO challenge) {
        if (!StringUtils.hasText(challenge.getAccessTokenHash())) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        stepUpRedisDAO.setByHash(challenge.getAccessTokenHash(), challenge.getUserId());
    }
}
