package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.common.util.monitor.TracerUtils;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.module.system.api.logger.dto.LoginLogCreateReqDTO;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.dal.mysql.auth.MfaRecoveryCodeMapper;
import com.basicframework.module.system.dal.redis.auth.MfaChallengeRedisDAO;
import com.basicframework.module.system.dal.redis.auth.MfaStepUpRedisDAO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.logger.LoginResultEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import com.basicframework.module.system.service.logger.LoginLogService;
import com.basicframework.module.system.service.permission.PermissionService;
import jakarta.annotation.Resource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** MFA 服务实现。挑战均一次性消费，失败后必须重新通过第一因子。 */
@Service
public class MfaServiceImpl implements MfaService {

    private static final int TOKEN_BYTES = 32;
    private static final int WEB_AUTHN_USER_HANDLE_BYTES = 32;
    private static final int MAX_WEB_AUTHN_CREDENTIAL_ID_BYTES = 1024;
    private static final int MAX_WEB_AUTHN_PUBLIC_KEY_BYTES = 16_384;
    private static final String WEB_AUTHN_METHOD = "WEBAUTHN";
    private static final String TOTP_METHOD = "TOTP";
    private static final String RECOVERY_METHOD = "RECOVERY_CODE";

    private final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private MfaProperties properties;

    @Resource
    private MfaChallengeRedisDAO challengeRedisDAO;

    @Resource
    private MfaStepUpRedisDAO stepUpRedisDAO;

    @Resource
    private MfaFactorMapper factorMapper;

    @Resource
    private MfaRecoveryCodeMapper recoveryCodeMapper;

    @Resource
    private MfaRecoveryCodeManager recoveryCodeManager;

    @Resource
    private MfaSecretCrypto secretCrypto;

    @Resource
    private TotpAuthenticator totpAuthenticator;

    @Resource
    private WebAuthnService webAuthnService;

    @Resource
    private PermissionService permissionService;

    @Resource
    private LoginLogService loginLogService;

    @Override
    public AuthLoginResultDTO beginAuthentication(AdminUserDO user, String loginIdentity, LoginLogTypeEnum logType) {
        if (!properties.isEnabled()) {
            return null;
        }
        List<MfaFactorDO> factors = factorMapper.selectEnabledByUserId(user.getId());
        boolean superAdmin = permissionService.hasAnyRoles(user.getId(), RoleCodeEnum.SUPER_ADMIN.getCode());
        if (!superAdmin && factors.isEmpty()) {
            return null;
        }

        boolean enrollmentRequired = factors.isEmpty();
        MfaChallengePurposeEnum purpose =
                enrollmentRequired ? MfaChallengePurposeEnum.REQUIRED_ENROLLMENT : MfaChallengePurposeEnum.LOGIN;
        String token = saveChallenge(MfaChallengeDTO.builder()
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
                .mfaMethods(enrollmentRequired ? enrollmentMethods() : availableMethods(factors))
                .build();
    }

    @Override
    public MfaTotpSetupDTO beginRequiredTotpEnrollment(String mfaToken) {
        requireEnabled();
        MfaChallengeDTO challenge = consumeChallenge(mfaToken, MfaChallengePurposeEnum.REQUIRED_ENROLLMENT);
        if (factorMapper.selectEnabledByUserIdAndType(challenge.getUserId(), MfaFactorTypeEnum.TOTP.getType())
                != null) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
        String secret = totpAuthenticator.generateSecret();
        String encryptedSecret = secretCrypto.encrypt(secret, challenge.getUserId());
        String enrollmentToken = saveChallenge(MfaChallengeDTO.builder()
                .userId(challenge.getUserId())
                .username(challenge.getUsername())
                .loginLogType(challenge.getLoginLogType())
                .purpose(MfaChallengePurposeEnum.TOTP_ENROLLMENT)
                .encryptedTotpSecret(encryptedSecret)
                .build());
        return MfaTotpSetupDTO.builder()
                .enrollmentToken(enrollmentToken)
                .secret(secret)
                .otpauthUri(buildOtpAuthUri(challenge.getUsername(), secret))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO completeRequiredTotpEnrollment(String enrollmentToken, String code) {
        return completeTotpEnrollment(null, enrollmentToken, code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO completeSelfTotpEnrollment(Long userId, String enrollmentToken, String code) {
        return completeTotpEnrollment(userId, enrollmentToken, code);
    }

    private MfaVerifiedPrincipalDTO completeTotpEnrollment(Long expectedUserId, String enrollmentToken, String code) {
        requireEnabled();
        MfaChallengeDTO challenge = consumeChallenge(enrollmentToken, MfaChallengePurposeEnum.TOTP_ENROLLMENT);
        requireChallengeOwner(challenge, expectedUserId);
        if (factorMapper.selectEnabledByUserIdAndType(challenge.getUserId(), MfaFactorTypeEnum.TOTP.getType())
                != null) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
        String secret = secretCrypto.decrypt(challenge.getEncryptedTotpSecret(), challenge.getUserId());
        OptionalLong verifiedStep = totpAuthenticator.verify(secret, code);
        if (verifiedStep.isEmpty()) {
            createMfaFailureLog(challenge);
            throw exception(AUTH_MFA_CODE_INVALID);
        }

        LocalDateTime now = LocalDateTime.now();
        factorMapper.insert(MfaFactorDO.builder()
                .userId(challenge.getUserId())
                .factorType(MfaFactorTypeEnum.TOTP.getType())
                .name(TOTP_METHOD)
                .secretCiphertext(challenge.getEncryptedTotpSecret())
                .signatureCount(0L)
                .lastUsedStep(verifiedStep.getAsLong())
                .enabled(true)
                .createTime(now)
                .updateTime(now)
                .build());
        List<String> recoveryCodes = recoveryCodeManager.replace(challenge.getUserId(), now);
        return principal(challenge, recoveryCodes);
    }

    @Override
    public MfaVerifiedPrincipalDTO verifyTotp(String mfaToken, String code) {
        requireEnabled();
        MfaChallengeDTO challenge = consumeChallenge(mfaToken, MfaChallengePurposeEnum.LOGIN);
        MfaFactorDO factor =
                factorMapper.selectEnabledByUserIdAndType(challenge.getUserId(), MfaFactorTypeEnum.TOTP.getType());
        if (factor == null) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        String secret = secretCrypto.decrypt(factor.getSecretCiphertext(), challenge.getUserId());
        OptionalLong verifiedStep = totpAuthenticator.verify(secret, code);
        if (verifiedStep.isEmpty()
                || factorMapper.advanceTotpStep(factor.getId(), verifiedStep.getAsLong(), LocalDateTime.now()) != 1) {
            createMfaFailureLog(challenge);
            throw exception(AUTH_MFA_CODE_INVALID);
        }
        return principal(challenge, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO verifyRecoveryCode(String mfaToken, String recoveryCode) {
        requireEnabled();
        MfaChallengeDTO challenge = consumeChallenge(mfaToken, MfaChallengePurposeEnum.LOGIN);
        String normalizedCode = normalizeRecoveryCode(recoveryCode);
        if (normalizedCode == null
                || recoveryCodeMapper.consume(
                                challenge.getUserId(),
                                secretCrypto.recoveryCodeHash(normalizedCode),
                                LocalDateTime.now())
                        != 1) {
            createMfaFailureLog(challenge);
            throw exception(AUTH_MFA_CODE_INVALID);
        }
        return principal(challenge, null);
    }

    @Override
    public MfaWebAuthnOptionsDTO beginRequiredWebAuthnEnrollment(String mfaToken) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge = consumeChallenge(mfaToken, MfaChallengePurposeEnum.REQUIRED_ENROLLMENT);
        if (!factorMapper
                .selectEnabledByUserIdAndTypeList(challenge.getUserId(), MfaFactorTypeEnum.WEBAUTHN.getType())
                .isEmpty()) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
        byte[] userHandle = randomBytes(WEB_AUTHN_USER_HANDLE_BYTES);
        WebAuthnService.CeremonyOptions options =
                webAuthnService.startRegistration(challenge.getUserId(), challenge.getUsername(), userHandle);
        String ceremonyToken = saveChallenge(MfaChallengeDTO.builder()
                .userId(challenge.getUserId())
                .username(challenge.getUsername())
                .loginLogType(challenge.getLoginLogType())
                .purpose(MfaChallengePurposeEnum.WEBAUTHN_ENROLLMENT)
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
    public MfaVerifiedPrincipalDTO completeRequiredWebAuthnEnrollment(String ceremonyToken, String credentialJson) {
        return completeWebAuthnEnrollment(null, ceremonyToken, credentialJson);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO completeSelfWebAuthnEnrollment(
            Long userId, String ceremonyToken, String credentialJson) {
        return completeWebAuthnEnrollment(userId, ceremonyToken, credentialJson);
    }

    private MfaVerifiedPrincipalDTO completeWebAuthnEnrollment(
            Long expectedUserId, String ceremonyToken, String credentialJson) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge = consumeChallenge(ceremonyToken, MfaChallengePurposeEnum.WEBAUTHN_ENROLLMENT);
        requireChallengeOwner(challenge, expectedUserId);
        try {
            if (!factorMapper
                    .selectEnabledByUserIdAndTypeList(challenge.getUserId(), MfaFactorTypeEnum.WEBAUTHN.getType())
                    .isEmpty()) {
                throw exception(AUTH_MFA_ALREADY_CONFIGURED);
            }
            WebAuthnService.RegistrationOutcome result =
                    webAuthnService.finishRegistration(challenge.getWebAuthnRequestJson(), credentialJson);
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
            factorMapper.insert(MfaFactorDO.builder()
                    .userId(challenge.getUserId())
                    .factorType(MfaFactorTypeEnum.WEBAUTHN.getType())
                    .name(DigestUtil.sha256Hex(credentialId))
                    .credentialId(credentialId)
                    .userHandle(challenge.getWebAuthnUserHandle())
                    .publicKeyCose(publicKeyCose)
                    .signatureCount(result.signatureCount())
                    .backupEligible(result.backupEligible())
                    .backupState(result.backupState())
                    .transports(JsonUtils.toJsonString(result.transports()))
                    .enabled(true)
                    .createTime(now)
                    .updateTime(now)
                    .build());
            return principal(challenge, recoveryCodeManager.replace(challenge.getUserId(), now));
        } catch (RuntimeException failure) {
            createMfaFailureLog(challenge);
            throw failure;
        }
    }

    @Override
    public MfaWebAuthnOptionsDTO beginWebAuthnAuthentication(String mfaToken) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge = consumeChallenge(mfaToken, MfaChallengePurposeEnum.LOGIN);
        if (factorMapper
                .selectEnabledByUserIdAndTypeList(challenge.getUserId(), MfaFactorTypeEnum.WEBAUTHN.getType())
                .isEmpty()) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        WebAuthnService.CeremonyOptions options = webAuthnService.startAssertion(challenge.getUserId());
        String ceremonyToken = saveChallenge(MfaChallengeDTO.builder()
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

    @Override
    public MfaVerifiedPrincipalDTO verifyWebAuthn(String ceremonyToken, String credentialJson) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge = consumeChallenge(ceremonyToken, MfaChallengePurposeEnum.WEBAUTHN_AUTHENTICATION);
        verifyWebAuthnChallenge(challenge, credentialJson);
        return principal(challenge, null);
    }

    @Override
    public AuthLoginResultDTO beginStepUp(Long userId, String accessToken) {
        requireEnabled();
        if (userId == null || !StringUtils.hasText(accessToken)) {
            throw exception(AUTH_MFA_STEP_UP_REQUIRED);
        }
        List<MfaFactorDO> factors = factorMapper.selectEnabledByUserId(userId);
        if (factors.isEmpty()) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        String token = saveChallenge(MfaChallengeDTO.builder()
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
                .mfaMethods(availableMethods(factors))
                .build();
    }

    @Override
    public void completeStepUpTotp(String mfaToken, String code) {
        requireEnabled();
        MfaChallengeDTO challenge = consumeChallenge(mfaToken, MfaChallengePurposeEnum.STEP_UP);
        MfaFactorDO factor =
                factorMapper.selectEnabledByUserIdAndType(challenge.getUserId(), MfaFactorTypeEnum.TOTP.getType());
        if (factor == null) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        String secret = secretCrypto.decrypt(factor.getSecretCiphertext(), challenge.getUserId());
        OptionalLong verifiedStep = totpAuthenticator.verify(secret, code);
        if (verifiedStep.isEmpty()
                || factorMapper.advanceTotpStep(factor.getId(), verifiedStep.getAsLong(), LocalDateTime.now()) != 1) {
            createMfaFailureLog(challenge);
            throw exception(AUTH_MFA_CODE_INVALID);
        }
        markStepUp(challenge);
    }

    @Override
    public void completeStepUpRecoveryCode(String mfaToken, String recoveryCode) {
        requireEnabled();
        MfaChallengeDTO challenge = consumeChallenge(mfaToken, MfaChallengePurposeEnum.STEP_UP);
        String normalizedCode = normalizeRecoveryCode(recoveryCode);
        if (normalizedCode == null
                || recoveryCodeMapper.consume(
                                challenge.getUserId(),
                                secretCrypto.recoveryCodeHash(normalizedCode),
                                LocalDateTime.now())
                        != 1) {
            createMfaFailureLog(challenge);
            throw exception(AUTH_MFA_CODE_INVALID);
        }
        markStepUp(challenge);
    }

    @Override
    public MfaWebAuthnOptionsDTO beginStepUpWebAuthn(String mfaToken) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge = consumeChallenge(mfaToken, MfaChallengePurposeEnum.STEP_UP);
        if (factorMapper
                .selectEnabledByUserIdAndTypeList(challenge.getUserId(), MfaFactorTypeEnum.WEBAUTHN.getType())
                .isEmpty()) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        WebAuthnService.CeremonyOptions options = webAuthnService.startAssertion(challenge.getUserId());
        String ceremonyToken = saveChallenge(MfaChallengeDTO.builder()
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

    @Override
    public void completeStepUpWebAuthn(String ceremonyToken, String credentialJson) {
        requireWebAuthnEnabled();
        MfaChallengeDTO challenge = consumeChallenge(ceremonyToken, MfaChallengePurposeEnum.WEBAUTHN_STEP_UP);
        verifyWebAuthnChallenge(challenge, credentialJson);
        markStepUp(challenge);
    }

    @Override
    public void requireStepUp(String accessToken, Long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!stepUpRedisDAO.matches(accessToken, userId)) {
            throw exception(AUTH_MFA_STEP_UP_REQUIRED);
        }
    }

    @Override
    public String beginSelfEnrollment(Long userId, String username) {
        requireEnabled();
        if (userId == null || !StringUtils.hasText(username)) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        if (!factorMapper.selectEnabledByUserId(userId).isEmpty()) {
            throw exception(AUTH_MFA_ALREADY_CONFIGURED);
        }
        return saveChallenge(MfaChallengeDTO.builder()
                .userId(userId)
                .username(username)
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.REQUIRED_ENROLLMENT)
                .build());
    }

    @Override
    public List<String> getEnabledMethods(Long userId) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<MfaFactorDO> factors = factorMapper.selectEnabledByUserId(userId);
        if (factors.isEmpty()) {
            return List.of();
        }
        return availableMethods(factors).stream()
                .filter(method -> !RECOVERY_METHOD.equals(method))
                .toList();
    }

    @Override
    public List<String> getEnrollmentMethods() {
        return properties.isEnabled() ? enrollmentMethods() : List.of();
    }

    private void verifyWebAuthnChallenge(MfaChallengeDTO challenge, String credentialJson) {
        try {
            WebAuthnService.AssertionOutcome result =
                    webAuthnService.finishAssertion(challenge.getWebAuthnRequestJson(), credentialJson);
            MfaFactorDO factor = factorMapper.selectEnabledByCredentialId(
                    result.credentialId(), MfaFactorTypeEnum.WEBAUTHN.getType());
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
        } catch (RuntimeException failure) {
            createMfaFailureLog(challenge);
            throw failure;
        }
    }

    private void markStepUp(MfaChallengeDTO challenge) {
        if (!StringUtils.hasText(challenge.getAccessTokenHash())) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        stepUpRedisDAO.setByHash(challenge.getAccessTokenHash(), challenge.getUserId());
    }

    private String saveChallenge(MfaChallengeDTO challenge) {
        String token = randomToken(TOKEN_BYTES);
        challengeRedisDAO.set(token, challenge);
        return token;
    }

    private MfaChallengeDTO consumeChallenge(String token, MfaChallengePurposeEnum expectedPurpose) {
        if (!StringUtils.hasText(token) || token.length() > 128) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        MfaChallengeDTO challenge = challengeRedisDAO.getAndDelete(token);
        if (challenge == null || challenge.getPurpose() != expectedPurpose) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        return challenge;
    }

    private static void requireChallengeOwner(MfaChallengeDTO challenge, Long expectedUserId) {
        if (expectedUserId != null && !expectedUserId.equals(challenge.getUserId())) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
    }

    private List<String> availableMethods(List<MfaFactorDO> factors) {
        boolean webAuthnAvailable = factors.stream()
                .anyMatch(factor -> MfaFactorTypeEnum.WEBAUTHN.getType().equals(factor.getFactorType()));
        boolean totpAvailable = factors.stream()
                .anyMatch(factor -> MfaFactorTypeEnum.TOTP.getType().equals(factor.getFactorType()));
        List<String> methods = new ArrayList<>(3);
        if (webAuthnAvailable && properties.getWebauthn().isEnabled()) {
            methods.add(WEB_AUTHN_METHOD);
        }
        if (totpAvailable) {
            methods.add(TOTP_METHOD);
        }
        methods.add(RECOVERY_METHOD);
        return List.copyOf(methods);
    }

    private List<String> enrollmentMethods() {
        return properties.getWebauthn().isEnabled() ? List.of(WEB_AUTHN_METHOD, TOTP_METHOD) : List.of(TOTP_METHOD);
    }

    private String buildOtpAuthUri(String username, String secret) {
        String issuer = urlEncode(properties.getIssuer());
        String account = urlEncode(properties.getIssuer() + ":" + username);
        return "otpauth://totp/" + account + "?secret=" + secret + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private void createMfaFailureLog(MfaChallengeDTO challenge) {
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(challenge.getLoginLogType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(challenge.getUserId());
        reqDTO.setUserType(UserTypeEnum.ADMIN.getValue());
        reqDTO.setUsername(challenge.getUsername());
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(LoginResultEnum.MFA_CODE_ERROR.getResult());
        loginLogService.createLoginLog(reqDTO);
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

    private String randomToken(int length) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(length));
    }

    private static String normalizeRecoveryCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.replace("-", "").trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z2-7]{16}") ? normalized : null;
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
