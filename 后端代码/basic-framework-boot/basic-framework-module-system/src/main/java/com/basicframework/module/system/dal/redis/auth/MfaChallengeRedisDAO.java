package com.basicframework.module.system.dal.redis.auth;

import static com.basicframework.module.system.dal.redis.RedisKeyConstants.MFA_CHALLENGE;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** MFA 一次性挑战 Redis 存储。读取即删除，避免并发重放。 */
@Repository
public class MfaChallengeRedisDAO {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MfaProperties properties;

    public void set(String token, MfaChallengeDTO challenge) {
        stringRedisTemplate
                .opsForValue()
                .set(MFA_CHALLENGE.formatted(token), JsonUtils.toJsonString(challenge), properties.getChallengeTtl());
    }

    public MfaChallengeDTO getAndDelete(String token) {
        String value = stringRedisTemplate.opsForValue().getAndDelete(MFA_CHALLENGE.formatted(token));
        return value == null ? null : JsonUtils.parseObject(value, MfaChallengeDTO.class);
    }
}
