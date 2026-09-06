package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.enums.LogRecordConstants;
import com.basicframework.module.system.enums.permission.RoleTypeEnum;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 使用真实 MySQL/Redis 验证会话撤销、刷新失效、审计与会话表契约。 */
class SessionPersistenceIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserSessionService userSessionService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private RoleService roleService;

    @Test
    void sessionSchema_succeedsAgainstRealDatabase() {
        verifySessionTokenColumnWidths();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void disablingUser_revokesAccessAndRefreshTokensAgainstRealServices() {
        long userId = 1L;
        Integer originalUserStatus =
                jdbcTemplate.queryForObject("SELECT status FROM system_users WHERE id = ?", Integer.class, userId);

        UserSessionDO token = null;
        try {
            token = userSessionService.createSession(userId, UserTypeEnum.ADMIN.getValue());
            String accessToken = token.getAccessToken();
            String refreshToken = token.getRefreshToken();
            String accessTokenHash = token.getAccessTokenHash();
            String refreshTokenHash = token.getRefreshTokenHash();
            assertThat(userSessionService.checkAccessToken(accessToken)).isNotNull();

            MockHttpServletRequest auditRequest =
                    new MockHttpServletRequest("PUT", "/admin-api/system/user/update-status");
            auditRequest.setRemoteAddr("192.0.2.10");
            auditRequest.addHeader("User-Agent", "integration-test");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(auditRequest));
            LoginUser loginUser = new LoginUser();
            loginUser.setId(userId);
            loginUser.setUserType(UserTypeEnum.ADMIN.getValue());
            SecurityFrameworkUtils.setLoginUser(loginUser, auditRequest);

            adminUserService.updateUserStatus(userId, CommonStatusEnum.DISABLE.getStatus());

            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> {
                        assertThat(jdbcTemplate.queryForObject(
                                        "SELECT COUNT(*) FROM system_user_session WHERE access_token_hash = ? OR refresh_token_hash = ?",
                                        Integer.class,
                                        accessTokenHash,
                                        refreshTokenHash))
                                .isZero();
                        assertThat(userSessionService.getSessionByAccessToken(accessToken))
                                .isNull();
                    });
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertStatusAuditPersisted(userId));
            assertServiceException(
                    ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode(),
                    () -> userSessionService.refreshSession(refreshToken));
        } finally {
            if (token != null) {
                userSessionService.removeSessionByAccessToken(token.getAccessToken());
            }
            jdbcTemplate.update("UPDATE system_users SET status = ? WHERE id = ?", originalUserStatus, userId);
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void passwordAndRoleChanges_revokeSessionsAgainstRealServices() {
        long userId = 1L;
        String originalPassword =
                jdbcTemplate.queryForObject("SELECT password FROM system_users WHERE id = ?", String.class, userId);
        Set<Long> originalRoleIds = permissionService.getUserRoleIdListByUserId(userId);
        Long temporaryRoleId = null;
        try {
            UserSessionDO passwordToken = userSessionService.createSession(userId, UserTypeEnum.ADMIN.getValue());
            adminUserService.updateUserPassword(userId, "integration-password-123");
            awaitTokenRevoked(passwordToken);
            awaitAnonymousAuditPersisted(LogRecordConstants.SYSTEM_USER_UPDATE_PASSWORD_SUB_TYPE, userId);

            UserSessionDO roleToken = userSessionService.createSession(userId, UserTypeEnum.ADMIN.getValue());
            String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
            temporaryRoleId = roleService.createRole(
                    new RoleDO()
                            .setName("会话集成" + uniqueSuffix)
                            .setCode("it_session_" + uniqueSuffix)
                            .setSort(999),
                    RoleTypeEnum.CUSTOM.getType());
            Set<Long> changedRoleIds = new HashSet<>(originalRoleIds);
            changedRoleIds.add(temporaryRoleId);
            permissionService.assignUserRole(userId, userId, changedRoleIds);
            awaitTokenRevoked(roleToken);
            awaitAnonymousAuditPersisted(LogRecordConstants.SYSTEM_PERMISSION_ASSIGN_USER_ROLE_SUB_TYPE, userId);
        } finally {
            jdbcTemplate.update("UPDATE system_users SET password = ? WHERE id = ?", originalPassword, userId);
            permissionService.assignUserRole(userId, userId, originalRoleIds);
            if (temporaryRoleId != null) {
                roleService.deleteRole(temporaryRoleId);
            }
        }
    }

    private void awaitTokenRevoked(UserSessionDO token) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    assertThat(jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM system_user_session WHERE access_token_hash = ? OR refresh_token_hash = ?",
                                    Integer.class,
                                    token.getAccessTokenHash(),
                                    token.getRefreshTokenHash()))
                            .isZero();
                    assertThat(userSessionService.getSessionByAccessToken(token.getAccessToken()))
                            .isNull();
                    assertServiceException(
                            ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode(),
                            () -> userSessionService.refreshSession(token.getRefreshToken()));
                });
    }

    private void awaitAnonymousAuditPersisted(String subType, long bizId) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    AnonymousAuditActor actor = jdbcTemplate.queryForObject(
                            """
                            SELECT user_id, user_type
                            FROM system_operate_log
                            WHERE sub_type = ? AND biz_id = ?
                            ORDER BY id DESC
                            LIMIT 1
                            """,
                            (resultSet, rowNum) -> new AnonymousAuditActor(
                                    resultSet.getObject("user_id", Long.class), resultSet.getInt("user_type")),
                            subType,
                            bizId);
                    assertThat(actor).isEqualTo(new AnonymousAuditActor(null, UserTypeEnum.SYSTEM.getValue()));
                });
    }

    private record AnonymousAuditActor(Long userId, int userType) {}

    private void assertStatusAuditPersisted(long userId) {
        StatusAuditRow audit = jdbcTemplate.queryForObject(
                """
                SELECT user_id, user_type, biz_id, action, request_method, request_url, user_ip, user_agent
                FROM system_operate_log
                WHERE type = ? AND sub_type = ? AND biz_id = ?
                ORDER BY id DESC
                LIMIT 1
                """,
                (resultSet, rowNum) -> new StatusAuditRow(
                        resultSet.getLong("user_id"),
                        resultSet.getInt("user_type"),
                        resultSet.getLong("biz_id"),
                        resultSet.getString("action"),
                        resultSet.getString("request_method"),
                        resultSet.getString("request_url"),
                        resultSet.getString("user_ip"),
                        resultSet.getString("user_agent")),
                LogRecordConstants.SYSTEM_USER_TYPE,
                LogRecordConstants.SYSTEM_USER_UPDATE_STATUS_SUB_TYPE,
                userId);
        assertThat(audit)
                .isEqualTo(new StatusAuditRow(
                        userId,
                        UserTypeEnum.ADMIN.getValue(),
                        userId,
                        "将用户【总部门】的状态修改为【关闭】",
                        "PUT",
                        "/admin-api/system/user/update-status",
                        "192.0.2.10",
                        "integration-test"));
    }

    private record StatusAuditRow(
            long userId,
            int userType,
            long bizId,
            String action,
            String requestMethod,
            String requestUrl,
            String userIp,
            String userAgent) {}

    private void verifySessionTokenColumnWidths() {
        assertThat(queryColumnCharacterLength("system_user_session", "access_token_hash"))
                .isEqualTo(64L);
        assertThat(queryColumnCharacterLength("system_user_session", "refresh_token_hash"))
                .isEqualTo(64L);
        assertThat(queryColumnNullable("system_user_session", "user_id")).isEqualTo("NO");
    }

    private Long queryColumnCharacterLength(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT CHARACTER_MAXIMUM_LENGTH
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """,
                Long.class,
                tableName,
                columnName);
    }

    private String queryColumnNullable(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT IS_NULLABLE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """,
                String.class,
                tableName,
                columnName);
    }
}
