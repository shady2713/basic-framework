package com.basicframework.module.system.controller.admin.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Schema(description = "管理后台 - MFA 恢复码验证 Request VO")
@Data
@ToString(exclude = {"mfaToken", "recoveryCode"})
public class AuthMfaRecoveryVerifyReqVO {

    @NotBlank(message = "MFA 挑战令牌不能为空")
    @Size(max = 128, message = "MFA 挑战令牌长度不能超过 128")
    private String mfaToken;

    @Schema(description = "一次性恢复码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "恢复码不能为空")
    @Pattern(regexp = "(?i)[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}", message = "恢复码格式不正确")
    private String recoveryCode;
}
