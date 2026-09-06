package com.basicframework.module.system.controller.admin.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Schema(description = "管理后台 - MFA 挑战 Request VO")
@Data
@ToString(exclude = {"mfaToken"})
public class AuthMfaTokenReqVO {

    @Schema(description = "一次性 MFA 挑战令牌", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "MFA 挑战令牌不能为空")
    @Size(max = 128, message = "MFA 挑战令牌长度不能超过 128")
    private String mfaToken;
}
