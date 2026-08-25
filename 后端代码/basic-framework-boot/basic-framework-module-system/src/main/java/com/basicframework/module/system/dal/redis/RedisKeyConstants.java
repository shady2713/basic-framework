package com.basicframework.module.system.dal.redis;

import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;

/**
 * System Redis Key 枚举类
 *
 */
public interface RedisKeyConstants {

    /**
     * 指定部门的所有子部门编号数组的缓存
     * <p>
     * KEY 格式：dept_children_ids:{id}
     * VALUE 数据类型：String 子部门编号集合
     */
    String DEPT_CHILDREN_ID_LIST = "dept_children_ids";

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
     */
    String USER_ROLE_ID_LIST = "user_role_ids";

    /**
     * 拥有指定菜单的角色编号的缓存
     * <p>
     * KEY 格式：user_role_ids:{menuId}
     * VALUE 数据类型：String 角色编号集合
     */
    String MENU_ROLE_ID_LIST = "menu_role_ids";

    /**
     * 拥有权限对应的菜单编号数组的缓存
     * <p>
     * KEY 格式：permission_menu_ids:{permission}
     * VALUE 数据类型：String 菜单编号数组
     */
    String PERMISSION_MENU_ID_LIST = "permission_menu_ids";

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

    /**
     * 小程序订阅模版的缓存
     *
     * KEY 格式：wxa_subscribe_template:{userType}
     * VALUE 数据格式 String, 模版信息
     */
    String WXA_SUBSCRIBE_TEMPLATE = "wxa_subscribe_template";
}
