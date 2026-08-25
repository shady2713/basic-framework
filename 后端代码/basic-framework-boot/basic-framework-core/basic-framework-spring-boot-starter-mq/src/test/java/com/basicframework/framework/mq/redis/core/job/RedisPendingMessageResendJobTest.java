package com.basicframework.framework.mq.redis.core.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class RedisPendingMessageResendJobTest {

    @Test
    void calculateRetryDelay_usesExponentialBackoffAndCap() {
        RedisMQProperties properties = new RedisMQProperties();
        properties.setPendingMessageMinIdle(Duration.ofMinutes(5));
        properties.setRetryMaxDelay(Duration.ofHours(1));
        properties.setRetryJitterFactor(0D);
        RedisPendingMessageResendJob job = job(properties);

        assertThat(job.calculateRetryDelay("1-0", 1L)).isEqualTo(Duration.ofMinutes(5));
        assertThat(job.calculateRetryDelay("1-0", 2L)).isEqualTo(Duration.ofMinutes(10));
        assertThat(job.calculateRetryDelay("1-0", 20L)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void calculateRetryDelay_jitterIsDeterministicAndBounded() {
        RedisMQProperties properties = new RedisMQProperties();
        properties.setPendingMessageMinIdle(Duration.ofMinutes(10));
        properties.setRetryJitterFactor(0.2D);
        RedisPendingMessageResendJob job = job(properties);

        Duration first = job.calculateRetryDelay("42-0", 1L);
        Duration repeated = job.calculateRetryDelay("42-0", 1L);

        assertThat(first).isEqualTo(repeated).isBetween(Duration.ofMinutes(8), Duration.ofMinutes(12));
    }

    private static RedisPendingMessageResendJob job(RedisMQProperties properties) {
        return new RedisPendingMessageResendJob(Collections.emptyList(), null, null, properties, "test-consumer", null);
    }
}
