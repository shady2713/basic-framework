package com.basicframework.server.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationEnvironmentPostProcessorTest {

    private final ProductionConfigurationEnvironmentPostProcessor processor =
            new ProductionConfigurationEnvironmentPostProcessor();

    @Test
    void production_rejectsDisabledMfa() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.security.mfa.enabled", "false");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("basic-framework.security.mfa.enabled 在生产环境必须启用");
    }

    @Test
    void production_rejectsInsecureRefreshCookie() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.security.refresh-cookie.secure", "false");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("basic-framework.security.refresh-cookie.secure 在生产环境必须启用");
    }

    @Test
    void production_rejectsMissingCredentialEncryptionKey() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.security.credential-encryption-key", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("basic-framework.security.credential-encryption-key 必须通过生产环境配置注入");
    }

    @Test
    void production_rejectsMissingFlywayCredentials() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.flyway.password", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.password 必须通过生产环境配置注入");
    }

    @Test
    void production_rejectsSharedApplicationAndFlywayUser() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.flyway.user", "framework_app");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.user 必须与应用数据源账号分离");
    }

    @Test
    void production_acceptsValidMfaMasterKey() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.security.mfa.enabled", "true");
        environment.setProperty("basic-framework.security.mfa.webauthn.enabled", "true");
        environment.setProperty("basic-framework.security.mfa.webauthn.rp-id", "company.invalid");
        environment.setProperty(
                "basic-framework.security.mfa.webauthn.allowed-origins[0]", "https://admin.company.invalid");

        assertThatCode(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void production_rejectsWebAuthnOriginOutsideRpId() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("basic-framework.security.mfa.enabled", "true");
        environment.setProperty("basic-framework.security.mfa.webauthn.enabled", "true");
        environment.setProperty("basic-framework.security.mfa.webauthn.rp-id", "company.invalid");
        environment.setProperty("basic-framework.security.mfa.webauthn.allowed-origins[0]", "https://attacker.invalid");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-origins 必须是 RP ID 范围内的精确 HTTPS Origin");
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty(
                "spring.datasource.dynamic.datasource.master.url", "jdbc:mysql://db.internal/basic_framework");
        environment.setProperty("spring.datasource.dynamic.datasource.master.username", "framework_app");
        environment.setProperty("spring.datasource.dynamic.datasource.master.password", "strong-db-password");
        environment.setProperty("spring.flyway.url", "jdbc:mysql://db.internal/basic_framework");
        environment.setProperty("spring.flyway.user", "framework_migrator");
        environment.setProperty("spring.flyway.password", "strong-migration-password");
        environment.setProperty("spring.data.redis.host", "redis.internal");
        environment.setProperty("spring.data.redis.password", "strong-redis-password");
        environment.setProperty("basic-framework.web.cors-allowed-origins[0]", "https://admin.company.invalid");
        environment.setProperty("basic-framework.security.refresh-cookie.secure", "true");
        environment.setProperty(
                "basic-framework.security.credential-encryption-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        return environment;
    }
}
