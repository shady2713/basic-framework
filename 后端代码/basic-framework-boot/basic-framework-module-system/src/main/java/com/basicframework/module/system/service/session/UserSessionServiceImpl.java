package com.basicframework.module.system.service.session;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertSet;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SESSION_ACCESS_TOKEN_EXPIRED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SESSION_ACCESS_TOKEN_NOT_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SESSION_REFRESH_TOKEN_EXPIRED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_NOT_EXISTS;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.HexUtil;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.date.DateUtils;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.system.config.SessionProperties;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.session.UserSessionMapper;
import com.basicframework.module.system.dal.redis.session.UserSessionRedisDAO;
import com.basicframework.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 用户会话服务实现。刷新令牌采用一次性轮换，数据库更新以旧摘要作为并发控制条件。 */
@Service
public class UserSessionServiceImpl implements UserSessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    @Resource
    private UserSessionMapper userSessionMapper;

    @Resource
    private UserSessionRedisDAO userSessionRedisDAO;

    @Resource
    private SessionProperties sessionProperties;

    @Resource
    private AdminUserService adminUserService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSessionDO createSession(Long userId, Integer userType) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户编号必须为正数");
        }
        validateUserType(userType);
        LocalDateTime refreshExpiresTime = LocalDateTime.now().plus(sessionProperties.getRefreshTokenTtl());
        UserSessionDO session = newSession(userId, userType, refreshExpiresTime);
        userSessionMapper.insert(session);
        updateCacheAfterCommit(session, null);
        return session;
    }

    @Override
    @Transactional(noRollbackFor = ServiceException.class)
    public UserSessionDO refreshSession(String refreshToken) {
        String expectedRefreshTokenHash = SessionTokenDigest.digest(refreshToken);
        UserSessionDO session = userSessionMapper.selectByRefreshTokenHash(expectedRefreshTokenHash);
        if (session == null) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        String previousAccessTokenHash = session.getAccessTokenHash();
        if (DateUtils.isExpired(session.getRefreshExpiresTime())) {
            deleteExpiredSession(session, expectedRefreshTokenHash, previousAccessTokenHash);
            throw exception(SESSION_REFRESH_TOKEN_EXPIRED);
        }

        rotateTokens(session);
        if (userSessionMapper.rotate(session, expectedRefreshTokenHash) == 0) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        updateCacheAfterCommit(session, previousAccessTokenHash);
        return session;
    }

    private void deleteExpiredSession(
            UserSessionDO session, String expectedRefreshTokenHash, String previousAccessTokenHash) {
        if (userSessionMapper.deleteByIdAndRefreshTokenHash(session.getId(), expectedRefreshTokenHash) == 0) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        deleteCacheAfterCommit(List.of(previousAccessTokenHash));
    }

    @Override
    public UserSessionDO getSessionByAccessToken(String accessToken) {
        String accessTokenHash = SessionTokenDigest.digest(accessToken);
        UserSessionDO session = userSessionRedisDAO.getByAccessTokenHash(accessTokenHash);
        if (session != null) {
            return session;
        }
        session = userSessionMapper.selectByAccessTokenHash(accessTokenHash);
        if (session != null && !DateUtils.isExpired(session.getAccessExpiresTime())) {
            userSessionRedisDAO.set(session);
        }
        return session;
    }

    @Override
    public UserSessionDO checkAccessToken(String accessToken) {
        UserSessionDO session = getSessionByAccessToken(accessToken);
        if (session == null) {
            throw exception(SESSION_ACCESS_TOKEN_NOT_EXISTS);
        }
        if (DateUtils.isExpired(session.getAccessExpiresTime())) {
            throw exception(SESSION_ACCESS_TOKEN_EXPIRED);
        }
        return session;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSessionDO removeSessionByAccessToken(String accessToken) {
        UserSessionDO session = userSessionMapper.selectByAccessTokenHash(SessionTokenDigest.digest(accessToken));
        return removeSession(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSessionDO removeSessionById(Long id) {
        return removeSession(userSessionMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSessionDO removeSessionByRefreshToken(String refreshToken) {
        UserSessionDO session = userSessionMapper.selectByRefreshTokenHash(SessionTokenDigest.digest(refreshToken));
        return removeSession(session);
    }

    private UserSessionDO removeSession(UserSessionDO session) {
        if (session == null || userSessionMapper.deleteById(session.getId()) == 0) {
            return null;
        }
        deleteCacheAfterCommit(List.of(session.getAccessTokenHash()));
        return session;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeSessionsByUser(Long userId, Integer userType) {
        List<UserSessionDO> sessions = userSessionMapper.selectListByUser(userId, userType);
        if (CollUtil.isEmpty(sessions)) {
            return;
        }
        userSessionMapper.deleteByIds(convertSet(sessions, UserSessionDO::getId));
        deleteCacheAfterCommit(convertSet(sessions, UserSessionDO::getAccessTokenHash));
    }

    @Override
    public PageResult<UserSessionDO> getSessionPage(PageParam pageParam, Long userId, Integer userType) {
        return userSessionMapper.selectPage(pageParam, userId, userType);
    }

    private UserSessionDO newSession(Long userId, Integer userType, LocalDateTime refreshExpiresTime) {
        UserSessionDO session =
                new UserSessionDO().setUserId(userId).setUserType(userType).setRefreshExpiresTime(refreshExpiresTime);
        rotateTokens(session);
        return session;
    }

    private void rotateTokens(UserSessionDO session) {
        String accessToken = generateSecureToken();
        String refreshToken = generateSecureToken();
        LocalDateTime accessExpiresTime = LocalDateTime.now().plus(sessionProperties.getAccessTokenTtl());
        if (accessExpiresTime.isAfter(session.getRefreshExpiresTime())) {
            accessExpiresTime = session.getRefreshExpiresTime();
        }
        session.setAccessToken(accessToken);
        session.setAccessTokenHash(SessionTokenDigest.digest(accessToken));
        session.setRefreshToken(refreshToken);
        session.setRefreshTokenHash(SessionTokenDigest.digest(refreshToken));
        session.setAccessExpiresTime(accessExpiresTime);
        session.setUserInfo(buildUserInfo(session.getUserId(), session.getUserType()));
    }

    private Map<String, String> buildUserInfo(Long userId, Integer userType) {
        validateUserType(userType);
        AdminUserDO user = adminUserService.getUser(userId);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        return MapUtil.builder(LoginUser.INFO_KEY_NICKNAME, user.getNickname())
                .put(
                        LoginUser.INFO_KEY_DEPT_ID,
                        user.getDeptId() != null ? user.getDeptId().toString() : null)
                .build();
    }

    private static void validateUserType(Integer userType) {
        if (!UserTypeEnum.ADMIN.getValue().equals(userType)) {
            throw new IllegalArgumentException("系统模块仅支持管理员会话");
        }
    }

    private static String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexUtil.encodeHexStr(bytes);
    }

    private void updateCacheAfterCommit(UserSessionDO session, String previousAccessTokenHash) {
        runAfterCommit(() -> {
            if (previousAccessTokenHash != null) {
                userSessionRedisDAO.deleteByAccessTokenHash(previousAccessTokenHash);
            }
            userSessionRedisDAO.set(session);
        });
    }

    private void deleteCacheAfterCommit(Collection<String> accessTokenHashes) {
        runAfterCommit(() -> userSessionRedisDAO.deleteByAccessTokenHashes(accessTokenHashes));
    }

    private static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
