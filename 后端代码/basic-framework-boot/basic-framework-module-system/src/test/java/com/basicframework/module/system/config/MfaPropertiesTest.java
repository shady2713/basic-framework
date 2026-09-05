package com.basicframework.module.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MfaPropertiesTest {

    @Test
    void webAuthnConfiguration_acceptsExactLocalhostOrigin() {
        MfaProperties properties = properties("localhost", "http://localhost:5666");

        assertThat(properties.isWebAuthnConfigurationValid()).isTrue();
    }

    @Test
    void webAuthnConfiguration_rejectsHttpProductionOriginAndForeignHost() {
        assertThat(properties("example.com", "http://example.com").isWebAuthnConfigurationValid())
                .isFalse();
        assertThat(properties("example.com", "https://attacker.invalid").isWebAuthnConfigurationValid())
                .isFalse();
    }

    @Test
    void webAuthnConfiguration_rejectsOriginWithPath() {
        assertThat(properties("example.com", "https://login.example.com/path").isWebAuthnConfigurationValid())
                .isFalse();
    }

    @Test
    void webAuthnConfiguration_rejectsMalformedRpIdLabels() {
        assertThat(properties("example..com", "https://example.com").isWebAuthnConfigurationValid())
                .isFalse();
        assertThat(properties("-example.com", "https://example.com").isWebAuthnConfigurationValid())
                .isFalse();
        assertThat(properties("intranet", "https://intranet").isWebAuthnConfigurationValid())
                .isFalse();
    }

    @Test
    void mfaTtl_rejectsInvalidOrOverlongWindows() {
        MfaProperties properties = new MfaProperties();
        assertThat(properties.isChallengeTtlValid()).isTrue();
        assertThat(properties.isStepUpTtlValid()).isTrue();

        properties.setChallengeTtl(Duration.ZERO);
        assertThat(properties.isChallengeTtlValid()).isFalse();
        properties.setChallengeTtl(Duration.ofSeconds(-1));
        assertThat(properties.isChallengeTtlValid()).isFalse();
        properties.setChallengeTtl(Duration.ofMinutes(11));
        assertThat(properties.isChallengeTtlValid()).isFalse();
        properties.setChallengeTtl(null);
        assertThat(properties.isChallengeTtlValid()).isFalse();

        properties.setStepUpTtl(Duration.ZERO);
        assertThat(properties.isStepUpTtlValid()).isFalse();
        properties.setStepUpTtl(Duration.ofSeconds(-1));
        assertThat(properties.isStepUpTtlValid()).isFalse();
        properties.setStepUpTtl(Duration.ofMinutes(16));
        assertThat(properties.isStepUpTtlValid()).isFalse();
        properties.setStepUpTtl(null);
        assertThat(properties.isStepUpTtlValid()).isFalse();
    }

    private static MfaProperties properties(String rpId, String origin) {
        MfaProperties properties = new MfaProperties();
        properties.setEnabled(true);
        properties.getWebauthn().setEnabled(true);
        properties.getWebauthn().setRpId(rpId);
        properties.getWebauthn().setAllowedOrigins(Set.of(origin));
        return properties;
    }
}
