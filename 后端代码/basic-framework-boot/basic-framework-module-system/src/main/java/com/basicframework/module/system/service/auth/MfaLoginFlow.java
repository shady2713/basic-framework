package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_ALREADY_CONFIGURED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CHALLENGE_INVALID;

import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 编排第一因子通过后的 MFA 登录挑战与凭据验证。 */
@Component
public class MfaLoginFlow {

    private final MfaMethodPolicy methodPolicy;
    private final MfaChallengeManager challengeManager;
    private final MfaCredentialVerifier credentialVerifier;
    private final MfaAuthenticationAudit authenticationAudit;

    public MfaLoginFlow(
            MfaMethodPolicy methodPolicy,
            MfaChallengeManager challengeManager,
            MfaCredentialVerifier credentialVerifier,
            MfaAuthenticationAudit authenticationAudit) {
        this.methodPolicy = methodPolicy;
        this.challengeManager = challengeManager;
        this.credentialVerifier = credentialVerifier;
        this.authenticationAudit = authenticationAudit;
    }

    public AuthLoginResultDTO beginAuthentication(AdminUserDO user, String loginIdentity, LoginLogTypeEnum logType) {
        if (!methodPolicy.isEnabled()) {
            return null;
        }
        List<MfaFactorDO> factors = methodPolicy.enabledFactors(user.getId());
        if (!methodPolicy.requiresAuthentication(user.getId(), factors)) {
            return null;
        }
        boolean enrollmentRequired = factors.isEmpty();
        MfaChallengePurposeEnum purpose =
                enrollmentRequired ? MfaChallengePurposeEnum.REQUIRED_ENROLLMENT : MfaChallengePurposeEnum.LOGIN;
        String token = challengeManager.save(MfaChallengeDTO.builder()
                .userId(user.getId())
                .username(loginIdentity)
                .loginLogType(logType.getType())
                .purpose(purpose)
                .build());
        return AuthLoginResultDTO.builder()
                .userId(user.getId())
                .mfaRequired(true)
                .mfaEnrollmentRequired(enrollmentRequired)
                .mfaToken(token)
                .mfaMethods(
                        enrollmentRequired ? methodPolicy.enrollmentMethods() : methodPolicy.availableMethods(factors))
                .build();
    }

    public MfaVerifiedPrincipalDTO verifyTotp(String mfaToken, String code) {
        methodPolicy.requireEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(mfaToken, MfaChallengePurposeEnum.LOGIN);
        verify(challenge, () -> credentialVerifier.verifyTotp(challenge.getUserId(), code));
        return principal(challenge);
    }

    public MfaVerifiedPrincipalDTO verifyRecoveryCode(String mfaToken, String recoveryCode) {
        methodPolicy.requireEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(mfaToken, MfaChallengePurposeEnum.LOGIN);
        verify(challenge, () -> credentialVerifier.consumeRecoveryCode(challenge.getUserId(), recoveryCode));
        return principal(challenge);
    }

    public MfaWebAuthnOptionsDTO beginWebAuthn(String mfaToken) {
        methodPolicy.requireWebAuthnEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(mfaToken, MfaChallengePurposeEnum.LOGIN);
        WebAuthnService.CeremonyOptions options = credentialVerifier.beginWebAuthn(challenge.getUserId());
        String ceremonyToken = challengeManager.save(MfaChallengeDTO.builder()
                .userId(challenge.getUserId())
                .username(challenge.getUsername())
                .loginLogType(challenge.getLoginLogType())
                .purpose(MfaChallengePurposeEnum.WEBAUTHN_AUTHENTICATION)
                .webAuthnRequestJson(options.requestJson())
                .build());
        return MfaWebAuthnOptionsDTO.builder()
                .ceremonyToken(ceremonyToken)
                .optionsJson(options.browserOptionsJson())
                .build();
    }

    public MfaVerifiedPrincipalDTO verifyWebAuthn(String ceremonyToken, String credentialJson) {
        methodPolicy.requireWebAuthnEnabled();
        MfaChallengeDTO challenge =
                challengeManager.consume(ceremonyToken, MfaChallengePurposeEnum.WEBAUTHN_AUTHENTICATION);
        verify(challenge, () -> credentialVerifier.verifyWebAuthn(challenge, credentialJson));
        return principal(challenge);
    }

    public String beginSelfEnrollment(Long userId, String username) {
        methodPolicy.requireEnabled();
        if (userId == null || !StringUtils.hasText(username)) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        if (!methodPolicy.enabledFactors(userId).isEmpty()) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
        return challengeManager.save(MfaChallengeDTO.builder()
                .userId(userId)
                .username(username)
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.REQUIRED_ENROLLMENT)
                .build());
    }

    private void verify(MfaChallengeDTO challenge, Runnable verification) {
        try {
            verification.run();
        } catch (RuntimeException failure) {
            authenticationAudit.recordFailure(challenge);
            throw failure;
        }
    }

    private static MfaVerifiedPrincipalDTO principal(MfaChallengeDTO challenge) {
        return MfaVerifiedPrincipalDTO.builder()
                .userId(challenge.getUserId())
                .username(challenge.getUsername())
                .loginLogType(challenge.getLoginLogType())
                .build();
    }
}
