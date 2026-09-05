package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.OptionalLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 执行已登录用户的 TOTP 新增或密钥轮换流程。 */
@Component
public class MfaTotpEnrollmentManager {

    private final MfaProperties properties;
    private final MfaChallengeManager challengeManager;
    private final MfaFactorMapper factorMapper;
    private final MfaSecretCrypto secretCrypto;
    private final TotpAuthenticator totpAuthenticator;

    public MfaTotpEnrollmentManager(
            MfaProperties properties,
            MfaChallengeManager challengeManager,
            MfaFactorMapper factorMapper,
            MfaSecretCrypto secretCrypto,
            TotpAuthenticator totpAuthenticator) {
        this.properties = properties;
        this.challengeManager = challengeManager;
        this.factorMapper = factorMapper;
        this.secretCrypto = secretCrypto;
        this.totpAuthenticator = totpAuthenticator;
    }

    public MfaTotpSetupDTO begin(Long userId, String username) {
        requireIdentity(userId, username);
        MfaFactorDO current = factorMapper.selectEnabledByUserIdAndType(userId, MfaFactorTypeEnum.TOTP.getType());
        String secret = totpAuthenticator.generateSecret();
        String enrollmentToken = challengeManager.save(MfaChallengeDTO.builder()
                .userId(userId)
                .username(username)
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.TOTP_MANAGEMENT_ENROLLMENT)
                .encryptedTotpSecret(secretCrypto.encrypt(secret, userId))
                .factorId(current == null ? null : current.getId())
                .build());
        return MfaTotpSetupDTO.builder()
                .enrollmentToken(enrollmentToken)
                .secret(secret)
                .otpauthUri(buildOtpAuthUri(username, secret))
                .build();
    }

    public LocalDateTime complete(Long userId, String enrollmentToken, String code) {
        requireEnabled();
        MfaChallengeDTO challenge = challengeManager.consumeOwned(
                enrollmentToken, MfaChallengePurposeEnum.TOTP_MANAGEMENT_ENROLLMENT, userId);
        String secret = secretCrypto.decrypt(challenge.getEncryptedTotpSecret(), userId);
        OptionalLong verifiedStep = totpAuthenticator.verify(secret, code);
        if (verifiedStep.isEmpty()) {
            throw exception(AUTH_MFA_CODE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        persist(challenge, userId, verifiedStep.getAsLong(), now);
        return now;
    }

    private void persist(MfaChallengeDTO challenge, Long userId, long verifiedStep, LocalDateTime now) {
        if (challenge.getFactorId() != null) {
            rotate(challenge, userId, verifiedStep, now);
            return;
        }
        if (factorMapper.selectEnabledByUserIdAndType(userId, MfaFactorTypeEnum.TOTP.getType()) != null) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
        try {
            factorMapper.insert(MfaFactorDO.builder()
                    .userId(userId)
                    .factorType(MfaFactorTypeEnum.TOTP.getType())
                    .name("TOTP")
                    .secretCiphertext(challenge.getEncryptedTotpSecret())
                    .signatureCount(0L)
                    .lastUsedStep(verifiedStep)
                    .enabled(true)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        } catch (DuplicateKeyException duplicateFactor) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
    }

    private void rotate(MfaChallengeDTO challenge, Long userId, long verifiedStep, LocalDateTime now) {
        if (factorMapper.rotateTotpSecret(
                        challenge.getFactorId(),
                        userId,
                        MfaFactorTypeEnum.TOTP.getType(),
                        challenge.getEncryptedTotpSecret(),
                        verifiedStep,
                        now)
                != 1) {
            throw exception(AUTH_MFA_FACTOR_NOT_FOUND);
        }
    }

    private void requireIdentity(Long userId, String username) {
        requireEnabled();
        if (userId == null || !StringUtils.hasText(username)) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
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
}
