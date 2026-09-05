package com.basicframework.module.system.controller.admin.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Schema(description = "管理后台 - 登录 Response VO")
@Data
@ToString(exclude = {"accessToken", "mfaToken", "recoveryCodes"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthLoginRespVO {

    @Schema(description = "用户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long userId;

    @Schema(description = "访问令牌；MFA 完成前为空", example = "happy")
    private String accessToken;

    @Schema(description = "令牌过期时间；MFA 完成前为空")
    private LocalDateTime expiresTime;

    @Schema(description = "是否需要完成 MFA 后才能签发令牌")
    private Boolean mfaRequired;

    @Schema(description = "是否必须先注册 MFA 因子")
    private Boolean mfaEnrollmentRequired;

    @Schema(description = "一次性 MFA 挑战令牌")
    private String mfaToken;

    @Schema(description = "当前可用的 MFA 方法")
    private List<String> mfaMethods;

    @Schema(description = "仅在首次注册成功后展示一次的恢复码")
    private List<String> recoveryCodes;
}
