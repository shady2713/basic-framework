package com.basicframework.module.system.dal.dataobject.user;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import com.basicframework.module.system.enums.common.SexEnum;
import com.basicframework.module.system.framework.operatelog.core.DeptParseFunction;
import com.basicframework.module.system.framework.operatelog.core.EmailDesensitizeParseFunction;
import com.basicframework.module.system.framework.operatelog.core.MobileDesensitizeParseFunction;
import com.basicframework.module.system.framework.operatelog.core.PostParseFunction;
import com.basicframework.module.system.framework.operatelog.core.SexParseFunction;
import com.mzt.logapi.starter.annotation.DiffLogField;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 管理后台的用户 DO
 *
 */
@TableName(value = "system_users", autoResultMap = true) // 由于 SQL Server 的 system_user 是关键字，所以使用 system_users
@KeySequence("system_users_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDO extends SoftDeletableDO {

    /**
     * 用户ID
     */
    @TableId
    private Long id;
    /**
     * 用户账号
     */
    @DiffLogField(name = "用户账号")
    private String username;
    /**
     * 加密后的密码
     *
     * 因为目前使用 {@link BCryptPasswordEncoder} 加密器，所以无需自己处理 salt 盐
     */
    @ToString.Exclude
    private String password;
    /**
     * 用户昵称
     */
    @DiffLogField(name = "用户昵称")
    private String nickname;
    /**
     * 备注
     */
    @DiffLogField(name = "备注")
    private String remark;
    /**
     * 部门 ID
     */
    @DiffLogField(name = "部门", function = DeptParseFunction.NAME)
    private Long deptId;
    /**
     * 岗位编号数组
     */
    @DiffLogField(name = "岗位", function = PostParseFunction.NAME)
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<Long> postIds;
    /**
     * 用户邮箱
     */
    @DiffLogField(name = "用户邮箱", function = EmailDesensitizeParseFunction.NAME)
    @ToString.Exclude
    private String email;
    /**
     * 手机号码
     */
    @DiffLogField(name = "手机号", function = MobileDesensitizeParseFunction.NAME)
    @ToString.Exclude
    private String mobile;
    /**
     * 用户性别
     *
     * 枚举类 {@link SexEnum}
     */
    @DiffLogField(name = "用户性别", function = SexParseFunction.NAME)
    private Integer sex;
    /**
     * 用户头像
     */
    @DiffLogField(name = "用户头像")
    private String avatar;
    /**
     * 帐号状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    @DiffLogField(name = "用户状态")
    private Integer status;
    /**
     * 最后登录IP
     */
    private String loginIp;
    /**
     * 最后登录时间
     */
    private LocalDateTime loginDate;
}
