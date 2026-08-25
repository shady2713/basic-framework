package com.basicframework.module.system.controller.admin.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - WebAuthn ceremony 完成 Request VO")
@Data
public class AuthMfaWebAuthnFinishReqVO {

    @Schema(description = "一次性 ceremony 令牌", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "WebAuthn ceremony 令牌不能为空")
    @Size(max = 128, message = "WebAuthn ceremony 令牌长度不能超过 128")
    private String ceremonyToken;

    @Schema(description = "浏览器 WebAuthn 响应 JSON", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "WebAuthn 响应不能为空")
    @Size(max = 65_536, message = "WebAuthn 响应长度不能超过 65536")
    private String credentialJson;
}
