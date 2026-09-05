package com.basicframework.module.system.api.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.mysql.permission.MenuMapper;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuReferenceCommonApiImplTest {

    @InjectMocks
    private MenuReferenceCommonApiImpl api;

    @Mock
    private MenuMapper menuMapper;

    @Test
    void isParentMenuAvailable_acceptsRootWithoutDatabaseLookup() {
        assertThat(api.isParentMenuAvailable(MenuDO.ID_ROOT)).isTrue();

        verify(menuMapper, never()).selectById(MenuDO.ID_ROOT);
    }

    @Test
    void isParentMenuAvailable_rejectsNullWithoutDatabaseLookup() {
        assertThat(api.isParentMenuAvailable(null)).isFalse();

        verify(menuMapper, never()).selectById(null);
    }

    @Test
    void isParentMenuAvailable_rejectsButton() {
        when(menuMapper.selectById(10L)).thenReturn(new MenuDO().setId(10L).setType(MenuTypeEnum.BUTTON.getType()));

        assertThat(api.isParentMenuAvailable(10L)).isFalse();
    }

    @Test
    void findUnavailableParentMenuIds_batchesLookupAndKeepsMissingOrButtonMenus() {
        Set<Long> menuIds = Set.of(10L, 20L, 30L);
        when(menuMapper.selectByIds(menuIds))
                .thenReturn(List.of(
                        new MenuDO().setId(10L).setType(MenuTypeEnum.DIR.getType()),
                        new MenuDO().setId(20L).setType(MenuTypeEnum.BUTTON.getType())));

        assertThat(api.findUnavailableParentMenuIds(menuIds)).containsExactlyInAnyOrder(20L, 30L);
    }

    @Test
    void findUnavailableParentMenuIds_acceptsOnlyRootWithoutDatabaseLookup() {
        assertThat(api.findUnavailableParentMenuIds(List.of(MenuDO.ID_ROOT))).isEmpty();

        verify(menuMapper, never()).selectByIds(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void findUnavailableParentMenuIds_acceptsNullWithoutDatabaseLookup() {
        assertThat(api.findUnavailableParentMenuIds(null)).isEmpty();

        verify(menuMapper, never()).selectByIds(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void lockParentMenuIfAvailable_acceptsActiveDirectory() {
        when(menuMapper.selectByIdForShare(10L))
                .thenReturn(new MenuDO().setId(10L).setType(MenuTypeEnum.DIR.getType()));

        assertThat(api.lockParentMenuIfAvailable(10L)).isTrue();
    }

    @Test
    void lockParentMenuIfAvailable_rejectsNullWithoutDatabaseLookup() {
        assertThat(api.lockParentMenuIfAvailable(null)).isFalse();

        verify(menuMapper, never()).selectByIdForShare(null);
    }

    @Test
    void lockParentMenuIfAvailable_acceptsRootWithoutDatabaseLookup() {
        assertThat(api.lockParentMenuIfAvailable(MenuDO.ID_ROOT)).isTrue();

        verify(menuMapper, never()).selectByIdForShare(MenuDO.ID_ROOT);
    }
}
