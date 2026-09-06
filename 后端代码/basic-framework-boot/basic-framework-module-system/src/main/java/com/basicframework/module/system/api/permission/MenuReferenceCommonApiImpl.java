package com.basicframework.module.system.api.permission;

import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.mysql.permission.MenuMapper;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 菜单逻辑引用 API 实现。 */
@Service
@RequiredArgsConstructor
public class MenuReferenceCommonApiImpl implements MenuReferenceCommonApi {

    private final MenuMapper menuMapper;

    @Override
    public boolean isParentMenuAvailable(Long menuId) {
        if (menuId == null) {
            return false;
        }
        if (MenuDO.ID_ROOT.equals(menuId)) {
            return true;
        }
        return isParentCapable(menuMapper.selectById(menuId));
    }

    @Override
    public Set<Long> findUnavailableParentMenuIds(Collection<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> unavailableMenuIds = new HashSet<>(menuIds);
        unavailableMenuIds.remove(null);
        unavailableMenuIds.remove(MenuDO.ID_ROOT);
        if (unavailableMenuIds.isEmpty()) {
            return Set.of();
        }
        menuMapper.selectByIds(unavailableMenuIds).stream()
                .filter(MenuReferenceCommonApiImpl::isParentCapable)
                .map(MenuDO::getId)
                .filter(Objects::nonNull)
                .forEach(unavailableMenuIds::remove);
        return Set.copyOf(unavailableMenuIds);
    }

    @Override
    public boolean lockParentMenuIfAvailable(Long menuId) {
        if (menuId == null) {
            return false;
        }
        if (MenuDO.ID_ROOT.equals(menuId)) {
            return true;
        }
        return isParentCapable(menuMapper.selectByIdForShare(menuId));
    }

    private static boolean isParentCapable(MenuDO menu) {
        return menu != null
                && (MenuTypeEnum.DIR.getType().equals(menu.getType())
                        || MenuTypeEnum.MENU.getType().equals(menu.getType()));
    }
}
