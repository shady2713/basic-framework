package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.config.LoginProtectionProperties;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginProtectionServiceTest {

    @Mock
    private LoginAttemptRedisDAO loginAttemptRedisDAO;

    private LoginProtectionService loginProtectionService;

    @BeforeEach
    void setUp() {
        LoginProtectionProperties properties = new LoginProtectionProperties();
        loginProtectionService = new LoginProtectionService(loginAttemptRedisDAO, properties);
    }

    @Test
    void recordFailure_usesValidatedPolicy() {
        when(loginAttemptRedisDAO.recordFailure(1L, 5, Duration.ofMinutes(15))).thenReturn(true);

        assertThat(loginProtectionService.recordFailure(1L)).isTrue();
    }

    @Test
    void isLocked_delegatesToRedisStore() {
        when(loginAttemptRedisDAO.isLocked(1L)).thenReturn(true);

        assertThat(loginProtectionService.isLocked(1L)).isTrue();
    }

    @Test
    void clear_removesCounterAndLock() {
        loginProtectionService.clear(1L);

        verify(loginAttemptRedisDAO).clear(1L);
    }
}
