package com.basicframework.framework.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.framework.common.enums.WebFilterOrderEnum;
import com.basicframework.framework.common.util.servlet.ClientIpResolver;
import com.basicframework.framework.web.core.filter.CacheRequestBodyFilter;
import com.basicframework.framework.web.core.handler.GlobalExceptionHandler;
import com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class BasicFrameworkWebAutoConfigurationTest {

    private final BasicFrameworkWebAutoConfiguration configuration = new BasicFrameworkWebAutoConfiguration();

    @Test
    void webMvcRegistrations_buildsOrderedControllerPrefixPredicates() {
        WebProperties properties = new WebProperties();
        properties.setAdminApi(new WebProperties.Api("/management", getClass().getPackageName()));
        properties.setAppApi(new WebProperties.Api("/client", "com.example.client"));

        RequestMappingHandlerMapping mapping =
                configuration.webMvcRegistrations(properties).getRequestMappingHandlerMapping();
        Map<String, Predicate<Class<?>>> pathPrefixes = mapping.getPathPrefixes();

        assertThat(pathPrefixes).containsOnlyKeys("/management", "/client");
        assertThat(pathPrefixes.get("/management").test(AdminController.class)).isTrue();
        assertThat(pathPrefixes.get("/management").test(PlainType.class)).isFalse();
        assertThat(pathPrefixes.get("/client").test(AdminController.class)).isFalse();
    }

    @Test
    void webMvcRegistrations_ignoresMissingApiConfiguration() {
        WebProperties properties = new WebProperties();
        properties.setAdminApi(null);
        properties.setAppApi(new WebProperties.Api("", "com.example.client"));

        RequestMappingHandlerMapping mapping =
                configuration.webMvcRegistrations(properties).getRequestMappingHandlerMapping();

        assertThat(mapping.getPathPrefixes()).isEmpty();
    }

    @Test
    void filterBeans_applyConfiguredLimitsAndFrameworkOrdering() {
        WebProperties properties = new WebProperties();
        properties.setCorsAllowedOrigins(java.util.List.of("https://admin.example.com"));
        properties.setRequestBodyCacheMaxBytes(2048);

        FilterRegistrationBean<CorsFilter> cors = configuration.corsFilterBean(properties);
        FilterRegistrationBean<CacheRequestBodyFilter> bodyCache = configuration.requestBodyCacheFilter(properties);

        assertThat(cors.getOrder()).isEqualTo(WebFilterOrderEnum.CORS_FILTER);
        assertThat(cors.getFilter()).isNotNull();
        assertThat(bodyCache.getOrder()).isEqualTo(WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER);
        assertThat(bodyCache.getFilter()).isNotNull();
    }

    @Test
    void infrastructureBeans_areConstructedFromExplicitDependencies() {
        ApiErrorLogCommonApi errorLogApi = mock(ApiErrorLogCommonApi.class);

        assertThat(configuration.globalExceptionHandler("test-app", errorLogApi))
                .isInstanceOf(GlobalExceptionHandler.class);
        assertThat(configuration.globalResponseBodyHandler()).isNotNull();
        assertThat(configuration.webFrameworkUtils(new WebProperties())).isNotNull();
        assertThat(configuration.restTemplate(new RestTemplateBuilder())).isNotNull();
    }

    @Test
    void clientIpResolver_seedsTrustedProxiesFromProperties() {
        WebProperties properties = new WebProperties();
        properties.setTrustedProxies(java.util.List.of("127.0.0.1"));

        assertThat(configuration.clientIpResolver(properties)).isNotNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @RestController
    private static final class AdminController {}

    private static final class PlainType {}
}
