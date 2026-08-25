package com.basicframework.module.system.dal.dataobject.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一次性 MFA 恢复码。只保存带密钥摘要，不保存明文。 */
@TableName("system_user_mfa_recovery_code")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaRecoveryCodeDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String codeHash;

    private LocalDateTime usedTime;

    private LocalDateTime createTime;
}
