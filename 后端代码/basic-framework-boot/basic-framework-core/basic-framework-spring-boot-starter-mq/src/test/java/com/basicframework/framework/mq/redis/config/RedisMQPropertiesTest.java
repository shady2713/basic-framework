package com.basicframework.framework.mq.redis.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RedisMQPropertiesTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void reliabilityDurations_defaultsAreValid() {
        RedisMQProperties properties = new RedisMQProperties();

        assertThat(properties.getStreamReadBatchSize()).isEqualTo(10);
        assertThat(properties.isPendingMessageMinIdleValid()).isTrue();
        assertThat(properties.isRetryMaxDelayValid()).isTrue();
        assertThat(properties.isDeadLetterAuditRetentionValid()).isTrue();
        assertThat(properties.getMaxDeliveryAttempts()).isEqualTo(5);
        assertThat(properties.getRetryJitterFactor()).isEqualTo(0.2D);
        assertThat(properties.getRetryMaxDelay()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.getDeadLetterAuditRetention()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void reliabilityDurations_rejectInvalidBoundaries() {
        RedisMQProperties properties = new RedisMQProperties();
        properties.setPendingMessageMinIdle(Duration.ZERO);
        properties.setRetryMaxDelay(Duration.ofHours(2));
        properties.setDeadLetterAuditRetention(Duration.ofDays(29));

        assertThat(properties.isPendingMessageMinIdleValid()).isFalse();
        assertThat(properties.isRetryMaxDelayValid()).isFalse();
        assertThat(properties.isDeadLetterAuditRetentionValid()).isFalse();
    }

    @Test
    void streamReadBatchSize_rejectsValuesOutsideSafeBounds() {
        RedisMQProperties properties = new RedisMQProperties();

        properties.setStreamReadBatchSize(0);
        assertThat(VALIDATOR.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("streamReadBatchSize"));

        properties.setStreamReadBatchSize(1001);
        assertThat(VALIDATOR.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("streamReadBatchSize"));

        properties.setStreamReadBatchSize(1000);
        assertThat(VALIDATOR.validate(properties)).isEmpty();
    }
}
