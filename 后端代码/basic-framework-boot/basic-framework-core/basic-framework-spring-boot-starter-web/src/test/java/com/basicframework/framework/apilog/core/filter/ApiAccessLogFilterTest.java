package com.basicframework.framework.apilog.core.filter;

import static com.basicframework.framework.apilog.core.interceptor.ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.api.logger.ApiAccessLogCommonApi;
import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class ApiAccessLogFilterTest {

    @Test
    void doFilter_sanitizesDefaultAndEndpointSpecificKeys() throws Exception {
        ApiAccessLogCommonApi apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/test");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(
                ("""
                        {
                          "oldPassword": "${TEST_SECRET}",
                          "API_SECRET": "${TEST_SECRET}",
                          "pin": "${TEST_SECRET}",
                          "name": "kept"
                        }
                        """)
                        .getBytes());
        WebFrameworkUtils.setLoginUserType(request, 1);
        Method endpoint = TestController.class.getDeclaredMethod("endpoint");
        request.setAttribute(ATTRIBUTE_HANDLER_METHOD, new HandlerMethod(new TestController(), endpoint));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi).createApiAccessLogAsync(captor.capture());
        assertThat(captor.getValue().getRequestParams())
                .doesNotContain("${TEST_SECRET}", "oldPassword", "API_SECRET", "pin")
                .contains("name", "kept");
    }

    private static final class TestController {

        @ApiAccessLog(sanitizeKeys = "pin")
        void endpoint() {}
    }
}
