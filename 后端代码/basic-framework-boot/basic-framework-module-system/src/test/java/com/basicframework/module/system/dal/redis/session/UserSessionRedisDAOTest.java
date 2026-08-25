package com.basicframework.module.system.dal.redis.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class UserSessionRedisDAOTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UserSessionRedisDAO redisDAO;

    @Test
    void set_neverCachesRawTokensOrRefreshTokenHash() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UserSessionDO source = new UserSessionDO()
                .setId(1L)
                .setAccessToken("raw-access-token")
                .setAccessTokenHash("access-hash")
                .setRefreshToken("raw-refresh-token")
                .setRefreshTokenHash("refresh-hash")
                .setUserId(2L)
                .setUserType(1)
                .setUserInfo(Map.of("nickname", "tester"))
                .setAccessExpiresTime(LocalDateTime.now().plusMinutes(5));

        redisDAO.set(source);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("user_session:access-hash"), json.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(json.getValue())
                .doesNotContain("raw-access-token", "raw-refresh-token", "refresh-hash", "refreshTokenHash")
                .contains("\"accessTokenHash\":\"access-hash\"");
        assertThat(source.toString()).doesNotContain("raw-access-token", "raw-refresh-token");
        assertThat(source.getAccessToken()).isEqualTo("raw-access-token");
    }
}
