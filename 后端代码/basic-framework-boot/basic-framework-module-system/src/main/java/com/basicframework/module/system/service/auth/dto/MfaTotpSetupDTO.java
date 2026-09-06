package com.basicframework.module.system.service.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** TOTP 设置材料，仅在一次性注册开始响应中返回。 */
@Data
@ToString(exclude = {"enrollmentToken", "secret"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaTotpSetupDTO {

    private String enrollmentToken;
    private String secret;
    private String otpauthUri;
}
