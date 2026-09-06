package com.basicframework.module.system.dal.redis;

import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;

/**
 * System Redis Key 枚举类
 *
 */
public interface RedisKeyConstants {

    // 以下 Spring Cache 键统一带 #ttl 兜底过期时间（TimeoutRedisCacheManager 解析），
    // 正常由写操作 @CacheEvict 精确失效；TTL 只兜住漏失效的脏数据，不替代失效逻辑。

    /**
     * 指定部门的所有子部门编号数组的缓存
     * <p>
     * KEY 格式：dept_children_ids:{id}
     * VALUE 数据类型：String 子部门编号集合
     * 兜底过期时间：30 分钟
     */
    String DEPT_CHILDREN_ID_LIST = "dept_children_ids#30m";

    /**
     * 角色的缓存
     * <p>
     * KEY 格式：role:{id}
     * VALUE 数据类型：String 角色信息
     */
    String ROLE = "role";

    /**
     * 用户拥有的角色编号的缓存
     * <p>
     * KEY 格式：user_role_ids:{userId}
     * VALUE 数据类型：String 角色编号集合
     * 兜底过期时间：30 分钟
     */
    String USER_ROLE_ID_LIST = "user_role_ids#30m";

    /**
     * 拥有指定菜单的角色编号的缓存
     * <p>
     * KEY 格式：menu_role_ids:{menuId}
     * VALUE 数据类型：String 角色编号集合
     * 兜底过期时间：30 分钟
     */
    String MENU_ROLE_ID_LIST = "menu_role_ids#30m";

    /**
     * 拥有权限对应的菜单编号数组的缓存
     * <p>
     * KEY 格式：permission_menu_ids:{permission}
     * VALUE 数据类型：String 菜单编号数组
     * 兜底过期时间：30 分钟
     */
    String PERMISSION_MENU_ID_LIST = "permission_menu_ids#30m";

    /**
     * 字典数据列表的缓存（按状态与类型筛选）
     * <p>
     * KEY 格式：dict_data_list:{status}:{dictType}
     * VALUE 数据类型：String 字典数据数组
     * 兜底过期时间：30 分钟
     */
    String DICT_DATA_LIST = "dict_data_list#30m";

    /**
     * 指定类型的字典数据数组的缓存
     * <p>
     * KEY 格式：dict_data_list_by_type:{dictType}
     * VALUE 数据类型：String 字典数据数组
     * 兜底过期时间：30 分钟
     */
    String DICT_DATA_LIST_BY_TYPE = "dict_data_list_by_type#30m";

    /**
     * 访问令牌的缓存
     * <p>
     * KEY 格式：user_session:{token-sha256}
     * VALUE 数据类型：String 会话信息 {@link UserSessionDO}
     * <p>
     * 由于动态过期时间，使用 RedisTemplate 操作
     */
    String USER_SESSION = "user_session:%s";

    /** 一次性 MFA 挑战，动态过期时间，VALUE 为 MfaChallengeDTO JSON。 */
    String MFA_CHALLENGE = "mfa_challenge:%s";

    /** 已完成二次验证的访问令牌摘要，动态过期时间，VALUE 为用户编号。 */
    String MFA_STEP_UP = "mfa_step_up:%s";

    /** 账号密码登录失败次数；动态过期时间，KEY 参数为用户编号。 */
    String LOGIN_FAILURES = "login_failures:%s";

    /** 账号密码登录锁定状态；动态过期时间，KEY 参数为用户编号。 */
    String LOGIN_LOCK = "login_lock:%s";

    /** 短信验证码校验失败次数；动态过期时间不超过验证码剩余有效期，KEY 参数为验证码记录编号。 */
    String SMS_CODE_VALIDATE_FAILURES = "sms_code_validate_failures:%d";

    /**
     * 站内信模版的缓存
     * <p>
     * KEY 格式：notify_template:{code}
     * VALUE 数据格式：String 模版信息
     */
    String NOTIFY_TEMPLATE = "notify_template";

    /**
     * 短信模版的缓存
     * <p>
     * KEY 格式：sms_template:{id}
     * VALUE 数据格式：String 模版信息
     */
    String SMS_TEMPLATE = "sms_template";
}
