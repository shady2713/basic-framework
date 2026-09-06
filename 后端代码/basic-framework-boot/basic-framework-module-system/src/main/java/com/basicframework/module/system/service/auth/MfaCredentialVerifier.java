package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CODE_INVALID;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_NOT_CONFIGURED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_WEBAUTHN_INVALID;

import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.dal.mysql.auth.MfaRecoveryCodeMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 验证已注册的 TOTP、恢复码与 WebAuthn 凭据，并执行防重放状态更新。 */
@Component
public class MfaCredentialVerifier {

    private final MfaFactorMapper factorMapper;
    private final MfaRecoveryCodeMapper recoveryCodeMapper;
    private final MfaSecretCrypto secretCrypto;
    private final TotpAuthenticator totpAuthenticator;
    private final WebAuthnService webAuthnService;

    public MfaCredentialVerifier(
            MfaFactorMapper factorMapper,
            MfaRecoveryCodeMapper recoveryCodeMapper,
            MfaSecretCrypto secretCrypto,
            TotpAuthenticator totpAuthenticator,
            WebAuthnService webAuthnService) {
        this.factorMapper = factorMapper;
        this.recoveryCodeMapper = recoveryCodeMapper;
        this.secretCrypto = secretCrypto;
        this.totpAuthenticator = totpAuthenticator;
        this.webAuthnService = webAuthnService;
    }

    public void verifyTotp(Long userId, String code) {
        MfaFactorDO factor = factorMapper.selectEnabledByUserIdAndType(userId, MfaFactorTypeEnum.TOTP.getType());
        if (factor == null) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        String secret = secretCrypto.decrypt(factor.getSecretCiphertext(), userId);
        OptionalLong verifiedStep = totpAuthenticator.verify(secret, code);
        if (verifiedStep.isEmpty()
                || factorMapper.advanceTotpStep(factor.getId(), verifiedStep.getAsLong(), LocalDateTime.now()) != 1) {
            throw exception(AUTH_MFA_CODE_INVALID);
        }
    }

    public void consumeRecoveryCode(Long userId, String recoveryCode) {
        String normalizedCode = normalizeRecoveryCode(recoveryCode);
        if (normalizedCode == null
                || recoveryCodeMapper.consume(
                                userId, secretCrypto.recoveryCodeHash(normalizedCode), LocalDateTime.now())
                        != 1) {
            throw exception(AUTH_MFA_CODE_INVALID);
        }
    }

    public WebAuthnService.CeremonyOptions beginWebAuthn(Long userId) {
        if (factorMapper
                .selectEnabledByUserIdAndTypeList(userId, MfaFactorTypeEnum.WEBAUTHN.getType())
                .isEmpty()) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        return webAuthnService.startAssertion(userId);
    }

    public void verifyWebAuthn(MfaChallengeDTO challenge, String credentialJson) {
        WebAuthnService.AssertionOutcome result =
                webAuthnService.finishAssertion(challenge.getWebAuthnRequestJson(), credentialJson);
        MfaFactorDO factor =
                factorMapper.selectEnabledByCredentialId(result.credentialId(), MfaFactorTypeEnum.WEBAUTHN.getType());
        if (!result.success()
                || !result.userVerified()
                || !result.signatureCounterValid()
                || !String.valueOf(challenge.getUserId()).equals(result.username())
                || factor == null
                || !challenge.getUserId().equals(factor.getUserId())
                || factorMapper.updateWebAuthnUsage(
                                factor.getId(),
                                factor.getSignatureCount(),
                                result.signatureCount(),
                                result.backupState(),
                                LocalDateTime.now())
                        != 1) {
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
    }

    private static String normalizeRecoveryCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z2-7]{16}") ? normalized : null;
    }
}
