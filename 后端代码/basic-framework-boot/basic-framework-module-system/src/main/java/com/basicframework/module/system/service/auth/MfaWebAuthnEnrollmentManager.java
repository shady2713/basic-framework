package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 执行已登录用户的 WebAuthn 因子注册流程。 */
@Component
public class MfaWebAuthnEnrollmentManager {

    private static final int USER_HANDLE_BYTES = 32;
    private static final int MAX_CREDENTIAL_ID_BYTES = 1024;
    private static final int MAX_PUBLIC_KEY_BYTES = 16_384;

    private final SecureRandom secureRandom = new SecureRandom();
    private final MfaProperties properties;
    private final MfaChallengeManager challengeManager;
    private final MfaFactorMapper factorMapper;
    private final WebAuthnService webAuthnService;

    public MfaWebAuthnEnrollmentManager(
            MfaProperties properties,
            MfaChallengeManager challengeManager,
            MfaFactorMapper factorMapper,
            WebAuthnService webAuthnService) {
        this.properties = properties;
        this.challengeManager = challengeManager;
        this.factorMapper = factorMapper;
        this.webAuthnService = webAuthnService;
    }

    public MfaWebAuthnOptionsDTO begin(Long userId, String username) {
        requireIdentity(userId, username);
        List<MfaFactorDO> existing =
                factorMapper.selectEnabledByUserIdAndTypeList(userId, MfaFactorTypeEnum.WEBAUTHN.getType());
        byte[] userHandle = existing.isEmpty() ? randomUserHandle() : requireUserHandle(existing);
        WebAuthnService.CeremonyOptions options = webAuthnService.startRegistration(userId, username, userHandle);
        String ceremonyToken = challengeManager.save(MfaChallengeDTO.builder()
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

    public void complete(Long userId, String ceremonyToken, String credentialJson) {
        requireEnabled();
        MfaChallengeDTO challenge = challengeManager.consumeOwned(
                ceremonyToken, MfaChallengePurposeEnum.WEBAUTHN_MANAGEMENT_ENROLLMENT, userId);
        WebAuthnService.RegistrationOutcome result =
                webAuthnService.finishRegistration(challenge.getWebAuthnRequestJson(), credentialJson);
        persist(userId, challenge.getWebAuthnUserHandle(), result);
    }

    private void persist(Long userId, byte[] userHandle, WebAuthnService.RegistrationOutcome result) {
        byte[] credentialId = result.credentialId();
        byte[] publicKeyCose = result.publicKeyCose();
        if (!result.userVerified()
                || credentialId.length == 0
                || credentialId.length > MAX_CREDENTIAL_ID_BYTES
                || publicKeyCose.length == 0
                || publicKeyCose.length > MAX_PUBLIC_KEY_BYTES) {
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

    private void requireIdentity(Long userId, String username) {
        requireEnabled();
        if (userId == null || !StringUtils.hasText(username)) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || !properties.getWebauthn().isEnabled()) {
            throw exception(AUTH_MFA_DISABLED);
        }
    }

    private byte[] randomUserHandle() {
        byte[] userHandle = new byte[USER_HANDLE_BYTES];
        secureRandom.nextBytes(userHandle);
        return userHandle;
    }

    private static byte[] requireUserHandle(List<MfaFactorDO> factors) {
        byte[] userHandle = factors.get(0).getUserHandle();
        if (userHandle == null || userHandle.length == 0) {
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
        return userHandle;
    }
}
