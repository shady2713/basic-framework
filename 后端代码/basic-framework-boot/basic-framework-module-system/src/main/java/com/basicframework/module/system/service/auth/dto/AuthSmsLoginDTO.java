package com.basicframework.module.system.service.auth.dto;

import com.basicframework.framework.common.validation.Mobile;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;

/**
 * 管理后台 - 短信验证码登录参数 DTO
 */
@Data
@ToString(exclude = {"mobile", "code"})
public class AuthSmsLoginDTO {

    /**
     * 手机号
     */
    @NotEmpty(message = "手机号不能为空")
    @Mobile
    private String mobile;

    /**
     * 短信验证码
     */
    @NotEmpty(message = "验证码不能为空")
    private String code;
}
