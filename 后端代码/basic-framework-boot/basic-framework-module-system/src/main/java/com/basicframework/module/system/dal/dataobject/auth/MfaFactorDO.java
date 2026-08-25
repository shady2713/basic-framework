package com.basicframework.module.system.dal.dataobject.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** 用户 MFA 因子。敏感密钥只保存 AES-GCM 密文。 */
@TableName("system_user_mfa_factor")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaFactorDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer factorType;

    private String name;

    @ToString.Exclude
    private String secretCiphertext;

    private byte[] credentialId;

    private byte[] userHandle;

    private byte[] publicKeyCose;

    private Long signatureCount;

    private Boolean backupEligible;

    private Boolean backupState;

    private Long lastUsedStep;

    private String transports;

    private Boolean enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
