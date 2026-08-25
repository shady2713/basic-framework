package com.basicframework.module.infra.api.codegen;

/** 代码生成逻辑引用 API。 */
public interface CodegenReferenceCommonApi {

    /**
     * 判断菜单是否仍被活动代码生成配置引用。空编号和根节点 {@code 0} 不代表实体菜单，返回 {@code false}。
     *
     * @param menuId 菜单编号
     * @return 是否仍被引用
     */
    boolean isParentMenuReferenced(Long menuId);
}
