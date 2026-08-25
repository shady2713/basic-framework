package com.basicframework.module.system.controller.admin.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID;

import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.web.config.WebProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 管理刷新令牌 Cookie 的唯一入口。 */
@Component
public class AuthRefreshTokenCookieManager {

    static final String COOKIE_NAME = "basic_framework_refresh_token";
    private static final int MAX_TOKEN_LENGTH = 128;
    private static final String SAME_SITE = "Strict";

    private final SecurityProperties securityProperties;
    private final String cookiePath;

    public AuthRefreshTokenCookieManager(SecurityProperties securityProperties, WebProperties webProperties) {
        this.securityProperties = securityProperties;
        this.cookiePath = normalizeApiPrefix(webProperties.getAdminApi().getPrefix()) + "/system/auth";
    }

    public void issue(HttpServletResponse response, String refreshToken) {
        if (!isValid(refreshToken)) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        response.addHeader(
                HttpHeaders.SET_COOKIE, buildCookie(refreshToken, null).toString());
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return isValid(value) ? value : null;
            }
        }
        return null;
    }

    public String require(HttpServletRequest request) {
        String refreshToken = read(request);
        if (!isValid(refreshToken)) {
            throw exception(SESSION_REFRESH_TOKEN_INVALID);
        }
        return refreshToken;
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(
                HttpHeaders.SET_COOKIE, buildCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie buildCookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(securityProperties.getRefreshCookie().isSecure())
                .sameSite(SAME_SITE)
                .path(cookiePath);
        if (maxAge != null) {
            builder.maxAge(maxAge);
        }
        return builder.build();
    }

    private static boolean isValid(String refreshToken) {
        return StringUtils.hasText(refreshToken) && refreshToken.length() <= MAX_TOKEN_LENGTH;
    }

    private static String normalizeApiPrefix(String prefix) {
        int end = prefix.length();
        while (end > 1 && prefix.charAt(end - 1) == '/') {
            end--;
        }
        return prefix.substring(0, end);
    }
}
