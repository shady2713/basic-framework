package com.basicframework.module.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MfaPropertiesTest {

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
}
