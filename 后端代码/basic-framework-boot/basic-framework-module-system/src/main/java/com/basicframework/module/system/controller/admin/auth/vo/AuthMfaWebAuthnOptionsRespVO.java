package com.basicframework.module.system.controller.admin.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - WebAuthn ceremony Response VO")
@Data
public class AuthMfaWebAuthnOptionsRespVO {

    @Schema(description = "一次性 ceremony 令牌", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ceremonyToken;

    @Schema(description = "传给 navigator.credentials 的 JSON 选项", requiredMode = Schema.RequiredMode.REQUIRED)
    private String optionsJson;
}
