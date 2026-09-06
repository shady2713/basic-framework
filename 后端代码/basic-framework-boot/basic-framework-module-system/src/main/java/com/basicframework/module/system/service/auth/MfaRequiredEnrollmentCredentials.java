package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_ALREADY_CONFIGURED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CODE_INVALID;

import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import java.time.LocalDateTime;
import java.util.OptionalLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** 生成并持久化登录阶段首次注册所需的 TOTP 凭据。 */
@Component
public class MfaRequiredEnrollmentCredentials {

    private final MfaFactorMapper factorMapper;
    private final MfaSecretCrypto secretCrypto;
    private final TotpAuthenticator totpAuthenticator;

    public MfaRequiredEnrollmentCredentials(
            MfaFactorMapper factorMapper, MfaSecretCrypto secretCrypto, TotpAuthenticator totpAuthenticator) {
        this.factorMapper = factorMapper;
        this.secretCrypto = secretCrypto;
        this.totpAuthenticator = totpAuthenticator;
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

    private void requireTotpAbsent(Long userId) {
        if (factorMapper.selectEnabledByUserIdAndType(userId, MfaFactorTypeEnum.TOTP.getType()) != null) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
    }

    public record TotpMaterial(String secret, String encryptedSecret) {}
}
