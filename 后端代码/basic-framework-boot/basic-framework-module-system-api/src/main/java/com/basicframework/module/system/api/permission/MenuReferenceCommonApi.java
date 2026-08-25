package com.basicframework.module.system.api.permission;

import java.util.Collection;
import java.util.Set;

/** 菜单逻辑引用 API。 */
public interface MenuReferenceCommonApi {

    /**
     * 判断菜单是否可作为业务配置的父菜单。根节点 {@code 0} 视为可用。
     *
     * @param menuId 菜单编号
     * @return 是否为活动的目录或菜单
     */
    boolean isParentMenuAvailable(Long menuId);

    /**
     * 批量筛选不能作为业务配置父菜单的编号。根节点 {@code 0} 不会出现在结果中。
     *
     * @param menuIds 菜单编号集合
     * @return 不可用的菜单编号集合
     */
    Set<Long> findUnavailableParentMenuIds(Collection<Long> menuIds);

    /**
     * 共享锁定菜单并判断是否可作为业务配置的父菜单。调用方应处于写事务中。
     *
     * @param menuId 菜单编号
     * @return 是否为活动的目录或菜单
     */
    boolean lockParentMenuIfAvailable(Long menuId);
}
