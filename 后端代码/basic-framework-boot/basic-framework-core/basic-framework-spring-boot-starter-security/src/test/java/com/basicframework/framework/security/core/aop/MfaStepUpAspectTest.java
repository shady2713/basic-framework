package com.basicframework.framework.security.core.aop;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.annotation.MfaStepUp;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.system.api.auth.MfaCommonApi;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class MfaStepUpAspectTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireStepUp_passesCurrentBearerTokenAndUserToCommonApi() {
        SecurityProperties properties = new SecurityProperties();
        MfaCommonApi mfaApi = mock(MfaCommonApi.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(properties.getTokenHeader())).thenReturn("Bearer access-token");
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(42L), request);

        try (var webFramework = mockStatic(WebFrameworkUtils.class)) {
            webFramework.when(WebFrameworkUtils::getRequest).thenReturn(request);

            new MfaStepUpAspect(properties, mfaApi).requireStepUp(mock(MfaStepUp.class));
        }

        verify(mfaApi).requireStepUp("access-token", 42L);
    }
}
