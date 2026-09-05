package com.basicframework.module.system.service.auth;

import com.basicframework.module.system.config.LoginProtectionProperties;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO;
import org.springframework.stereotype.Service;

/** 账号密码登录的失败计数与锁定策略。 */
@Service
public class LoginProtectionService {

    private final LoginAttemptRedisDAO loginAttemptRedisDAO;
    private final LoginProtectionProperties properties;

    public LoginProtectionService(LoginAttemptRedisDAO loginAttemptRedisDAO, LoginProtectionProperties properties) {
        this.loginAttemptRedisDAO = loginAttemptRedisDAO;
        this.properties = properties;
    }

    public boolean isLocked(Long userId) {
        return loginAttemptRedisDAO.isLocked(userId);
    }

    public boolean recordFailure(Long userId) {
        return loginAttemptRedisDAO.recordFailure(
                userId, properties.getMaxFailedAttempts(), properties.getLockDuration());
    }

    public void clear(Long userId) {
        loginAttemptRedisDAO.clear(userId);
    }
}
