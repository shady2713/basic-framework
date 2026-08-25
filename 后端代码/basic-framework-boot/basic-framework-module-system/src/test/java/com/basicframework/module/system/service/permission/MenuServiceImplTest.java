package com.basicframework.module.system.service.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.infra.api.codegen.CodegenReferenceCommonApi;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.mysql.permission.MenuMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class MenuServiceImplTest {

    @InjectMocks
    private MenuServiceImpl menuService;

    @Mock
    private MenuMapper menuMapper;

    @Mock
    private ObjectProvider<PermissionService> permissionServiceProvider;

    @Mock
    private PermissionService permissionService;

    @Mock
    private CodegenReferenceCommonApi codegenReferenceApi;

    @Test
    void createMenu_locksCompleteParentChain() {
        MenuDO grandparent = menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR);
        MenuDO parent = menu(2L, 1L, MenuTypeEnum.MENU);
        MenuDO child = menu(null, 2L, MenuTypeEnum.MENU);
        when(menuMapper.selectByIdForShare(2L)).thenReturn(parent);
        when(menuMapper.selectByIdForShare(1L)).thenReturn(grandparent);

        menuService.createMenu(child);

        InOrder inOrder = inOrder(menuMapper);
        inOrder.verify(menuMapper).selectByIdForShare(2L);
        inOrder.verify(menuMapper).selectByIdForShare(1L);
        verify(menuMapper).insert(child);
    }

    @Test
    void updateMenu_rejectsMovingBelowOwnChild() {
        MenuDO update = menu(1L, 2L, MenuTypeEnum.DIR);
        when(menuMapper.selectByIdForUpdate(1L)).thenReturn(menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR));
        when(menuMapper.selectByIdForShare(2L)).thenReturn(menu(2L, 1L, MenuTypeEnum.DIR));

        assertServiceException(ErrorCodeConstants.MENU_PARENT_IS_CHILD, () -> menuService.updateMenu(update));

        verify(menuMapper, never()).updateById(any(MenuDO.class));
    }

    @Test
    void updateMenu_rejectsCycleOutsideUpdatedMenu() {
        MenuDO update = menu(1L, 2L, MenuTypeEnum.DIR);
        when(menuMapper.selectByIdForUpdate(1L)).thenReturn(menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR));
        when(menuMapper.selectByIdForShare(2L)).thenReturn(menu(2L, 3L, MenuTypeEnum.DIR));
        when(menuMapper.selectByIdForShare(3L)).thenReturn(menu(3L, 2L, MenuTypeEnum.MENU));

        assertServiceException(ErrorCodeConstants.MENU_PARENT_CYCLE, () -> menuService.updateMenu(update));

        verify(menuMapper, never()).updateById(any(MenuDO.class));
    }

    @Test
    void updateMenu_rejectsChangingParentWithChildrenToButton() {
        MenuDO update = menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.BUTTON);
        when(menuMapper.selectByIdForUpdate(1L)).thenReturn(menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR));
        when(menuMapper.selectCountByParentId(1L)).thenReturn(1L);

        assertServiceException(ErrorCodeConstants.MENU_BUTTON_EXISTS_CHILDREN, () -> menuService.updateMenu(update));

        verify(menuMapper, never()).updateById(any(MenuDO.class));
    }

    @Test
    void updateMenu_rejectsChangingCodegenParentToButton() {
        MenuDO update = menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.BUTTON);
        when(menuMapper.selectByIdForUpdate(1L)).thenReturn(menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR));
        when(codegenReferenceApi.isParentMenuReferenced(1L)).thenReturn(true);

        assertServiceException(ErrorCodeConstants.MENU_USED_BY_CODEGEN, () -> menuService.updateMenu(update));

        verify(menuMapper, never()).updateById(any(MenuDO.class));
    }

    @Test
    void deleteMenu_locksBeforeCheckingChildren() {
        when(menuMapper.selectByIdForUpdate(1L)).thenReturn(menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR));
        when(menuMapper.selectCountByParentId(1L)).thenReturn(1L);

        assertServiceException(ErrorCodeConstants.MENU_EXISTS_CHILDREN, () -> menuService.deleteMenu(1L));

        InOrder inOrder = inOrder(menuMapper);
        inOrder.verify(menuMapper).selectByIdForUpdate(1L);
        inOrder.verify(menuMapper).selectCountByParentId(1L);
        verify(menuMapper, never()).deleteById(1L);
    }

    @Test
    void deleteMenu_rejectsCodegenParentMenu() {
        when(menuMapper.selectByIdForUpdate(1L)).thenReturn(menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR));
        when(codegenReferenceApi.isParentMenuReferenced(1L)).thenReturn(true);

        assertServiceException(ErrorCodeConstants.MENU_USED_BY_CODEGEN, () -> menuService.deleteMenu(1L));

        verify(menuMapper, never()).deleteById(1L);
    }

    @Test
    void deleteMenuList_locksDistinctMenusInStableOrder() {
        when(menuMapper.selectByIdForUpdate(1L)).thenReturn(menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.MENU));
        when(menuMapper.selectByIdForUpdate(2L)).thenReturn(menu(2L, MenuDO.ID_ROOT, MenuTypeEnum.MENU));
        when(permissionServiceProvider.getObject()).thenReturn(permissionService);

        menuService.deleteMenuList(List.of(2L, 1L, 2L));

        InOrder inOrder = inOrder(menuMapper);
        inOrder.verify(menuMapper).selectByIdForUpdate(1L);
        inOrder.verify(menuMapper).selectByIdForUpdate(2L);
        verify(menuMapper).deleteByIds(List.of(1L, 2L));
        verify(permissionService).processMenuDeleted(1L);
        verify(permissionService).processMenuDeleted(2L);
    }

    @Test
    void filterDisableMenus_disablesCycleInsteadOfRecursingForever() {
        List<MenuDO> cyclicMenus = List.of(menu(1L, 2L, MenuTypeEnum.DIR), menu(2L, 1L, MenuTypeEnum.MENU));

        assertThat(menuService.filterDisableMenus(cyclicMenus)).isEmpty();
    }

    @Test
    void filterDisableMenus_keepsEnabledTree() {
        List<MenuDO> menus = List.of(menu(1L, MenuDO.ID_ROOT, MenuTypeEnum.DIR), menu(2L, 1L, MenuTypeEnum.MENU));

        assertThat(menuService.filterDisableMenus(menus)).containsExactlyElementsOf(menus);
    }

    private static MenuDO menu(Long id, Long parentId, MenuTypeEnum type) {
        return new MenuDO()
                .setId(id)
                .setName("菜单-" + id)
                .setParentId(parentId)
                .setType(type.getType())
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

    private static void assertServiceException(ErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(errorCode.getCode());
    }
}
