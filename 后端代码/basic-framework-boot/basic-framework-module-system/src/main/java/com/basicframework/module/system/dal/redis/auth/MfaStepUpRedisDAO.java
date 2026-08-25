package com.basicframework.module.system.dal.redis.auth;

import static com.basicframework.module.system.dal.redis.RedisKeyConstants.MFA_STEP_UP;

import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.module.system.config.MfaProperties;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** 与访问令牌绑定的短时 MFA 二次验证状态。 */
@Repository
public class MfaStepUpRedisDAO {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MfaProperties properties;

    public void set(String accessToken, Long userId) {
        setByHash(tokenHash(accessToken), userId);
    }

    public void setByHash(String accessTokenHash, Long userId) {
        stringRedisTemplate
                .opsForValue()
                .set(keyByHash(accessTokenHash), String.valueOf(userId), properties.getStepUpTtl());
    }

    public boolean matches(String accessToken, Long userId) {
        return accessToken != null
                && userId != null
                && String.valueOf(userId)
                        .equals(stringRedisTemplate.opsForValue().get(keyByHash(tokenHash(accessToken))));
    }

    public static String tokenHash(String accessToken) {
        return accessToken == null ? null : DigestUtil.sha256Hex(accessToken);
    }

    private static String keyByHash(String accessTokenHash) {
        return MFA_STEP_UP.formatted(accessTokenHash);
    }
}
