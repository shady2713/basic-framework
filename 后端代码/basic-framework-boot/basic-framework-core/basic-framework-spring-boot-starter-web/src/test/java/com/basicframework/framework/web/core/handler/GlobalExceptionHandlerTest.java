package com.basicframework.framework.web.core.handler;

import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.LOCKED;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.REPEATED_REQUESTS;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.TOO_MANY_REQUESTS;
import static com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static com.basicframework.framework.web.core.handler.enums.TestStatusErrorCodeConstants.TEST_FOO_EXISTS;
import static com.basicframework.framework.web.core.handler.enums.TestStatusErrorCodeConstants.TEST_FOO_INVALID_STATE;
import static com.basicframework.framework.web.core.handler.enums.TestStatusErrorCodeConstants.TEST_FOO_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi;
import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * HTTP status semantics of {@link GlobalExceptionHandler} (ADR 0003): the HTTP status expresses
 * the generic outcome while the body code keeps the business sub-reason.
 */
class GlobalExceptionHandlerTest {

    private final ApiErrorLogCommonApi apiErrorLogApi = mock(ApiErrorLogCommonApi.class);

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", apiErrorLogApi);

    @Test
    void serviceException_mapsUnauthorizedTo401WithChallengeHeader() {
        ResponseEntity<CommonResult<?>> entity = handler.serviceExceptionHandler(new ServiceException(UNAUTHORIZED));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(entity.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(GlobalExceptionHandler.WWW_AUTHENTICATE_VALUE);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(UNAUTHORIZED.getCode());
    }

    @Test
    void serviceException_mapsNotExistsSuffixTo404() {
        ResponseEntity<CommonResult<?>> entity =
                handler.serviceExceptionHandler(new ServiceException(TEST_FOO_NOT_EXISTS));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(TEST_FOO_NOT_EXISTS.getCode());
    }

    @Test
    void serviceException_mapsExistsSuffixTo409() {
        ResponseEntity<CommonResult<?>> entity = handler.serviceExceptionHandler(new ServiceException(TEST_FOO_EXISTS));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(TEST_FOO_EXISTS.getCode());
    }

    @Test
    void serviceException_mapsFrameworkConflictCodesTo409() {
        assertThat(handler.serviceExceptionHandler(new ServiceException(LOCKED))
                        .getStatusCode()
                        .value())
                .isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(handler.serviceExceptionHandler(new ServiceException(REPEATED_REQUESTS))
                        .getStatusCode()
                        .value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void serviceException_mapsRateLimitTo429WithRetryAfter() {
        ResponseEntity<CommonResult<?>> entity =
                handler.serviceExceptionHandler(new ServiceException(TOO_MANY_REQUESTS));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(entity.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    void serviceException_defaultsBusinessCodeTo422() {
        // Registered constant without a mapped suffix
        assertThat(handler.serviceExceptionHandler(new ServiceException(TEST_FOO_INVALID_STATE))
                        .getStatusCode()
                        .value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        // Code absent from every registry
        assertThat(handler.serviceExceptionHandler(new ServiceException(1_999_999_999, "unknown"))
                        .getStatusCode()
                        .value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void accessDenied_mapsTo403() {
        ResponseEntity<CommonResult<?>> entity =
                handler.accessDeniedExceptionHandler(new MockHttpServletRequest(), new AccessDeniedException("deny"));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(FORBIDDEN.getCode());
    }

    @Test
    void mfaStepUpRequired_mapsTo403AndKeepsBusinessCode() {
        int stepUpRequiredCode = 1_002_000_016;

        ResponseEntity<CommonResult<?>> entity =
                handler.serviceExceptionHandler(new ServiceException(stepUpRequiredCode, "该操作需要重新完成 MFA 二次验证"));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(stepUpRequiredCode);
    }

    @Test
    void defaultException_mapsTo500WithSafeFixedMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Provide the user type up front; otherwise the URL-prefix fallback needs the WebProperties bean.
        WebFrameworkUtils.setLoginUserType(request, 1);
        String leak = "sensitive internal detail";

        ResponseEntity<CommonResult<?>> entity = handler.defaultExceptionHandler(request, new RuntimeException(leak));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(INTERNAL_SERVER_ERROR.getCode());
        assertThat(entity.getBody().getMsg()).isEqualTo(INTERNAL_SERVER_ERROR.getMsg());
        assertThat(entity.getBody().getMsg()).doesNotContain(leak);
        verify(apiErrorLogApi).createApiErrorLogAsync(any(ApiErrorLogCreateReqDTO.class));
    }

    @Test
    void defaultException_sanitizesCredentialFieldsInErrorLog() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/test");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(
                ("""
                        {
                          "newPassword": "${TEST_SECRET}",
                          "apiKey": "${TEST_SECRET}",
                          "accessSecret": "${TEST_SECRET}",
                          "name": "kept"
                        }
                        """)
                        .getBytes(StandardCharsets.UTF_8));
        WebFrameworkUtils.setLoginUserType(request, 1);

        String sensitiveValue = UUID.randomUUID().toString();
        handler.defaultExceptionHandler(
                request, new RuntimeException(sensitiveValue, new IllegalArgumentException(sensitiveValue)));

        ArgumentCaptor<ApiErrorLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(apiErrorLogApi).createApiErrorLogAsync(captor.capture());
        assertThat(captor.getValue().getRequestParams())
                .doesNotContain("${TEST_SECRET}", "newPassword", "apiKey", "accessSecret")
                .contains("name", "kept");
        assertThat(captor.getValue().getExceptionMessage()).doesNotContain(sensitiveValue);
        assertThat(captor.getValue().getExceptionRootCauseMessage()).doesNotContain(sensitiveValue);
        assertThat(captor.getValue().getExceptionStackTrace()).doesNotContain(sensitiveValue);
        assertThat(captor.getValue().getExceptionStackTrace())
                .contains(RuntimeException.class.getName(), IllegalArgumentException.class.getName());
    }

    @Test
    void malformedRequestBody_doesNotEchoRejectedValue() {
        String sensitiveValue = UUID.randomUUID().toString();
        HttpMessageNotReadableException exception = mock(HttpMessageNotReadableException.class);
        InvalidFormatException cause = mock(InvalidFormatException.class);
        when(exception.getCause()).thenReturn(cause);
        when(cause.getValue()).thenReturn(sensitiveValue);

        ResponseEntity<CommonResult<?>> entity = handler.methodArgumentTypeInvalidFormatExceptionHandler(exception);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getMsg()).doesNotContain(sensitiveValue);
    }

    @Test
    void defaultException_mapsSqlUniqueConstraintTo409() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RuntimeException ex = new RuntimeException(
                new SQLIntegrityConstraintViolationException("Duplicate entry 'a' for key 'uk_code'"));

        ResponseEntity<CommonResult<?>> entity = handler.defaultExceptionHandler(request, ex);

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getMsg()).contains("编码已存在");
    }

    @Test
    void missingRequestParameter_mapsTo400() {
        ResponseEntity<CommonResult<?>> entity = handler.missingServletRequestParameterExceptionHandler(
                new MissingServletRequestParameterException("name", "java.lang.String"));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void noHandlerFound_mapsTo404() {
        ResponseEntity<CommonResult<?>> entity =
                handler.noHandlerFoundExceptionHandler(new NoHandlerFoundException("GET", "/lost", new HttpHeaders()));

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void writeResponse_copiesStatusHeadersAndBody() throws Exception {
        ResponseEntity<CommonResult<?>> entity =
                handler.serviceExceptionHandler(new ServiceException(TOO_MANY_REQUESTS));
        MockHttpServletResponse response = new MockHttpServletResponse();

        GlobalExceptionHandler.writeResponse(response, entity);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(response.getContentAsString())
                .contains(TOO_MANY_REQUESTS.getCode().toString());
    }
}
