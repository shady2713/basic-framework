package com.basicframework.module.system.controller.admin.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - TOTP 验证 Request VO")
@Data
public class AuthMfaTotpVerifyReqVO {

    @NotBlank(message = "MFA 挑战令牌不能为空")
    @Size(max = 128, message = "MFA 挑战令牌长度不能超过 128")
    private String mfaToken;

    @Schema(description = "6 位动态验证码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "动态验证码不能为空")
    @Pattern(regexp = "\\d{6}", message = "动态验证码必须是 6 位数字")
    private String code;
}
