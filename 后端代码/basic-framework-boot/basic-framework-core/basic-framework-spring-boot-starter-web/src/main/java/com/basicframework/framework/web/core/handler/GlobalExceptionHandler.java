package com.basicframework.framework.web.core.handler;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.*;
import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeJson;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeMap;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeRequestPath;
import static java.util.Map.entry;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.common.util.monitor.TracerUtils;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi;
import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.google.common.util.concurrent.UncheckedExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler: translates exceptions into an HTTP status plus a
 * {@link CommonResult} body (ADR 0003, docs/adr/0003-http-status-semantics.md).
 *
 * The HTTP status expresses the generic outcome; the body `code`/`msg` keeps the business
 * sub-reason. The code-to-status derivation lives only in {@link #resolveHttpStatus(Integer)}
 * and must not be scattered elsewhere.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * WWW-Authenticate header value carried by HTTP 401 responses (RFC 7235); shared with the
     * security starter's AuthenticationEntryPoint, which answers 401 outside the SpringMVC flow.
     */
    public static final String WWW_AUTHENTICATE_VALUE = "Bearer realm=\"basic-framework\"";

    /**
     * Retry-After hint (in seconds) carried by HTTP 429 responses. Fixed value: ServiceException
     * does not carry the per-endpoint limiter window; refine when the error body gains details.
     */
    private static final String RETRY_AFTER_SECONDS = "1";

    /**
     * Explicit HTTP status for framework global codes (GlobalErrorCodeConstants), restricted to
     * the ADR 0003 status set: concurrency/duplication codes map to 409, authorization denials to
     * 403, transport contract failures to their standard 4xx status, and server-side capability
     * codes to 500.
     */
    private static final Map<Integer, Integer> FRAMEWORK_CODE_STATUS = Map.ofEntries(
            entry(BAD_REQUEST.getCode(), HttpStatus.BAD_REQUEST.value()),
            entry(UNAUTHORIZED.getCode(), HttpStatus.UNAUTHORIZED.value()),
            entry(FORBIDDEN.getCode(), HttpStatus.FORBIDDEN.value()),
            entry(NOT_FOUND.getCode(), HttpStatus.NOT_FOUND.value()),
            entry(METHOD_NOT_ALLOWED.getCode(), HttpStatus.METHOD_NOT_ALLOWED.value()),
            entry(PAYLOAD_TOO_LARGE.getCode(), HttpStatus.PAYLOAD_TOO_LARGE.value()),
            entry(UNSUPPORTED_MEDIA_TYPE.getCode(), HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()),
            entry(LOCKED.getCode(), HttpStatus.CONFLICT.value()),
            entry(TOO_MANY_REQUESTS.getCode(), HttpStatus.TOO_MANY_REQUESTS.value()),
            entry(INTERNAL_SERVER_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.value()),
            entry(NOT_IMPLEMENTED.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.value()),
            entry(ERROR_CONFIGURATION.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.value()),
            entry(REPEATED_REQUESTS.getCode(), HttpStatus.CONFLICT.value()),
            // system AUTH_MFA_STEP_UP_REQUIRED：已登录但认证强度不足。
            entry(1_002_000_016, HttpStatus.FORBIDDEN.value()),
            entry(UNKNOWN.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.value()));

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    private final String applicationName;

    private final ApiErrorLogCommonApi apiErrorLogApi;

    public GlobalExceptionHandler(String applicationName, ApiErrorLogCommonApi apiErrorLogApi) {
        this.applicationName = applicationName;
        this.apiErrorLogApi = apiErrorLogApi;
        // Fail loud at startup when the error-code name registry cannot be built.
        ErrorCodeNameRegistry.init();
    }

    /**
     * 处理所有异常，主要是提供给 Filter 使用
     * 因为 Filter 不走 SpringMVC 的流程，但是我们又需要兜底处理异常，所以这里提供一个全量的异常处理过程，保持逻辑统一。
     *
     * @param request 请求
     * @param ex 异常
     * @return 通用返回，携带 ADR 0003 映射后的 HTTP 状态码
     */
    public ResponseEntity<CommonResult<?>> allExceptionHandler(HttpServletRequest request, Exception ex) {
        if (ex instanceof MissingServletRequestParameterException) {
            return missingServletRequestParameterExceptionHandler((MissingServletRequestParameterException) ex);
        }
        if (ex instanceof MethodArgumentTypeMismatchException) {
            return methodArgumentTypeMismatchExceptionHandler((MethodArgumentTypeMismatchException) ex);
        }
        if (ex instanceof MethodArgumentNotValidException) {
            return methodArgumentNotValidExceptionExceptionHandler((MethodArgumentNotValidException) ex);
        }
        if (ex instanceof BindException) {
            return bindExceptionHandler((BindException) ex);
        }
        if (ex instanceof ConstraintViolationException) {
            return constraintViolationExceptionHandler((ConstraintViolationException) ex);
        }
        if (ex instanceof ValidationException) {
            return validationException((ValidationException) ex);
        }
        if (ex instanceof MaxUploadSizeExceededException) {
            return maxUploadSizeExceededExceptionHandler((MaxUploadSizeExceededException) ex);
        }
        if (ex instanceof NoHandlerFoundException) {
            return noHandlerFoundExceptionHandler((NoHandlerFoundException) ex);
        }
        if (ex instanceof NoResourceFoundException) {
            return noResourceFoundExceptionHandler((NoResourceFoundException) ex);
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return httpRequestMethodNotSupportedExceptionHandler((HttpRequestMethodNotSupportedException) ex);
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return httpMediaTypeNotSupportedExceptionHandler((HttpMediaTypeNotSupportedException) ex);
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return methodArgumentTypeInvalidFormatExceptionHandler((HttpMessageNotReadableException) ex);
        }
        if (ex instanceof UncheckedExecutionException) {
            return uncheckedExecutionExceptionHandler(request, (UncheckedExecutionException) ex);
        }
        if (ex instanceof ServiceException) {
            return serviceExceptionHandler((ServiceException) ex);
        }
        if (ex instanceof AccessDeniedException) {
            return accessDeniedExceptionHandler(request, (AccessDeniedException) ex);
        }
        return defaultExceptionHandler(request, ex);
    }

    /**
     * Write a ResponseEntity produced by this handler into a raw servlet response. Servlet
     * filters (for example, token authentication and authorization-denial filters) run outside the
     * SpringMVC flow but must share the same status semantics.
     *
     * @param response raw servlet response
     * @param entity entity produced by this handler
     */
    public static void writeResponse(HttpServletResponse response, ResponseEntity<CommonResult<?>> entity) {
        entity.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        ServletUtils.writeJSON(response, entity.getStatusCode().value(), entity.getBody());
    }

    /**
     * Derive the HTTP status for an error code; the single home of the code-to-status mapping
     * (ADR 0003). Framework global codes are mapped explicitly in {@link #FRAMEWORK_CODE_STATUS};
     * business codes follow the constant-name suffix convention (`*_NOT_EXISTS` to 404,
     * `*_EXISTS`/conflict-like names to 409) via {@link ErrorCodeNameRegistry}; anything else
     * defaults to 422.
     *
     * @param code error code carried by a ServiceException
     * @return HTTP status for the response
     */
    static int resolveHttpStatus(Integer code) {
        Integer mapped = FRAMEWORK_CODE_STATUS.get(code);
        if (mapped != null) {
            return mapped;
        }
        String name = ErrorCodeNameRegistry.nameOf(code);
        if (name == null) {
            return HttpStatus.UNPROCESSABLE_ENTITY.value();
        }
        if (name.endsWith("_NOT_EXISTS")) {
            return HttpStatus.NOT_FOUND.value();
        }
        if (name.contains("EXISTS") || name.contains("CONFLICT") || name.contains("DUPLICATE")) {
            return HttpStatus.CONFLICT.value();
        }
        return HttpStatus.UNPROCESSABLE_ENTITY.value();
    }

    private static ResponseEntity<CommonResult<?>> error(HttpStatus status, CommonResult<?> body) {
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 处理 SpringMVC 请求参数缺失
     *
     * 例如说，接口上设置了 @RequestParam("xx") 参数，结果并未传递 xx 参数
     */
    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public ResponseEntity<CommonResult<?>> missingServletRequestParameterExceptionHandler(
            MissingServletRequestParameterException ex) {
        log.debug("[missingServletRequestParameterExceptionHandler][parameterName({})]", ex.getParameterName());
        return error(
                HttpStatus.BAD_REQUEST,
                CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数缺失:%s", ex.getParameterName())));
    }

    /**
     * 处理 SpringMVC 请求参数类型错误
     *
     * 例如说，接口上设置了 @RequestParam("xx") 参数为 Integer，结果传递 xx 参数类型为 String
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CommonResult<?>> methodArgumentTypeMismatchExceptionHandler(
            MethodArgumentTypeMismatchException ex) {
        log.debug("[methodArgumentTypeMismatchExceptionHandler][parameterName({})]", ex.getName());
        return error(
                HttpStatus.BAD_REQUEST,
                CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数类型错误:%s", ex.getName())));
    }

    /**
     * 处理 SpringMVC 参数校验不正确
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResult<?>> methodArgumentNotValidExceptionExceptionHandler(
            MethodArgumentNotValidException ex) {
        // 获取 errorMessage
        String errorMessage = null;
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError == null) {
            // 组合校验时，尝试从全量错误信息中提取提示
            List<ObjectError> allErrors = ex.getBindingResult().getAllErrors();
            if (CollUtil.isNotEmpty(allErrors)) {
                errorMessage = allErrors.get(0).getDefaultMessage();
            }
        } else {
            errorMessage = fieldError.getDefaultMessage();
        }
        // 转换 CommonResult
        if (StrUtil.isEmpty(errorMessage)) {
            return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST));
        }
        return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), errorMessage));
    }

    /**
     * 处理 SpringMVC 参数绑定不正确，本质上也是通过 Validator 校验
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<CommonResult<?>> bindExceptionHandler(BindException ex) {
        FieldError fieldError = ex.getFieldError();
        if (fieldError == null || StrUtil.isEmpty(fieldError.getDefaultMessage())) {
            return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST));
        }
        return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), fieldError.getDefaultMessage()));
    }

    /**
     * 处理 SpringMVC 请求参数类型错误
     *
     * 例如说，接口上设置了 @RequestBody 实体中 xx 属性类型为 Integer，结果传递 xx 参数类型为 String
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @SuppressWarnings("PatternVariableCanBeUsed")
    public ResponseEntity<CommonResult<?>> methodArgumentTypeInvalidFormatExceptionHandler(
            HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException) {
            return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), "请求参数类型错误"));
        }
        if (StrUtil.startWith(ex.getMessage(), "Required request body is missing")) {
            return error(
                    HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), "请求参数类型错误: request body 缺失"));
        }
        return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), "请求体格式错误"));
    }

    /**
     * 处理 Validator 校验不通过产生的异常
     */
    @ExceptionHandler(value = ConstraintViolationException.class)
    public ResponseEntity<CommonResult<?>> constraintViolationExceptionHandler(ConstraintViolationException ex) {
        ConstraintViolation<?> constraintViolation =
                ex.getConstraintViolations().stream().findFirst().orElse(null);
        if (constraintViolation == null || StrUtil.isEmpty(constraintViolation.getMessage())) {
            return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST));
        }
        return error(
                HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST.getCode(), constraintViolation.getMessage()));
    }

    /**
     * 处理 Dubbo Consumer 本地参数校验时，抛出的 ValidationException 异常
     */
    @ExceptionHandler(value = ValidationException.class)
    public ResponseEntity<CommonResult<?>> validationException(ValidationException ex) {
        // 无法拼接明细的错误信息，因为 Dubbo Consumer 抛出 ValidationException 异常时，是直接的字符串信息，且人类不可读
        return error(HttpStatus.BAD_REQUEST, CommonResult.error(BAD_REQUEST));
    }

    /**
     * 处理上传文件过大异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CommonResult<?>> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, CommonResult.error(PAYLOAD_TOO_LARGE));
    }

    /**
     * 处理 SpringMVC 请求地址不存在
     *
     * 注意，它需要设置如下两个配置项：
     * 1. spring.mvc.throw-exception-if-no-handler-found 为 true
     * 2. spring.mvc.static-path-pattern 为 /statics/**
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<CommonResult<?>> noHandlerFoundExceptionHandler(NoHandlerFoundException ex) {
        log.debug("[noHandlerFoundExceptionHandler][method({}) url({})]", ex.getHttpMethod(), ex.getRequestURL());
        return error(
                HttpStatus.NOT_FOUND,
                CommonResult.error(NOT_FOUND.getCode(), String.format("请求地址不存在:%s", ex.getRequestURL())));
    }

    /**
     * 处理 SpringMVC 请求地址不存在
     */
    @ExceptionHandler(NoResourceFoundException.class)
    private ResponseEntity<CommonResult<?>> noResourceFoundExceptionHandler(NoResourceFoundException ex) {
        log.debug("[noResourceFoundExceptionHandler][resourcePath({})]", ex.getResourcePath());
        return error(
                HttpStatus.NOT_FOUND,
                CommonResult.error(NOT_FOUND.getCode(), String.format("请求地址不存在:%s", ex.getResourcePath())));
    }

    /**
     * 处理 SpringMVC 请求方法不正确
     *
     * 例如说，A 接口的方法为 GET 方式，结果请求方法为 POST 方式，导致不匹配
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CommonResult<?>> httpRequestMethodNotSupportedExceptionHandler(
            HttpRequestMethodNotSupportedException ex) {
        log.debug("[httpRequestMethodNotSupportedExceptionHandler][method({})]", ex.getMethod());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supportedMethods = ex.getSupportedHttpMethods();
        if (CollUtil.isNotEmpty(supportedMethods)) {
            builder.allow(supportedMethods.toArray(HttpMethod[]::new));
        }
        return builder.body(
                CommonResult.error(METHOD_NOT_ALLOWED.getCode(), String.format("请求方法不正确:%s", ex.getMethod())));
    }

    /**
     * 处理 SpringMVC 请求的 Content-Type 不正确
     *
     * 例如说，A 接口的 Content-Type 为 application/json，结果请求的 Content-Type 为 application/octet-stream，导致不匹配
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<CommonResult<?>> httpMediaTypeNotSupportedExceptionHandler(
            HttpMediaTypeNotSupportedException ex) {
        log.debug("[httpMediaTypeNotSupportedExceptionHandler][contentType({})]", ex.getContentType());
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, CommonResult.error(UNSUPPORTED_MEDIA_TYPE));
    }

    /**
     * 处理 Spring Security 权限不足的异常
     *
     * 来源是，使用 @PreAuthorize 注解，AOP 进行权限拦截
     */
    @ExceptionHandler(value = AccessDeniedException.class)
    public ResponseEntity<CommonResult<?>> accessDeniedExceptionHandler(
            HttpServletRequest req, AccessDeniedException ex) {
        log.warn(
                "[accessDeniedExceptionHandler][userId({}) 无法访问 url({})]",
                WebFrameworkUtils.getLoginUserId(req),
                sanitizeRequestPath(req));
        return error(HttpStatus.FORBIDDEN, CommonResult.error(FORBIDDEN));
    }

    /**
     * 处理 Guava UncheckedExecutionException
     *
     * 例如说，缓存加载报错时，会被包装成该异常
     */
    @ExceptionHandler(value = UncheckedExecutionException.class)
    public ResponseEntity<CommonResult<?>> uncheckedExecutionExceptionHandler(
            HttpServletRequest req, UncheckedExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof Exception exception) {
            return allExceptionHandler(req, exception);
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return defaultExceptionHandler(req, ex);
    }

    /**
     * 处理业务异常 ServiceException
     *
     * 例如说，商品库存不足，用户手机号已存在。
     */
    @ExceptionHandler(value = ServiceException.class)
    public ResponseEntity<CommonResult<?>> serviceExceptionHandler(ServiceException ex) {
        int status = resolveHttpStatus(ex.getCode());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_VALUE);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return builder.body(CommonResult.error(ex.getCode(), ex.getPublicMessage()));
    }

    /**
     * 处理系统异常，兜底处理所有的一切
     */
    @ExceptionHandler(value = Exception.class)
    public ResponseEntity<CommonResult<?>> defaultExceptionHandler(HttpServletRequest req, Exception ex) {
        // 包装异常中的业务异常仍按业务错误返回
        if (ex.getCause() instanceof ServiceException serviceException) {
            return serviceExceptionHandler(serviceException);
        }
        // 处理数据库唯一索引冲突
        ResponseEntity<CommonResult<?>> sqlResult = handleSqlException(ex);
        if (sqlResult != null) {
            return sqlResult;
        }
        log.error(
                "[defaultExceptionHandler][exceptionName({}) stackTrace({})]",
                ex.getClass().getName(),
                format(ex));
        // 插入异常日志
        createExceptionLog(req, ex);
        // 返回 ERROR CommonResult；对外固定文案，ex.getMessage() 不进入响应
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                CommonResult.error(INTERNAL_SERVER_ERROR.getCode(), INTERNAL_SERVER_ERROR.getMsg()));
    }

    /**
     * 处理数据库唯一索引冲突异常，映射为 HTTP 409（ADR 0003：已知约束名映射到领域冲突）
     */
    private ResponseEntity<CommonResult<?>> handleSqlException(Exception ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.sql.SQLIntegrityConstraintViolationException) {
                String message = cause.getMessage();
                if (message != null && message.contains("uk_code")) {
                    return error(HttpStatus.CONFLICT, CommonResult.error(CONFLICT.getCode(), "编码已存在，请更换后重试"));
                }
                if (message != null && message.contains("uk_name")) {
                    return error(HttpStatus.CONFLICT, CommonResult.error(CONFLICT.getCode(), "名称已存在，请更换后重试"));
                }
                return error(HttpStatus.CONFLICT, CommonResult.error(CONFLICT.getCode(), "数据已存在，请检查后重试"));
            }
            cause = cause.getCause();
        }
        return null;
    }

    private void createExceptionLog(HttpServletRequest req, Exception e) {
        // 插入错误日志
        ApiErrorLogCreateReqDTO errorLog = new ApiErrorLogCreateReqDTO();
        try {
            // 初始化 errorLog
            buildExceptionLog(errorLog, req, e);
            // 执行插入 errorLog
            apiErrorLogApi.createApiErrorLogAsync(errorLog);
        } catch (Exception logException) {
            log.error(
                    "[createExceptionLog][url({}) traceId({}) exceptionName({}) logExceptionName({}) 记录失败]",
                    sanitizeRequestPath(req),
                    errorLog.getTraceId(),
                    errorLog.getExceptionName(),
                    logException.getClass().getName());
        }
    }

    private void buildExceptionLog(ApiErrorLogCreateReqDTO errorLog, HttpServletRequest request, Exception e) {
        // 处理用户信息
        errorLog.setUserId(WebFrameworkUtils.getLoginUserId(request));
        errorLog.setUserType(WebFrameworkUtils.getLoginUserType(request));
        // 设置异常字段
        errorLog.setExceptionName(e.getClass().getName());
        errorLog.setExceptionMessage(e.getClass().getName());
        errorLog.setExceptionRootCauseMessage(getRootCauseClassName(e));
        errorLog.setExceptionStackTrace(format(e));
        StackTraceElement[] stackTraceElements = e.getStackTrace();
        StackTraceElement stackTraceElement = stackTraceElements.length == 0
                ? new StackTraceElement(e.getClass().getName(), "unknown", null, -1)
                : stackTraceElements[0];
        errorLog.setExceptionClassName(stackTraceElement.getClassName());
        errorLog.setExceptionFileName(stackTraceElement.getFileName());
        errorLog.setExceptionMethodName(stackTraceElement.getMethodName());
        errorLog.setExceptionLineNumber(stackTraceElement.getLineNumber());
        // 设置其它字段
        errorLog.setTraceId(TracerUtils.getTraceId());
        errorLog.setApplicationName(applicationName);
        errorLog.setRequestUrl(sanitizeRequestPath(request));
        Map<String, Object> requestParams = MapUtil.<String, Object>builder()
                .put("query", sanitizeMap(ServletUtils.getParamMap(request)))
                .put("body", sanitizeJson(ServletUtils.getBody(request)))
                .build();
        errorLog.setRequestParams(JsonUtils.toJsonString(requestParams));
        errorLog.setRequestMethod(request.getMethod());
        errorLog.setUserAgent(ServletUtils.getUserAgent(request));
        errorLog.setUserIp(ServletUtils.getClientIP(request));
        errorLog.setExceptionTime(LocalDateTime.now());
    }

    private static String getRootCauseClassName(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && visited.add(rootCause)) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getClass().getName();
    }
}
