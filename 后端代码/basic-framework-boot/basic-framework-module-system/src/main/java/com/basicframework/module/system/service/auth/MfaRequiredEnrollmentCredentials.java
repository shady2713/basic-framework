package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_ALREADY_CONFIGURED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CODE_INVALID;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_WEBAUTHN_INVALID;

import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** 生成并持久化登录阶段首次注册所需的 TOTP 与 WebAuthn 凭据。 */
@Component
public class MfaRequiredEnrollmentCredentials {

    private static final int USER_HANDLE_BYTES = 32;
    private static final int MAX_CREDENTIAL_ID_BYTES = 1024;
    private static final int MAX_PUBLIC_KEY_BYTES = 16_384;

    private final SecureRandom secureRandom = new SecureRandom();
    private final MfaFactorMapper factorMapper;
    private final MfaSecretCrypto secretCrypto;
    private final TotpAuthenticator totpAuthenticator;
    private final WebAuthnService webAuthnService;

    public MfaRequiredEnrollmentCredentials(
            MfaFactorMapper factorMapper,
            MfaSecretCrypto secretCrypto,
            TotpAuthenticator totpAuthenticator,
            WebAuthnService webAuthnService) {
        this.factorMapper = factorMapper;
        this.secretCrypto = secretCrypto;
        this.totpAuthenticator = totpAuthenticator;
        this.webAuthnService = webAuthnService;
    }

    public TotpMaterial beginTotp(Long userId) {
        requireTotpAbsent(userId);
        String secret = totpAuthenticator.generateSecret();
        return new TotpMaterial(secret, secretCrypto.encrypt(secret, userId));
    }

    public LocalDateTime completeTotp(MfaChallengeDTO challenge, String code) {
        requireTotpAbsent(challenge.getUserId());
        String secret = secretCrypto.decrypt(challenge.getEncryptedTotpSecret(), challenge.getUserId());
        OptionalLong verifiedStep = totpAuthenticator.verify(secret, code);
        if (verifiedStep.isEmpty()) {
            throw exception(AUTH_MFA_CODE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            factorMapper.insert(MfaFactorDO.builder()
                    .userId(challenge.getUserId())
                    .factorType(MfaFactorTypeEnum.TOTP.getType())
                    .name("TOTP")
                    .secretCiphertext(challenge.getEncryptedTotpSecret())
                    .signatureCount(0L)
                    .lastUsedStep(verifiedStep.getAsLong())
                    .enabled(true)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        } catch (DuplicateKeyException duplicateFactor) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
        return now;
    }

    public WebAuthnMaterial beginWebAuthn(Long userId, String username) {
        requireWebAuthnAbsent(userId);
        byte[] userHandle = new byte[USER_HANDLE_BYTES];
        secureRandom.nextBytes(userHandle);
        WebAuthnService.CeremonyOptions options = webAuthnService.startRegistration(userId, username, userHandle);
        return new WebAuthnMaterial(userHandle, options.requestJson(), options.browserOptionsJson());
    }

    public LocalDateTime completeWebAuthn(MfaChallengeDTO challenge, String credentialJson) {
        requireWebAuthnAbsent(challenge.getUserId());
        WebAuthnService.RegistrationOutcome result =
                webAuthnService.finishRegistration(challenge.getWebAuthnRequestJson(), credentialJson);
        validateRegistration(result);
        LocalDateTime now = LocalDateTime.now();
        try {
            factorMapper.insert(MfaFactorDO.builder()
                    .userId(challenge.getUserId())
                    .factorType(MfaFactorTypeEnum.WEBAUTHN.getType())
                    .name(DigestUtil.sha256Hex(result.credentialId()))
                    .credentialId(result.credentialId())
                    .userHandle(challenge.getWebAuthnUserHandle())
                    .publicKeyCose(result.publicKeyCose())
                    .signatureCount(result.signatureCount())
                    .backupEligible(result.backupEligible())
                    .backupState(result.backupState())
                    .transports(JsonUtils.toJsonString(result.transports()))
                    .enabled(true)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        } catch (DuplicateKeyException duplicateCredential) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
        return now;
    }

    private void requireTotpAbsent(Long userId) {
        if (factorMapper.selectEnabledByUserIdAndType(userId, MfaFactorTypeEnum.TOTP.getType()) != null) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
    }

    private void requireWebAuthnAbsent(Long userId) {
        List<MfaFactorDO> factors =
                factorMapper.selectEnabledByUserIdAndTypeList(userId, MfaFactorTypeEnum.WEBAUTHN.getType());
        if (!factors.isEmpty()) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
    }

    private static void validateRegistration(WebAuthnService.RegistrationOutcome result) {
        byte[] credentialId = result.credentialId();
        byte[] publicKeyCose = result.publicKeyCose();
        if (!result.userVerified()
                || credentialId.length == 0
                || credentialId.length > MAX_CREDENTIAL_ID_BYTES
                || publicKeyCose.length == 0
                || publicKeyCose.length > MAX_PUBLIC_KEY_BYTES) {
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
    }

    public record TotpMaterial(String secret, String encryptedSecret) {}

    public record WebAuthnMaterial(byte[] userHandle, String requestJson, String browserOptionsJson) {}
}
