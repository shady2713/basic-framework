package com.basicframework.module.system.controller.admin.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - TOTP 设置 Response VO")
@Data
public class AuthMfaTotpSetupRespVO {

    private String enrollmentToken;
    private String secret;
    private String otpauthUri;
}
