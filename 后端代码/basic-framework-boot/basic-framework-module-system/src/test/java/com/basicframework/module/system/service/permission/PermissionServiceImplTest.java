package com.basicframework.module.system.service.permission;

import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.ROLE_PERMISSION_CHANGED;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.USER_ROLE_CHANGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.permission.RoleMenuDO;
import com.basicframework.module.system.dal.dataobject.permission.UserRoleDO;
import com.basicframework.module.system.dal.mysql.permission.RoleMenuMapper;
import com.basicframework.module.system.dal.mysql.permission.UserRoleMapper;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PermissionServiceImpl} 授权流程的单元测试
 *
 * 覆盖关联表物理删除契约：三张关联表（system_user_role、
 * system_role_menu、system_user_post）已加 V3 组合唯一约束，取消授权必须物理删除，
 * 否则残留 deleted=1 的行会让再次授权的 insertBatch 撞唯一键。
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @InjectMocks
    private PermissionServiceImpl permissionService;

    @Mock
    private RoleMenuMapper roleMenuMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleService roleService;

    @Mock
    private MenuService menuService;

    @Mock
    private DeptService deptService;

    @Mock
    private AdminUserService userService;

    @Mock
    private UserSessionRevocationPublisher sessionRevocationPublisher;

    // ========== 用户-角色：授权 -> 取消 -> 再授权 ==========

    @Test
    void assignUserRole_revokeThenReassign_noUniqueKeyConflict() {
        Long userId = 1001L;
        Long roleId = 201L;

        // 1. 首次授权：库中无关联，直接 insertBatch
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(Collections.emptyList());
        permissionService.assignUserRole(userId, Set.of(roleId));
        verify(userRoleMapper)
                .insertBatch(argThat(list ->
                        list.size() == 1 && roleId.equals(list.iterator().next().getRoleId())));

        // 2. 取消授权：走物理删除方法，物理删后该行不存在（count 为 0），而非 deleted=1 残留
        UserRoleDO dbRole = new UserRoleDO();
        dbRole.setUserId(userId);
        dbRole.setRoleId(roleId);
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(List.of(dbRole));
        permissionService.assignUserRole(userId, Set.of());
        verify(userRoleMapper).deleteListByUserIdAndRoleIdIds(eq(userId), argThat(roleIds -> roleIds.contains(roleId)));

        // 3. 再次授权：物理删后查询无残留，insertBatch 可重复执行，不撞 uk_user_id_role_id 唯一键
        when(userRoleMapper.selectListByUserId(userId)).thenReturn(Collections.emptyList());
        permissionService.assignUserRole(userId, Set.of(roleId));
        verify(userRoleMapper, times(2))
                .insertBatch(argThat(list ->
                        list.size() == 1 && roleId.equals(list.iterator().next().getRoleId())));
        verify(sessionRevocationPublisher, times(3)).revokeAdminSession(userId, USER_ROLE_CHANGED);
    }

    // ========== 角色-菜单：授权 -> 取消 -> 再授权 ==========

    @Test
    void assignRoleMenu_revokeThenReassign_noUniqueKeyConflict() {
        Long roleId = 301L;
        Long menuId = 401L;

        // 1. 首次授权：库中无关联，直接 insertBatch
        when(roleMenuMapper.selectListByRoleId(roleId)).thenReturn(Collections.emptyList());
        permissionService.assignRoleMenu(roleId, Set.of(menuId));
        verify(roleMenuMapper)
                .insertBatch(argThat(list ->
                        list.size() == 1 && menuId.equals(list.iterator().next().getMenuId())));

        // 2. 取消授权：走物理删除方法，物理删后该行不存在（count 为 0），而非 deleted=1 残留
        RoleMenuDO dbMenu = new RoleMenuDO();
        dbMenu.setRoleId(roleId);
        dbMenu.setMenuId(menuId);
        when(roleMenuMapper.selectListByRoleId(roleId)).thenReturn(List.of(dbMenu));
        permissionService.assignRoleMenu(roleId, Set.of());
        verify(roleMenuMapper).deleteListByRoleIdAndMenuIds(eq(roleId), argThat(menuIds -> menuIds.contains(menuId)));

        // 3. 再次授权：物理删后查询无残留，insertBatch 可重复执行，不撞 uk_role_id_menu_id 唯一键
        when(roleMenuMapper.selectListByRoleId(roleId)).thenReturn(Collections.emptyList());
        permissionService.assignRoleMenu(roleId, Set.of(menuId));
        verify(roleMenuMapper, times(2))
                .insertBatch(argThat(list ->
                        list.size() == 1 && menuId.equals(list.iterator().next().getMenuId())));
    }

    @Test
    void assignRoleMenu_changed_revokesAffectedUserSessions() {
        Long roleId = 301L;
        Long menuId = 401L;
        Long userId = 1001L;
        when(roleMenuMapper.selectListByRoleId(roleId)).thenReturn(Collections.emptyList());
        UserRoleDO userRole = new UserRoleDO().setUserId(userId).setRoleId(roleId);
        when(userRoleMapper.selectListByRoleIds(Set.of(roleId))).thenReturn(List.of(userRole));

        permissionService.assignRoleMenu(roleId, Set.of(menuId));

        verify(sessionRevocationPublisher).revokeAdminSessions(Set.of(userId), ROLE_PERMISSION_CHANGED);
    }

    @Test
    void getUserRoleIdListByRoleId_emptyRoleIds_skipsDatabaseQuery() {
        assertEquals(Collections.emptySet(), permissionService.getUserRoleIdListByRoleId(Collections.emptySet()));

        verify(userRoleMapper, never()).selectListByRoleIds(Collections.emptySet());
    }
}
