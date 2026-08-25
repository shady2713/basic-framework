package com.basicframework.module.system.controller.admin.user.vo.profile;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "管理后台 - 当前用户 MFA 恢复码 Response VO")
@Data
@AllArgsConstructor
public class UserProfileMfaRecoveryCodesRespVO {

    @Schema(description = "仅展示一次的一次性恢复码")
    private List<String> recoveryCodes;
}
