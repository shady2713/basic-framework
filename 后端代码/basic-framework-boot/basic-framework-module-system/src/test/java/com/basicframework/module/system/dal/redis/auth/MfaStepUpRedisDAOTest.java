package com.basicframework.module.system.dal.redis.auth;

import static com.basicframework.module.system.dal.redis.RedisKeyConstants.MFA_STEP_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.config.MfaProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class MfaStepUpRedisDAOTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MfaProperties properties;
    private MfaStepUpRedisDAO dao;

    @BeforeEach
    void setUp() {
        properties = new MfaProperties();
        dao = new MfaStepUpRedisDAO(stringRedisTemplate, properties);
    }

    @Test
    void setByHash_storesUserWithConfiguredTtl() {
        Duration ttl = Duration.ofMinutes(7);
        properties.setStepUpTtl(ttl);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        dao.setByHash("token-hash", 42L);

        verify(valueOperations).set(MFA_STEP_UP.formatted("token-hash"), "42", ttl);
    }

    @Test
    void set_hashesRawAccessTokenBeforeWriting() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        dao.set("raw-access-token", 7L);

        verify(valueOperations)
                .set(
                        MFA_STEP_UP.formatted(MfaStepUpRedisDAO.tokenHash("raw-access-token")),
                        "7",
                        properties.getStepUpTtl());
    }

    @Test
    void matches_comparesStoredOwnerAndRejectsMissingInputsWithoutRedisLookup() {
        String accessToken = "raw-access-token";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(MFA_STEP_UP.formatted(MfaStepUpRedisDAO.tokenHash(accessToken))))
                .thenReturn("42");

        assertThat(dao.matches(accessToken, 42L)).isTrue();
        assertThat(dao.matches(accessToken, 41L)).isFalse();
        assertThat(dao.matches(null, 42L)).isFalse();
        assertThat(dao.matches(accessToken, null)).isFalse();
        verify(valueOperations, never()).get(MFA_STEP_UP.formatted("null"));
    }

    @Test
    void tokenHash_neverReturnsRawSecretAndKeepsNullContract() {
        assertThat(MfaStepUpRedisDAO.tokenHash(null)).isNull();
        assertThat(MfaStepUpRedisDAO.tokenHash("raw-access-token")).hasSize(64).doesNotContain("raw-access-token");
    }
}
