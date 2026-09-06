package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CHALLENGE_INVALID;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_DISABLED;

import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/** 编排登录阶段的首次 MFA 注册，挑战归属与恢复码轮换在同一事务入口完成。 */
@Component
public class MfaRequiredEnrollmentFlow {

    private final MfaProperties properties;
    private final MfaChallengeManager challengeManager;
    private final MfaRequiredEnrollmentCredentials credentials;
    private final MfaRecoveryCodeManager recoveryCodeManager;
    private final MfaAuthenticationAudit authenticationAudit;

    public MfaRequiredEnrollmentFlow(
            MfaProperties properties,
            MfaChallengeManager challengeManager,
            MfaRequiredEnrollmentCredentials credentials,
            MfaRecoveryCodeManager recoveryCodeManager,
            MfaAuthenticationAudit authenticationAudit) {
        this.properties = properties;
        this.challengeManager = challengeManager;
        this.credentials = credentials;
        this.recoveryCodeManager = recoveryCodeManager;
        this.authenticationAudit = authenticationAudit;
    }

    public MfaTotpSetupDTO beginTotp(String mfaToken) {
        requireEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(mfaToken, MfaChallengePurposeEnum.REQUIRED_ENROLLMENT);
        MfaRequiredEnrollmentCredentials.TotpMaterial material = credentials.beginTotp(challenge.getUserId());
        String enrollmentToken = challengeManager.save(MfaChallengeDTO.builder()
                .userId(challenge.getUserId())
                .username(challenge.getUsername())
                .loginLogType(challenge.getLoginLogType())
                .purpose(MfaChallengePurposeEnum.TOTP_ENROLLMENT)
                .encryptedTotpSecret(material.encryptedSecret())
                .build());
        return MfaTotpSetupDTO.builder()
                .enrollmentToken(enrollmentToken)
                .secret(material.secret())
                .otpauthUri(buildOtpAuthUri(challenge.getUsername(), material.secret()))
                .build();
    }

    public MfaVerifiedPrincipalDTO completeTotp(Long expectedUserId, String enrollmentToken, String code) {
        requireEnabled();
        MfaChallengeDTO challenge =
                consumeOwnedWhenPresent(enrollmentToken, MfaChallengePurposeEnum.TOTP_ENROLLMENT, expectedUserId);
        try {
            LocalDateTime now = credentials.completeTotp(challenge, code);
            return principal(challenge, recoveryCodeManager.replace(challenge.getUserId(), now));
        } catch (RuntimeException failure) {
            authenticationAudit.recordFailure(challenge);
            throw failure;
        }
    }

    public MfaWebAuthnOptionsDTO beginWebAuthn(String mfaToken) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge = challengeManager.consume(mfaToken, MfaChallengePurposeEnum.REQUIRED_ENROLLMENT);
        MfaRequiredEnrollmentCredentials.WebAuthnMaterial material =
                credentials.beginWebAuthn(challenge.getUserId(), challenge.getUsername());
        String ceremonyToken = challengeManager.save(MfaChallengeDTO.builder()
                .userId(challenge.getUserId())
                .username(challenge.getUsername())
                .loginLogType(challenge.getLoginLogType())
                .purpose(MfaChallengePurposeEnum.WEBAUTHN_ENROLLMENT)
                .webAuthnUserHandle(material.userHandle())
                .webAuthnRequestJson(material.requestJson())
                .build());
        return MfaWebAuthnOptionsDTO.builder()
                .ceremonyToken(ceremonyToken)
                .optionsJson(material.browserOptionsJson())
                .build();
    }

    public MfaVerifiedPrincipalDTO completeWebAuthn(Long expectedUserId, String ceremonyToken, String credentialJson) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge =
                consumeOwnedWhenPresent(ceremonyToken, MfaChallengePurposeEnum.WEBAUTHN_ENROLLMENT, expectedUserId);
        try {
            LocalDateTime now = credentials.completeWebAuthn(challenge, credentialJson);
            return principal(challenge, recoveryCodeManager.replace(challenge.getUserId(), now));
        } catch (RuntimeException failure) {
            authenticationAudit.recordFailure(challenge);
            throw failure;
        }
    }

    private MfaChallengeDTO consumeOwnedWhenPresent(
            String token, MfaChallengePurposeEnum purpose, Long expectedUserId) {
        MfaChallengeDTO challenge = challengeManager.consume(token, purpose);
        if (expectedUserId != null && !expectedUserId.equals(challenge.getUserId())) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        return challenge;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw exception(AUTH_MFA_DISABLED);
        }
    }

    private void requireWebAuthnEnabled() {
        requireEnabled();
        if (!properties.getWebauthn().isEnabled()) {
            throw exception(AUTH_MFA_DISABLED);
        }
    }

    private String buildOtpAuthUri(String username, String secret) {
        String issuer = urlEncode(properties.getIssuer());
        String account = urlEncode(properties.getIssuer() + ":" + username);
        return "otpauth://totp/" + account + "?secret=" + secret + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static MfaVerifiedPrincipalDTO principal(MfaChallengeDTO challenge, List<String> recoveryCodes) {
        return MfaVerifiedPrincipalDTO.builder()
                .userId(challenge.getUserId())
                .username(challenge.getUsername())
                .loginLogType(challenge.getLoginLogType())
                .recoveryCodes(recoveryCodes)
                .build();
    }
}
