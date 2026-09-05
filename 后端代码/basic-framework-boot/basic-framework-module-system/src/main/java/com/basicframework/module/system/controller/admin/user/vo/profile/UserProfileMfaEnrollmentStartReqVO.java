package com.basicframework.module.system.controller.admin.user.vo.profile;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Schema(description = "管理后台 - 当前用户 MFA 自助注册开始 Request VO")
@Data
@ToString(exclude = {"password"})
public class UserProfileMfaEnrollmentStartReqVO {

    @Schema(description = "当前密码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "当前密码不能为空")
    @Size(max = 128, message = "当前密码长度不能超过 128")
    private String password;
}
