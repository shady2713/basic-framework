package com.basicframework.module.system.service.user;

import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_IMPORT_LIST_IS_EMPTY;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_MOBILE_EXISTS;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.PASSWORD_CHANGED;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_DISABLED;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_INFO_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserQuery;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import com.basicframework.module.system.service.user.dto.UserImportResultDTO;
import com.mzt.logapi.context.LogRecordContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link AdminUserServiceImpl#importUserList} 的单元测试
 *
 * 覆盖用户导入契约：新用户为禁用状态 + SecureRandom 随机密码，
 * 不再读取 system.user.init-password 共享配置，由管理员重置密码并启用后激活。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    @InjectMocks
    private AdminUserServiceImpl userService;

    @Mock
    private AdminUserMapper userMapper;

    @Mock
    private DeptService deptService;

    @Mock
    private PostService postService;

    @Mock
    private ObjectProvider<PermissionService> permissionServiceProvider;

    @Mock
    private PermissionService permissionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PasswordPolicy passwordPolicy;

    @Mock
    private UserSessionRevocationPublisher sessionRevocationPublisher;

    @Mock
    private UserPostMapper userPostMapper;

    @Mock
    private Validator validator;

    @Captor
    private ArgumentCaptor<AdminUserDO> userCaptor;

    @Captor
    private ArgumentCaptor<String> rawPasswordCaptor;

    @Test
    void getUserByMobile_normalizesInputBeforeLookup() {
        AdminUserDO user = new AdminUserDO().setId(1L).setMobile("13812345678");
        when(userMapper.selectByMobile("13812345678")).thenReturn(user);

        AdminUserDO result = userService.getUserByMobile(" 13812345678 ");

        assertThat(result).isSameAs(user);
        verify(userMapper).selectByMobile("13812345678");
    }

    @Test
    void createUser_normalizesIdentityFieldsAndRunsPasswordPolicy() {
        AdminUserDO user = new AdminUserDO()
                .setUsername("  Alice_01  ")
                .setNickname("  Alice😀  ")
                .setMobile(" 13812345678 ")
                .setEmail("Alice@Example.COM ")
                .setPassword("violet river orbits quietly!")
                .setPostIds(Set.of());
        when(passwordEncoder.encode("violet river orbits quietly!")).thenReturn("encoded-password");
        LogRecordContext.putEmptySpan();

        try {
            userService.createUser(user);

            verify(passwordPolicy).validate("violet river orbits quietly!", "alice_01");
            verify(userMapper).insert(userCaptor.capture());
            assertThat(userCaptor.getValue())
                    .extracting(
                            AdminUserDO::getUsername,
                            AdminUserDO::getNickname,
                            AdminUserDO::getMobile,
                            AdminUserDO::getEmail,
                            AdminUserDO::getPassword)
                    .containsExactly("alice_01", "Alice😀", "13812345678", "Alice@example.com", "encoded-password");
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void deleteUser_clearsDepartmentLeaderBeforeDeletingUser() {
        AdminUserDO user = new AdminUserDO().setId(1L).setUsername("admin");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);
        LogRecordContext.putEmptySpan();

        try {
            userService.deleteUser(1L);

            verify(deptService).processUserDeleted(1L);
            verify(userMapper).deleteById(1L);
            verify(permissionService).processUserDeleted(1L);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void deleteUserList_clearsEveryDepartmentLeaderReference() {
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);

        userService.deleteUserList(List.of(1L, 2L));

        verify(deptService).processUserDeleted(1L);
        verify(deptService).processUserDeleted(2L);
        verify(userMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void updateUserStatus_disabled_revokesSessionAndBuildsAuditContext() {
        AdminUserDO user = new AdminUserDO().setId(1L).setNickname("管理员");
        when(userMapper.selectById(1L)).thenReturn(user);
        LogRecordContext.putEmptySpan();

        try {
            userService.updateUserStatus(1L, CommonStatusEnum.DISABLE.getStatus());

            verify(userMapper).updateById(userCaptor.capture());
            assertThat(userCaptor.getValue())
                    .extracting(AdminUserDO::getId, AdminUserDO::getStatus)
                    .containsExactly(1L, CommonStatusEnum.DISABLE.getStatus());
            verify(sessionRevocationPublisher).revokeAdminSession(1L, USER_DISABLED);
            assertThat(LogRecordContext.getVariable("user")).isSameAs(user);
            assertThat(LogRecordContext.getVariable("statusName")).isEqualTo(CommonStatusEnum.DISABLE.getName());
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUserProfile_nicknameChanged_revokesSessionsWithStaleIdentitySnapshot() {
        AdminUserDO oldUser = new AdminUserDO().setId(1L).setNickname("旧昵称").setDeptId(2L);
        AdminUserDO update = new AdminUserDO().setNickname("新昵称");
        when(userMapper.selectById(1L)).thenReturn(oldUser);

        userService.updateUserProfile(1L, update);

        verify(userMapper).updateById(update);
        verify(sessionRevocationPublisher).revokeAdminSession(1L, USER_INFO_CHANGED);
    }

    @Test
    void updateUser_departmentCleared_revokesSessionsWithStaleDataScope() {
        AdminUserDO oldUser = new AdminUserDO()
                .setId(1L)
                .setUsername("admin")
                .setNickname("管理员")
                .setDeptId(2L);
        AdminUserDO update = new AdminUserDO()
                .setId(1L)
                .setUsername("admin")
                .setNickname("管理员")
                .setDeptId(null)
                .setPostIds(Set.of());
        when(userMapper.selectById(1L)).thenReturn(oldUser);
        LogRecordContext.putEmptySpan();

        try {
            userService.updateUser(update);

            verify(sessionRevocationPublisher).revokeAdminSession(1L, USER_INFO_CHANGED);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUserPassword_selfChange_revokesAllSessions() {
        AdminUserDO user = new AdminUserDO()
                .setId(1L)
                .setUsername("admin")
                .setNickname("管理员")
                .setPassword("encoded-old");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("old-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        LogRecordContext.putEmptySpan();

        try {
            userService.updateUserPassword(1L, "old-password", "new-password");

            verify(passwordPolicy).validate("new-password", "admin");
            verify(userMapper).updateById(userCaptor.capture());
            assertThat(userCaptor.getValue())
                    .extracting(AdminUserDO::getId, AdminUserDO::getPassword)
                    .containsExactly(1L, "encoded-new");
            verify(sessionRevocationPublisher).revokeAdminSession(1L, PASSWORD_CHANGED);
            assertThat(LogRecordContext.getVariable("user")).isSameAs(user);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void updateUserPassword_adminReset_revokesAllSessions() {
        AdminUserDO user = new AdminUserDO().setId(1L).setUsername("admin").setNickname("管理员");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        LogRecordContext.putEmptySpan();

        try {
            userService.updateUserPassword(1L, "new-password");

            verify(passwordPolicy).validate("new-password", "admin");
            verify(userMapper).updateById(userCaptor.capture());
            assertThat(userCaptor.getValue())
                    .extracting(AdminUserDO::getId, AdminUserDO::getPassword)
                    .containsExactly(1L, "encoded-new");
            verify(sessionRevocationPublisher).revokeAdminSession(1L, PASSWORD_CHANGED);
            assertThat(LogRecordContext.getVariable("user")).isSameAs(user);
        } finally {
            LogRecordContext.clear();
        }
    }

    @Test
    void upgradePasswordEncodingIfNeeded_currentStrengthDoesNotWrite() {
        when(passwordEncoder.upgradeEncoding("current-hash")).thenReturn(false);

        userService.upgradePasswordEncodingIfNeeded(1L, "verified-password", "current-hash");

        verify(passwordEncoder).upgradeEncoding("current-hash");
        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(sessionRevocationPublisher);
    }

    @Test
    void upgradePasswordEncodingIfNeeded_oldStrengthUsesCompareAndSetWithoutRevokingSessions() {
        when(passwordEncoder.upgradeEncoding("old-hash")).thenReturn(true);
        when(passwordEncoder.encode("verified-password")).thenReturn("upgraded-hash");

        userService.upgradePasswordEncodingIfNeeded(1L, "verified-password", "old-hash");

        verify(userMapper).updatePasswordIfUnchanged(1L, "old-hash", "upgraded-hash");
        verifyNoInteractions(sessionRevocationPublisher);
    }

    // ========== 新增导入：禁用状态 + 随机密码 ==========

    @Test
    void importUserList_newUsers_statusDisabled() {
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");

        UserImportResultDTO respDTO =
                userService.importUserList(List.of(buildImportUser("zhangsan"), buildImportUser("lisi")), false);

        assertThat(respDTO.getCreateUsernames()).containsExactly("zhangsan", "lisi");
        assertThat(respDTO.getFailureUsernames()).isEmpty();
        verify(userMapper, times(2)).insert(userCaptor.capture());
        // 导入的新用户必须为禁用状态，等待管理员重置密码并启用
        assertThat(userCaptor.getAllValues())
                .allSatisfy(user -> assertThat(user.getStatus()).isEqualTo(CommonStatusEnum.DISABLE.getStatus()));
    }

    @Test
    void importUserList_normalizesUsernameBeforeLookupAndStorage() {
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");

        UserImportResultDTO result = userService.importUserList(List.of(buildImportUser("  User_123  ")), false);

        assertThat(result.getCreateUsernames()).containsExactly("user_123");
        verify(userMapper).selectByUsername("user_123");
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("user_123");
    }

    @Test
    void importUserList_newUsers_randomPasswordPerUser() {
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");

        userService.importUserList(List.of(buildImportUser("zhangsan"), buildImportUser("lisi")), false);

        // 每个用户独立的 32 位小写十六进制随机密码（128 bit），不存在共享初始密码
        verify(passwordEncoder, times(2)).encode(rawPasswordCaptor.capture());
        List<String> rawPasswords = rawPasswordCaptor.getAllValues();
        assertThat(rawPasswords).allSatisfy(raw -> assertThat(raw).matches("^[0-9a-f]{32}$"));
        assertThat(rawPasswords.get(0)).isNotEqualTo(rawPasswords.get(1));
    }

    @Test
    void importUserList_excelStatusEnabled_stillDisabled() {
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        // Excel 中填写"开启"状态，不允许绕过"导入即禁用"的安全约束
        UserImportDTO importUser = buildImportUser("zhangsan");
        importUser.setStatus(CommonStatusEnum.ENABLE.getStatus());

        UserImportResultDTO respDTO = userService.importUserList(List.of(importUser), false);

        assertThat(respDTO.getCreateUsernames()).containsExactly("zhangsan");
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(CommonStatusEnum.DISABLE.getStatus());
    }

    // ========== 共享初始密码配置已删除 ==========

    @Test
    void importUserList_noInitPasswordConfig_succeeds() {
        // 不准备任何配置：改造前此处会抛 USER_IMPORT_INIT_PASSWORD，改造后必须导入成功
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");

        UserImportResultDTO respDTO = userService.importUserList(List.of(buildImportUser("zhangsan")), false);

        assertThat(respDTO.getCreateUsernames()).containsExactly("zhangsan");
        assertThat(respDTO.getFailureUsernames()).isEmpty();
        // 负向断言：类上不存在 ConfigApi 依赖与 USER_INIT_PASSWORD_KEY 常量（ConfigApi 已随本切片删除）
        List<Field> fields = Arrays.asList(AdminUserServiceImpl.class.getDeclaredFields());
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.getType().getSimpleName()).isNotEqualTo("ConfigApi");
            assertThat(field.getName()).isNotEqualTo("USER_INIT_PASSWORD_KEY");
        });
        List<Field> staticFields = fields.stream()
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .toList();
        assertThat(staticFields).allSatisfy(field -> assertThat(field.getName()).doesNotContain("PASSWORD_KEY"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importUserList_validationFailure_returnsDeclaredConstraintMessage() {
        UserImportDTO importUser = buildImportUser(" ");
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("用户账号不能为空");
        when(validator.validate(any(), any(Class[].class))).thenReturn(Set.of(violation));

        UserImportResultDTO result = userService.importUserList(List.of(importUser), false);

        assertThat(result.getFailureUsernames()).containsEntry("第 1 行", "用户账号不能为空");
        verify(userMapper, never()).insert(any(AdminUserDO.class));
    }

    @Test
    void importUserList_businessFailure_returnsExplicitPublicMessage() {
        UserImportDTO importUser = buildImportUser("zhangsan").setMobile("13812345678");
        when(userMapper.selectByMobile("13812345678"))
                .thenReturn(new AdminUserDO().setId(99L).setMobile("13812345678"));

        UserImportResultDTO result = userService.importUserList(List.of(importUser), false);

        assertThat(result.getFailureUsernames()).containsEntry("zhangsan", USER_MOBILE_EXISTS.getMsg());
        verify(userMapper, never()).insert(any(AdminUserDO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstConstraintViolationMessage_blankDeclaredMessage_usesStableFallback() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn(" ");

        String message = AdminUserServiceImpl.firstConstraintViolationMessage(
                new jakarta.validation.ConstraintViolationException(Set.of(violation)));

        assertThat(message).isEqualTo("用户信息校验失败");
    }

    // ========== 更新已存在用户：不触碰密码与状态 ==========

    @Test
    void importUserList_existingUserUpdate_passwordUntouched() {
        AdminUserDO existUser = new AdminUserDO()
                .setId(100L)
                .setUsername("zhangsan")
                .setPassword("old-encoded-password")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        when(userMapper.selectByUsername("zhangsan")).thenReturn(existUser);

        UserImportResultDTO respDTO = userService.importUserList(List.of(buildImportUser("zhangsan")), true);

        assertThat(respDTO.getUpdateUsernames()).containsExactly("zhangsan");
        verify(userMapper).updateById(userCaptor.capture());
        // 更新路径不得重置密码；状态为 null，不参与 updateById 更新，已有账号的启用状态保留
        assertThat(userCaptor.getValue().getPassword()).isNull();
        assertThat(userCaptor.getValue().getStatus()).isNull();
        verify(passwordEncoder, never()).encode(anyString());
    }

    // ========== 参数校验 ==========

    @Test
    void importUserList_emptyList_throws() {
        assertThatThrownBy(() -> userService.importUserList(List.of(), false))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(USER_IMPORT_LIST_IS_EMPTY.getCode()));
    }

    private UserImportDTO buildImportUser(String username) {
        return new UserImportDTO().setUsername(username).setNickname("昵称" + username);
    }

    // ========== 用户分页：部门条件走缓存 ==========

    @Test
    void getUserPage_withDeptId_usesCachedChildDeptIdsIncludingSelf() {
        when(deptService.getChildDeptIdListFromCache(10L)).thenReturn(Set.of(11L, 12L));
        when(userMapper.selectPage(any(PageParam.class), any(AdminUserQuery.class)))
                .thenReturn(PageResult.empty());

        userService.getUserPage(new PageParam(), new AdminUserQuery(null, null, null, null, null, null), 10L, null);

        verify(deptService).getChildDeptIdListFromCache(10L);
        verify(deptService, never()).getChildDeptList(any(Long.class));
        ArgumentCaptor<AdminUserQuery> queryCaptor = ArgumentCaptor.forClass(AdminUserQuery.class);
        verify(userMapper).selectPage(any(), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getDeptIds()).containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void getUserPage_withoutDeptId_skipsDeptLookup() {
        when(userMapper.selectPage(any(PageParam.class), any(AdminUserQuery.class)))
                .thenReturn(PageResult.empty());

        userService.getUserPage(new PageParam(), new AdminUserQuery(null, null, null, null, null, null), null, null);

        verify(deptService, never()).getChildDeptIdListFromCache(any());
        ArgumentCaptor<AdminUserQuery> queryCaptor = ArgumentCaptor.forClass(AdminUserQuery.class);
        verify(userMapper).selectPage(any(), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getDeptIds()).isEmpty();
    }
}
