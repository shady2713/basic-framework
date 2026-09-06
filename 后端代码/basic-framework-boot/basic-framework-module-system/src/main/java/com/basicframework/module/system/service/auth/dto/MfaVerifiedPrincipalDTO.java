package com.basicframework.module.system.service.auth.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** MFA 验证后的登录主体。 */
@Data
@ToString(exclude = {"recoveryCodes"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaVerifiedPrincipalDTO {

    private Long userId;
    private String username;
    private Integer loginLogType;
    private List<String> recoveryCodes;
}
