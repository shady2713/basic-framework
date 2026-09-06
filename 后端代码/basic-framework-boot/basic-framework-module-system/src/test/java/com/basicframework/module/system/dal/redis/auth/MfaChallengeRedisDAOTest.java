package com.basicframework.module.system.dal.redis.auth;

import static com.basicframework.module.system.dal.redis.RedisKeyConstants.MFA_CHALLENGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class MfaChallengeRedisDAOTest {

    private static final String TOKEN = "single-use-token";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MfaProperties properties;
    private MfaChallengeRedisDAO dao;

    @BeforeEach
    void setUp() {
        properties = new MfaProperties();
        dao = new MfaChallengeRedisDAO(stringRedisTemplate, properties);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void set_serializesChallengeWithConfiguredSingleUseTtl() {
        Duration ttl = Duration.ofMinutes(4);
        properties.setChallengeTtl(ttl);
        MfaChallengeDTO challenge = challenge();

        dao.set(TOKEN, challenge);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(MFA_CHALLENGE.formatted(TOKEN)), payloadCaptor.capture(), eq(ttl));
        MfaChallengeDTO stored = JsonUtils.parseObject(payloadCaptor.getValue(), MfaChallengeDTO.class);
        assertThat(stored.getUserId()).isEqualTo(42L);
        assertThat(stored.getPurpose()).isEqualTo(MfaChallengePurposeEnum.LOGIN);
        assertThat(stored.getWebAuthnUserHandle()).containsExactly((byte) 1, (byte) 2);
    }

    @Test
    void getAndDelete_returnsParsedChallengeOrNullAfterAtomicRead() {
        when(valueOperations.getAndDelete(MFA_CHALLENGE.formatted(TOKEN)))
                .thenReturn(JsonUtils.toJsonString(challenge()), null);

        MfaChallengeDTO consumed = dao.getAndDelete(TOKEN);

        assertThat(consumed.getUsername()).isEqualTo("shady");
        assertThat(consumed.getAccessTokenHash()).isEqualTo("sha256-token");
        assertThat(dao.getAndDelete(TOKEN)).isNull();
        verify(valueOperations, times(2)).getAndDelete(MFA_CHALLENGE.formatted(TOKEN));
    }

    private static MfaChallengeDTO challenge() {
        return MfaChallengeDTO.builder()
                .userId(42L)
                .username("shady")
                .loginLogType(1)
                .purpose(MfaChallengePurposeEnum.LOGIN)
                .encryptedTotpSecret("encrypted-secret")
                .webAuthnUserHandle(new byte[] {1, 2})
                .webAuthnRequestJson("{\"challenge\":\"value\"}")
                .accessTokenHash("sha256-token")
                .factorId(8L)
                .build();
    }
}
