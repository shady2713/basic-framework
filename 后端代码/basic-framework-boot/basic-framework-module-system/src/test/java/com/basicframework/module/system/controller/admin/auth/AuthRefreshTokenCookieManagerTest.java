package com.basicframework.module.system.controller.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRefreshTokenCookieManagerTest {

    private AuthRefreshTokenCookieManager cookieManager;

    @BeforeEach
    void setUp() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.getRefreshCookie().setSecure(true);
        cookieManager = new AuthRefreshTokenCookieManager(securityProperties, new WebProperties());
    }

    @Test
    void issue_usesHostOnlyHardenedSessionCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieManager.issue(response, "refresh-token");

        assertThat(response.getHeader("Set-Cookie"))
                .isEqualTo(
                        "basic_framework_refresh_token=refresh-token; Path=/admin-api/system/auth; Secure; HttpOnly; SameSite=Strict");
    }

    @Test
    void require_readsValidCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthRefreshTokenCookieManager.COOKIE_NAME, "refresh-token"));

        assertThat(cookieManager.require(request)).isEqualTo("refresh-token");
    }

    @Test
    void require_rejectsMissingOrOversizedCookie() {
        assertInvalid(new MockHttpServletRequest());
        MockHttpServletRequest oversized = new MockHttpServletRequest();
        oversized.setCookies(new Cookie(AuthRefreshTokenCookieManager.COOKIE_NAME, "x".repeat(129)));
        assertInvalid(oversized);
    }

    @Test
    void clear_expiresCookieAtSamePath() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieManager.clear(response);

        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("basic_framework_refresh_token=;")
                .contains(
                        "Path=/admin-api/system/auth",
                        "Max-Age=0",
                        "Expires=Thu, 1 Jan 1970 00:00:00 GMT",
                        "Secure",
                        "HttpOnly",
                        "SameSite=Strict");
    }

    private void assertInvalid(MockHttpServletRequest request) {
        assertThatThrownBy(() -> cookieManager.require(request))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode());
    }
}
