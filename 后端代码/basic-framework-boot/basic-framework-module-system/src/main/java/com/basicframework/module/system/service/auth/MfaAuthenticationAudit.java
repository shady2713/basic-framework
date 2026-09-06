package com.basicframework.module.system.service.auth;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.util.monitor.TracerUtils;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.module.system.enums.logger.LoginResultEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.logger.LoginLogService;
import com.basicframework.module.system.service.logger.dto.LoginLogCreateReqDTO;
import org.springframework.stereotype.Component;

/** 记录 MFA 认证失败事件，不记录验证码、恢复码或 WebAuthn 响应。 */
@Component
public class MfaAuthenticationAudit {

    private final LoginLogService loginLogService;

    public MfaAuthenticationAudit(LoginLogService loginLogService) {
        this.loginLogService = loginLogService;
    }

    public void recordFailure(MfaChallengeDTO challenge) {
        LoginLogCreateReqDTO request = new LoginLogCreateReqDTO();
        request.setLogType(challenge.getLoginLogType());
        request.setTraceId(TracerUtils.getTraceId());
        request.setUserId(challenge.getUserId());
        request.setUserType(UserTypeEnum.ADMIN.getValue());
        request.setUsername(challenge.getUsername());
        request.setUserAgent(ServletUtils.getUserAgent());
        request.setUserIp(ServletUtils.getClientIP());
        request.setResult(LoginResultEnum.MFA_CODE_ERROR.getResult());
        loginLogService.createLoginLog(request);
    }
}
