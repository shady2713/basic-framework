package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;
import static com.basicframework.module.system.enums.LogRecordConstants.*;

import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.dal.redis.auth.MfaChallengeRedisDAO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaFactorDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import com.basicframework.module.system.service.permission.PermissionService;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** MFA 因子管理实现。管理挑战与登录挑战用途隔离，并绑定当前用户。 */
@Service
public class MfaFactorManagementServiceImpl implements MfaFactorManagementService {

    private static final int TOKEN_BYTES = 32;
    private static final int WEB_AUTHN_USER_HANDLE_BYTES = 32;
    private static final int MAX_WEB_AUTHN_CREDENTIAL_ID_BYTES = 1024;
    private static final int MAX_WEB_AUTHN_PUBLIC_KEY_BYTES = 16_384;

    private final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private MfaProperties properties;

    @Resource
    private MfaChallengeRedisDAO challengeRedisDAO;

    @Resource
    private MfaFactorMapper factorMapper;

    @Resource
    private MfaSecretCrypto secretCrypto;

    @Resource
    private MfaRecoveryCodeManager recoveryCodeManager;

    @Resource
    private TotpAuthenticator totpAuthenticator;

    @Resource
    private WebAuthnService webAuthnService;

    @Resource
    private PermissionService permissionService;

    @Override
    public List<MfaFactorDTO> getFactors(Long userId) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        return factorMapper.selectEnabledByUserId(userId).stream()
                .map(MfaFactorManagementServiceImpl::toFactorDTO)
                .toList();
    }

    @Override
    public MfaTotpSetupDTO beginTotpEnrollment(Long userId, String username) {
        requireIdentity(userId, username);
        MfaFactorDO current = factorMapper.selectEnabledByUserIdAndType(userId, MfaFactorTypeEnum.TOTP.getType());
        String secret = totpAuthenticator.generateSecret();
        String encryptedSecret = secretCrypto.encrypt(secret, userId);
        String enrollmentToken = saveChallenge(MfaChallengeDTO.builder()
                .userId(userId)
                .username(username)
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.TOTP_MANAGEMENT_ENROLLMENT)
                .encryptedTotpSecret(encryptedSecret)
                .factorId(current == null ? null : current.getId())
                .build());
        return MfaTotpSetupDTO.builder()
                .enrollmentToken(enrollmentToken)
                .secret(secret)
                .otpauthUri(buildOtpAuthUri(username, secret))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_MFA_ADD_SUB_TYPE,
            bizNo = "{{#userId}}",
            success = SYSTEM_USER_MFA_TOTP_ADD_SUCCESS)
    public List<String> completeTotpEnrollment(Long userId, String enrollmentToken, String code) {
        requireEnabled();
        MfaChallengeDTO challenge =
                consumeChallenge(enrollmentToken, MfaChallengePurposeEnum.TOTP_MANAGEMENT_ENROLLMENT, userId);
        String secret = secretCrypto.decrypt(challenge.getEncryptedTotpSecret(), userId);
        OptionalLong verifiedStep = totpAuthenticator.verify(secret, code);
        if (verifiedStep.isEmpty()) {
            throw exception(AUTH_MFA_CODE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        persistTotpFactor(challenge, userId, verifiedStep.getAsLong(), now);
        return recoveryCodeManager.replace(userId, now);
    }

    @Override
    public MfaWebAuthnOptionsDTO beginWebAuthnEnrollment(Long userId, String username) {
        requireWebAuthnIdentity(userId, username);
        List<MfaFactorDO> existing =
                factorMapper.selectEnabledByUserIdAndTypeList(userId, MfaFactorTypeEnum.WEBAUTHN.getType());
        byte[] userHandle = existing.isEmpty() ? randomBytes(WEB_AUTHN_USER_HANDLE_BYTES) : requireUserHandle(existing);
        WebAuthnService.CeremonyOptions options = webAuthnService.startRegistration(userId, username, userHandle);
        String ceremonyToken = saveChallenge(MfaChallengeDTO.builder()
                .userId(userId)
                .username(username)
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.WEBAUTHN_MANAGEMENT_ENROLLMENT)
                .webAuthnUserHandle(userHandle)
                .webAuthnRequestJson(options.requestJson())
                .build());
        return MfaWebAuthnOptionsDTO.builder()
                .ceremonyToken(ceremonyToken)
                .optionsJson(options.browserOptionsJson())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_MFA_ADD_SUB_TYPE,
            bizNo = "{{#userId}}",
            success = SYSTEM_USER_MFA_WEBAUTHN_ADD_SUCCESS)
    public void completeWebAuthnEnrollment(Long userId, String ceremonyToken, String credentialJson) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge =
                consumeChallenge(ceremonyToken, MfaChallengePurposeEnum.WEBAUTHN_MANAGEMENT_ENROLLMENT, userId);
        WebAuthnService.RegistrationOutcome result =
                webAuthnService.finishRegistration(challenge.getWebAuthnRequestJson(), credentialJson);
        persistWebAuthnFactor(userId, challenge.getWebAuthnUserHandle(), result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_MFA_DELETE_SUB_TYPE,
            bizNo = "{{#userId}}",
            success = SYSTEM_USER_MFA_DELETE_SUCCESS)
    public void removeFactor(Long userId, Long factorId) {
        requireEnabled();
        MfaFactorDO factor = factorMapper.selectEnabledByIdAndUserId(factorId, userId);
        if (factor == null) {
            throw exception(AUTH_MFA_FACTOR_NOT_FOUND);
        }
        List<MfaFactorDO> factors = factorMapper.selectEnabledByUserIdForUpdate(userId);
        if (factors.size() <= 1 && permissionService.hasAnyRoles(userId, RoleCodeEnum.SUPER_ADMIN.getCode())) {
            throw exception(AUTH_MFA_LAST_FACTOR_REQUIRED);
        }
        if (factorMapper.deleteEnabledByIdAndUserId(factorId, userId) != 1) {
            throw exception(AUTH_MFA_FACTOR_NOT_FOUND);
        }
        if (factors.size() <= 1) {
            recoveryCodeManager.deleteByUserId(userId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_MFA_RECOVERY_RESET_SUB_TYPE,
            bizNo = "{{#userId}}",
            success = SYSTEM_USER_MFA_RECOVERY_RESET_SUCCESS)
    public List<String> resetRecoveryCodes(Long userId) {
        requireEnabled();
        if (factorMapper.selectEnabledByUserIdForUpdate(userId).isEmpty()) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        return recoveryCodeManager.replace(userId, LocalDateTime.now());
    }

    private void persistTotpFactor(MfaChallengeDTO challenge, Long userId, long verifiedStep, LocalDateTime now) {
        if (challenge.getFactorId() == null) {
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
            return;
        }
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

    private void persistWebAuthnFactor(Long userId, byte[] userHandle, WebAuthnService.RegistrationOutcome result) {
        byte[] credentialId = result.credentialId();
        byte[] publicKeyCose = result.publicKeyCose();
        if (!result.userVerified()
                || credentialId.length == 0
                || credentialId.length > MAX_WEB_AUTHN_CREDENTIAL_ID_BYTES
                || publicKeyCose.length == 0
                || publicKeyCose.length > MAX_WEB_AUTHN_PUBLIC_KEY_BYTES) {
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            factorMapper.insert(MfaFactorDO.builder()
                    .userId(userId)
                    .factorType(MfaFactorTypeEnum.WEBAUTHN.getType())
                    .name(DigestUtil.sha256Hex(credentialId))
                    .credentialId(credentialId)
                    .userHandle(userHandle)
                    .publicKeyCose(publicKeyCose)
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
    }

    private String saveChallenge(MfaChallengeDTO challenge) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(TOKEN_BYTES));
        challengeRedisDAO.set(token, challenge);
        return token;
    }

    private MfaChallengeDTO consumeChallenge(
            String token, MfaChallengePurposeEnum expectedPurpose, Long expectedUserId) {
        if (!StringUtils.hasText(token) || token.length() > 128) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        MfaChallengeDTO challenge = challengeRedisDAO.getAndDelete(token);
        if (challenge == null
                || challenge.getPurpose() != expectedPurpose
                || !expectedUserId.equals(challenge.getUserId())) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        return challenge;
    }

    private void requireIdentity(Long userId, String username) {
        requireEnabled();
        if (userId == null || !StringUtils.hasText(username)) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
    }

    private void requireWebAuthnIdentity(Long userId, String username) {
        requireWebAuthnEnabled();
        if (userId == null || !StringUtils.hasText(username)) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
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

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private String buildOtpAuthUri(String username, String secret) {
        String issuer = urlEncode(properties.getIssuer());
        String account = urlEncode(properties.getIssuer() + ":" + username);
        return "otpauth://totp/" + account + "?secret=" + secret + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private static byte[] requireUserHandle(List<MfaFactorDO> factors) {
        byte[] userHandle = factors.get(0).getUserHandle();
        if (userHandle == null || userHandle.length == 0) {
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
        return userHandle;
    }

    private static MfaFactorDTO toFactorDTO(MfaFactorDO factor) {
        if (MfaFactorTypeEnum.TOTP.getType().equals(factor.getFactorType())) {
            return MfaFactorDTO.builder()
                    .id(factor.getId())
                    .type("TOTP")
                    .name("动态验证码")
                    .createTime(factor.getCreateTime())
                    .build();
        }
        if (MfaFactorTypeEnum.WEBAUTHN.getType().equals(factor.getFactorType())) {
            String suffix = StringUtils.hasText(factor.getName())
                    ? factor.getName().substring(Math.max(0, factor.getName().length() - 8))
                    : String.valueOf(factor.getId());
            return MfaFactorDTO.builder()
                    .id(factor.getId())
                    .type("WEBAUTHN")
                    .name("安全密钥 · " + suffix)
                    .createTime(factor.getCreateTime())
                    .build();
        }
        throw new IllegalStateException("未知 MFA 因子类型: " + factor.getFactorType());
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
