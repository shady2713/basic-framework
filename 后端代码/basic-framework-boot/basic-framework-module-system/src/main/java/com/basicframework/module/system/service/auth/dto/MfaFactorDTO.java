package com.basicframework.module.system.service.auth.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

/** 当前用户可管理的 MFA 因子摘要，不包含任何密钥材料。 */
@Value
@Builder
public class MfaFactorDTO {
    Long id;
    String type;
    String name;
    LocalDateTime createTime;
}
