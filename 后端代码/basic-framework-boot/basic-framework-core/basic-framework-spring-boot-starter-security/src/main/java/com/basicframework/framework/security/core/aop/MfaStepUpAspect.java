package com.basicframework.framework.security.core.aop;

import static com.basicframework.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.annotation.MfaStepUp;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.system.api.auth.MfaCommonApi;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** 高风险操作 MFA 二次验证切面。 */
@Aspect
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
public class MfaStepUpAspect {

    private final SecurityProperties securityProperties;
    private final MfaCommonApi mfaApi;

    @Before("@annotation(mfaStepUp)")
    public void requireStepUp(MfaStepUp mfaStepUp) {
        HttpServletRequest request = WebFrameworkUtils.getRequest();
        String accessToken = request == null
                ? null
                : SecurityFrameworkUtils.obtainAuthorization(
                        request, securityProperties.getTokenHeader(), securityProperties.getTokenParameter());
        mfaApi.requireStepUp(accessToken, getLoginUserId());
    }
}
