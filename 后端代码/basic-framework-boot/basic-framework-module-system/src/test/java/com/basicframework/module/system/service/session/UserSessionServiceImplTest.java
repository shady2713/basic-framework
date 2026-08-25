package com.basicframework.module.system.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.config.SessionProperties;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.session.UserSessionMapper;
import com.basicframework.module.system.dal.redis.session.UserSessionRedisDAO;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserSessionServiceImplTest {

    @InjectMocks
    private UserSessionServiceImpl sessionService;

    @Mock
    private UserSessionMapper userSessionMapper;

    @Mock
    private UserSessionRedisDAO userSessionRedisDAO;

    @Mock
    private SessionProperties sessionProperties;

    @Mock
    private AdminUserService adminUserService;

    private void stubCreateDependencies() {
        when(sessionProperties.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(30));
        when(sessionProperties.getRefreshTokenTtl()).thenReturn(Duration.ofDays(30));
        stubAdminUser();
    }

    private void stubRotationDependencies() {
        when(sessionProperties.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(30));
        stubAdminUser();
    }

    private void stubAdminUser() {
        when(adminUserService.getUser(1L))
                .thenReturn(new AdminUserDO().setId(1L).setNickname("tester").setDeptId(2L));
    }

    @Test
    void createSession_generatesIndependent256BitTokensAndOneRow() {
        UserSessionDO session = createSession();

        assertThat(session.getAccessToken()).matches("^[0-9a-f]{64}$");
        assertThat(session.getRefreshToken()).matches("^[0-9a-f]{64}$");
        assertThat(session.getAccessToken()).isNotEqualTo(session.getRefreshToken());
        assertThat(session.getAccessTokenHash()).isEqualTo(SessionTokenDigest.digest(session.getAccessToken()));
        assertThat(session.getRefreshTokenHash()).isEqualTo(SessionTokenDigest.digest(session.getRefreshToken()));
        assertThat(session.getAccessExpiresTime()).isBefore(session.getRefreshExpiresTime());
        verify(userSessionMapper).insert(session);
        verify(userSessionRedisDAO).set(session);
    }

    @Test
    void createSession_rejectsInvalidIdentity() {
        assertThatThrownBy(() -> sessionService.createSession(null, UserTypeEnum.ADMIN.getValue()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户编号必须为正数");
        assertThatThrownBy(() -> sessionService.createSession(1L, UserTypeEnum.MEMBER.getValue()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("系统模块仅支持管理员会话");
        verify(userSessionMapper, never()).insert(any(UserSessionDO.class));
    }

    @Test
    void createSession_largeSample_hasNoRepeatedToken() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            UserSessionDO session = createSession();
            assertThat(tokens.add(session.getAccessToken())).isTrue();
            assertThat(tokens.add(session.getRefreshToken())).isTrue();
        }
    }

    @Test
    void refreshSession_rotatesAtomicallyWithoutExtendingAbsoluteExpiry() {
        stubRotationDependencies();
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(7));
        String previousAccessHash = current.getAccessTokenHash();
        String previousRefreshHash = current.getRefreshTokenHash();
        when(userSessionMapper.selectByRefreshTokenHash(previousRefreshHash)).thenReturn(current);
        when(userSessionMapper.rotate(current, previousRefreshHash)).thenReturn(1);

        UserSessionDO result = sessionService.refreshSession(current.getRefreshToken());

        assertThat(result.getRefreshTokenHash()).isNotEqualTo(previousRefreshHash);
        assertThat(result.getAccessTokenHash()).isNotEqualTo(previousAccessHash);
        assertThat(result.getRefreshExpiresTime()).isEqualTo(current.getRefreshExpiresTime());
        verify(userSessionRedisDAO).deleteByAccessTokenHash(previousAccessHash);
        verify(userSessionRedisDAO).set(current);
    }

    @Test
    void refreshSession_concurrentReplayFailsWithoutPublishingTokens() {
        stubRotationDependencies();
        UserSessionDO current = currentSession(LocalDateTime.now().plusDays(7));
        String previousRefreshHash = current.getRefreshTokenHash();
        when(userSessionMapper.selectByRefreshTokenHash(previousRefreshHash)).thenReturn(current);
        when(userSessionMapper.rotate(current, previousRefreshHash)).thenReturn(0);

        assertThatThrownBy(() -> sessionService.refreshSession(current.getRefreshToken()))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
        verify(userSessionRedisDAO, never()).set(any());
        verify(userSessionRedisDAO, never()).deleteByAccessTokenHash(any());
    }

    @Test
    void getSessionByAccessToken_neverAcceptsRefreshToken() {
        String refreshToken = "refresh-token";
        String tokenHash = SessionTokenDigest.digest(refreshToken);
        when(userSessionMapper.selectByAccessTokenHash(tokenHash)).thenReturn(null);

        assertThat(sessionService.getSessionByAccessToken(refreshToken)).isNull();

        verify(userSessionMapper, never()).selectByRefreshTokenHash(tokenHash);
        verify(userSessionRedisDAO, never()).set(any());
    }

    private UserSessionDO createSession() {
        stubCreateDependencies();
        return sessionService.createSession(1L, UserTypeEnum.ADMIN.getValue());
    }

    private static UserSessionDO currentSession(LocalDateTime refreshExpiresTime) {
        String refreshToken = "current-refresh-token";
        return new UserSessionDO()
                .setId(42L)
                .setAccessTokenHash("old-access-hash")
                .setRefreshToken(refreshToken)
                .setRefreshTokenHash(SessionTokenDigest.digest(refreshToken))
                .setUserId(1L)
                .setUserType(UserTypeEnum.ADMIN.getValue())
                .setAccessExpiresTime(LocalDateTime.now().plusMinutes(10))
                .setRefreshExpiresTime(refreshExpiresTime);
    }
}
