package com.basicframework.framework.common.security;

import com.basicframework.framework.common.enums.UserTypeEnum;
import org.springframework.lang.Nullable;

/**
 * "当前登录用户身份"能力接缝
 *
 * 由 basic-framework-spring-boot-starter-security 注册实现 Bean，数据来源于
 * Spring Security 上下文中的登录用户；消费者（如 mybatis 字段填充、数据权限）
 * 经 ObjectProvider 注入，缺省时回退为"无登录用户"语义，即各查询方法返回 null、
 * {@link #isSkipPermissionCheck()} 返回 false。
 *
 */
public interface CurrentUserProvider {

    /**
     * 获得当前登录用户的编号
     *
     * @return 用户编号；无登录用户时返回 null
     */
    @Nullable
    Long getLoginUserId();

    /**
     * 获得当前登录用户的类型
     *
     * @return 用户类型，关联 {@link UserTypeEnum}；无登录用户时返回 null
     */
    @Nullable
    Integer getLoginUserType();

    /**
     * 获得当前登录用户请求级上下文中的缓存值
     *
     * @param key 缓存键
     * @param type 缓存值类型
     * @param <T> 缓存值类型
     * @return 缓存值；无登录用户或缓存不存在时返回 null
     */
    @Nullable
    <T> T getContext(String key, Class<T> type);

    /**
     * 写入当前登录用户请求级上下文，用于基于登录用户维度的临时缓存
     *
     * @param key 缓存键
     * @param value 缓存值；无登录用户时忽略本次写入
     */
    void setContext(String key, Object value);

    /**
     * 是否跳过权限校验，包括数据权限、功能权限
     *
     * @return 是否跳过；默认实现恒为 false，即不跳过
     */
    default boolean isSkipPermissionCheck() {
        return false;
    }
}
