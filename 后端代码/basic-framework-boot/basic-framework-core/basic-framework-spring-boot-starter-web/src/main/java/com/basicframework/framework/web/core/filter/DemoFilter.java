package com.basicframework.framework.web.core.filter;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.DEMO_DENY;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 演示 Filter，禁止用户发起写操作，避免影响测试数据
 *
 * 响应状态由 {@link GlobalExceptionHandler} 统一映射（ADR 0003：DEMO_DENY 对应 HTTP 403）
 */
@RequiredArgsConstructor
public class DemoFilter extends OncePerRequestFilter {

    private final GlobalExceptionHandler globalExceptionHandler;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !StrUtil.equalsAnyIgnoreCase(method, "POST", "PUT", "DELETE") // 写操作时，不进行过滤率
                || WebFrameworkUtils.getLoginUserId(request) == null; // 非登录用户时，不进行过滤
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        // 直接返回 DEMO_DENY 的结果。即，请求不继续
        GlobalExceptionHandler.writeResponse(
                response, globalExceptionHandler.allExceptionHandler(request, new ServiceException(DEMO_DENY)));
    }
}
