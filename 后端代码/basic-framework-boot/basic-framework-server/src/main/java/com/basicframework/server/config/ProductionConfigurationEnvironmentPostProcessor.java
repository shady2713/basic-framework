package com.basicframework.server.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * 生产环境关键配置校验。
 */
public class ProductionConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROD_PROFILE = "prod";
    private static final String DEFAULT_CORS_ORIGIN = "https://admin.example.com";
    private static final Set<String> UNSAFE_DEFAULT_VALUES = Set.of(
            "admin",
            "admin123",
            "change_me",
            "changeme",
            "default",
            "password",
            "please_change_me",
            "root",
            "test",
            "123456");

    private static final List<String> REQUIRED_PROD_PROPERTIES = List.of(
            "spring.datasource.dynamic.datasource.master.url",
            "spring.datasource.dynamic.datasource.master.username",
            "spring.datasource.dynamic.datasource.master.password",
            "spring.flyway.url",
            "spring.flyway.user",
            "spring.flyway.password",
            "spring.data.redis.host",
            "spring.data.redis.password");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!isProdProfile(environment)) {
            return;
        }
        List<String> errors = new ArrayList<>();
        validateRequiredProperties(environment, errors);
        validateSeparateDatabasePrincipals(environment, errors);
        validateCorsOrigins(environment, errors);
        validateRefreshCookie(environment, errors);
        validateCredentialEncryptionKey(environment, errors);
        validateMfa(environment, errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("生产环境配置不安全或不完整：" + String.join("；", errors));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static boolean isProdProfile(ConfigurableEnvironment environment) {
        return Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
    }

    private static void validateRequiredProperties(ConfigurableEnvironment environment, List<String> errors) {
        for (String property : REQUIRED_PROD_PROPERTIES) {
            String value = environment.getProperty(property);
            if (isUnsafeValue(value) || isUnsafeDefaultValue(value)) {
                errors.add(property + " 必须通过生产环境配置注入，不能使用空值、占位符或弱默认值");
            }
        }
    }

    private static void validateSeparateDatabasePrincipals(ConfigurableEnvironment environment, List<String> errors) {
        String applicationUser = environment.getProperty("spring.datasource.dynamic.datasource.master.username");
        String migrationUser = environment.getProperty("spring.flyway.user");
        if (StringUtils.hasText(applicationUser)
                && StringUtils.hasText(migrationUser)
                && applicationUser.equalsIgnoreCase(migrationUser)) {
            errors.add("spring.flyway.user 必须与应用数据源账号分离，避免运行时持有 DDL 权限");
        }
    }

    private static void validateCorsOrigins(ConfigurableEnvironment environment, List<String> errors) {
        String firstOrigin = environment.getProperty("basic-framework.web.cors-allowed-origins[0]");
        if (isUnsafeValue(firstOrigin)) {
            errors.add("basic-framework.web.cors-allowed-origins 必须配置为生产域名");
            return;
        }
        if ("*".equals(firstOrigin)
                || DEFAULT_CORS_ORIGIN.equals(firstOrigin)
                || firstOrigin.contains("localhost")
                || firstOrigin.contains("127.0.0.1")) {
            errors.add("basic-framework.web.cors-allowed-origins 不能使用通配符、示例域名或本机地址");
        }
    }

    private static void validateRefreshCookie(ConfigurableEnvironment environment, List<String> errors) {
        boolean secure =
                environment.getProperty("basic-framework.security.refresh-cookie.secure", Boolean.class, false);
        if (!secure) {
            errors.add("basic-framework.security.refresh-cookie.secure 在生产环境必须启用");
        }
    }

    private static void validateMfa(ConfigurableEnvironment environment, List<String> errors) {
        boolean enabled = environment.getProperty("basic-framework.security.mfa.enabled", Boolean.class, false);
        if (!enabled) {
            errors.add("basic-framework.security.mfa.enabled 在生产环境必须启用");
            return;
        }
        validateWebAuthn(environment, errors);
    }

    private static void validateCredentialEncryptionKey(ConfigurableEnvironment environment, List<String> errors) {
        String property = "basic-framework.security.credential-encryption-key";
        String encodedKey = environment.getProperty(property);
        if (isUnsafeValue(encodedKey) || isUnsafeDefaultValue(encodedKey)) {
            errors.add(property + " 必须通过生产环境配置注入");
            return;
        }
        try {
            if (Base64.getDecoder().decode(encodedKey).length != 32) {
                errors.add(property + " 必须是 32 字节 Base64 值");
            }
        } catch (IllegalArgumentException exception) {
            errors.add(property + " 必须是合法 Base64 值");
        }
    }

    private static void validateWebAuthn(ConfigurableEnvironment environment, List<String> errors) {
        String prefix = "basic-framework.security.mfa.webauthn.";
        if (!environment.getProperty(prefix + "enabled", Boolean.class, false)) {
            errors.add(prefix + "enabled 在生产环境必须启用");
            return;
        }
        String rpId = environment.getProperty(prefix + "rp-id");
        String origin = environment.getProperty(prefix + "allowed-origins[0]");
        if (isUnsafeValue(rpId) || rpId.contains("example") || rpId.contains("localhost")) {
            errors.add(prefix + "rp-id 必须配置为生产域名");
            return;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !(host.equalsIgnoreCase(rpId)
                            || host.toLowerCase(Locale.ROOT).endsWith("." + rpId.toLowerCase(Locale.ROOT)))
                    || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                errors.add(prefix + "allowed-origins 必须是 RP ID 范围内的精确 HTTPS Origin");
            }
        } catch (RuntimeException invalidOrigin) {
            errors.add(prefix + "allowed-origins 必须是合法 HTTPS Origin");
        }
    }

    private static boolean isUnsafeValue(String value) {
        return !StringUtils.hasText(value) || value.contains("${");
    }

    private static boolean isUnsafeDefaultValue(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        return UNSAFE_DEFAULT_VALUES.contains(normalizedValue);
    }
}
