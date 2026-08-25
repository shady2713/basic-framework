package com.basicframework.module.system.service.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** WebAuthn ceremony 的一次性令牌与浏览器选项。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaWebAuthnOptionsDTO {

    private String ceremonyToken;
    private String optionsJson;
}
