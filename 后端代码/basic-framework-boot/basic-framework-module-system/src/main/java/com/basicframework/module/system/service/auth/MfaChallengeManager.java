package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CHALLENGE_INVALID;

import com.basicframework.module.system.dal.redis.auth.MfaChallengeRedisDAO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 创建并一次性消费 MFA 挑战，统一校验用途与用户归属。 */
@Component
public class MfaChallengeManager {

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final MfaChallengeRedisDAO challengeRedisDAO;

    public MfaChallengeManager(MfaChallengeRedisDAO challengeRedisDAO) {
        this.challengeRedisDAO = challengeRedisDAO;
    }

    public String save(MfaChallengeDTO challenge) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        challengeRedisDAO.set(token, challenge);
        return token;
    }

    public MfaChallengeDTO consume(String token, MfaChallengePurposeEnum expectedPurpose) {
        if (!StringUtils.hasText(token) || token.length() > MAX_TOKEN_LENGTH) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        MfaChallengeDTO challenge = challengeRedisDAO.getAndDelete(token);
        if (challenge == null || challenge.getPurpose() != expectedPurpose) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        return challenge;
    }

    public MfaChallengeDTO consumeOwned(String token, MfaChallengePurposeEnum expectedPurpose, Long expectedUserId) {
        MfaChallengeDTO challenge = consume(token, expectedPurpose);
        if (expectedUserId == null || !expectedUserId.equals(challenge.getUserId())) {
            throw exception(AUTH_MFA_CHALLENGE_INVALID);
        }
        return challenge;
    }
}
