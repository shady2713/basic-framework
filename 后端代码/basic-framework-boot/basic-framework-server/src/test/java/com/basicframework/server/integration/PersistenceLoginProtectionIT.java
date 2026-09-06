package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.config.LoginProtectionProperties;
import com.basicframework.module.system.dal.redis.RedisKeyConstants;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 使用真实 Redis 验证账号失败计数、锁定和管理员解锁。 */
class PersistenceLoginProtectionIT extends AbstractPersistenceIntegrationTest {

    private static final Long TEST_USER_ID = 9_001L;

    @Autowired
    private LoginAttemptRedisDAO loginAttemptRedisDAO;

    @Autowired
    private LoginProtectionProperties properties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loginFailures_lockAtThresholdAndClearAtomically() {
        loginAttemptRedisDAO.clear(TEST_USER_ID);
        try {
            for (int attempt = 1; attempt < properties.getMaxFailedAttempts(); attempt++) {
                assertThat(loginAttemptRedisDAO.recordFailure(
                                TEST_USER_ID, properties.getMaxFailedAttempts(), properties.getLockDuration()))
                        .isFalse();
            }

            assertThat(loginAttemptRedisDAO.recordFailure(
                            TEST_USER_ID, properties.getMaxFailedAttempts(), properties.getLockDuration()))
                    .isTrue();
            assertThat(loginAttemptRedisDAO.isLocked(TEST_USER_ID)).isTrue();
            assertThat(stringRedisTemplate.hasKey(RedisKeyConstants.LOGIN_FAILURES.formatted(TEST_USER_ID)))
                    .isFalse();
            assertThat(stringRedisTemplate.getExpire(RedisKeyConstants.LOGIN_LOCK.formatted(TEST_USER_ID)))
                    .isPositive();

            loginAttemptRedisDAO.clear(TEST_USER_ID);

            assertThat(loginAttemptRedisDAO.isLocked(TEST_USER_ID)).isFalse();
        } finally {
            loginAttemptRedisDAO.clear(TEST_USER_ID);
        }
    }
}
