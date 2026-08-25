package com.basicframework.framework.security.core.filter;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.system.api.session.UserSessionCommonApi;
import com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token 过滤器，负责校验请求携带的访问令牌。
 * 校验通过后，将解析出的 {@link LoginUser} 写入 Spring Security 上下文。
 */
@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final SecurityProperties securityProperties;

    private final GlobalExceptionHandler globalExceptionHandler;

    private final ObjectProvider<UserSessionCommonApi> userSessionApiProvider;

    @Override
    @SuppressWarnings("NullableProblems")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = SecurityFrameworkUtils.obtainAuthorization(
                request, securityProperties.getTokenHeader(), securityProperties.getTokenParameter());
        if (StrUtil.isNotEmpty(token)) {
            Integer userType = WebFrameworkUtils.getLoginUserType(request);
            try {
                // 基于标准 token 校验结果构建登录用户，开源版不再保留 mock 登录兜底能力。
                LoginUser loginUser = buildLoginUserByToken(token, userType);
                // 仅在标准 token 校验通过时写入当前登录用户。
                if (loginUser != null) {
                    SecurityFrameworkUtils.setLoginUser(loginUser, request);
                }
            } catch (Throwable ex) {
                GlobalExceptionHandler.writeResponse(response, globalExceptionHandler.allExceptionHandler(request, ex));
                return;
            }
        }

        // 继续后续过滤链。
        chain.doFilter(request, response);
    }

    private LoginUser buildLoginUserByToken(String token, Integer userType) {
        try {
            UserSessionCheckRespDTO session = userSessionApiProvider.getObject().checkAccessToken(token);
            if (session == null) {
                return null;
            }
            // 用户类型不匹配时直接拒绝访问。
            // 仅 /admin-api/* 和 /app-api/* 这类请求会带用户类型，其他场景允许为空。
            if (userType != null && ObjectUtil.notEqual(session.getUserType(), userType)) {
                throw new AccessDeniedException("错误的用户类型");
            }
            // 构建登录用户并透传权限和附加信息。
            return new LoginUser()
                    .setId(session.getUserId())
                    .setUserType(session.getUserType())
                    .setInfo(session.getUserInfo())
                    .setExpiresTime(session.getAccessExpiresTime());
        } catch (ServiceException serviceException) {
            // token 校验失败时直接按未登录处理，交由后续鉴权链路决定是否允许访问。
            return null;
        }
    }
}
