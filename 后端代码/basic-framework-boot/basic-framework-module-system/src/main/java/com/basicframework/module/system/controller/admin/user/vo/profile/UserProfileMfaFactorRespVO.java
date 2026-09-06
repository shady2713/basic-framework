package com.basicframework.module.system.controller.admin.user.vo.profile;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

@Schema(description = "管理后台 - 当前用户 MFA 因子 Response VO")
@Data
public class UserProfileMfaFactorRespVO {

    @Schema(description = "因子编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "因子类型", example = "TOTP", requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    @Schema(description = "安全展示名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "注册时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;
}
