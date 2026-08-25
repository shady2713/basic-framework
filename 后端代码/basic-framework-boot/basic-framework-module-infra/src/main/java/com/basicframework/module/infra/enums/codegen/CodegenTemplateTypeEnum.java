package com.basicframework.module.infra.enums.codegen;

import com.basicframework.framework.common.util.object.ObjectUtils;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 代码生成模板类型
 *
 */
@AllArgsConstructor
@Getter
public enum CodegenTemplateTypeEnum {

    /** 单表（增删改查） */
    ONE(1),
    /** 树表（增删改查） */
    TREE(2),

    /** 主子表 - 主表 - 普通模式 */
    MASTER_NORMAL(10),
    /** 主子表 - 主表 - ERP 模式 */
    MASTER_ERP(11),
    /** 主子表 - 主表 - 内嵌模式 */
    MASTER_INNER(12),
    /** 主子表 - 子表 */
    SUB(15),
    ;

    /**
     * 类型
     */
    private final Integer type;

    /**
     * 是否为主表
     *
     * @param type 类型
     * @return 是否主表
     */
    public static boolean isMaster(Integer type) {
        return ObjectUtils.equalsAny(type, MASTER_NORMAL.type, MASTER_ERP.type, MASTER_INNER.type);
    }

    /**
     * 是否为子表
     *
     * @param type 类型
     * @return 是否子表
     */
    public static boolean isSub(Integer type) {
        return Objects.equals(type, SUB.type);
    }

    /**
     * 是否为树表
     *
     * @param type 类型
     * @return 是否树表
     */
    public static boolean isTree(Integer type) {
        return Objects.equals(type, TREE.type);
    }

    /**
     * 是否为支持的模板类型
     *
     * @param type 类型
     * @return 是否支持
     */
    public static boolean isSupported(Integer type) {
        return ObjectUtils.equalsAny(
                type, ONE.type, TREE.type, MASTER_NORMAL.type, MASTER_ERP.type, MASTER_INNER.type, SUB.type);
    }
}
