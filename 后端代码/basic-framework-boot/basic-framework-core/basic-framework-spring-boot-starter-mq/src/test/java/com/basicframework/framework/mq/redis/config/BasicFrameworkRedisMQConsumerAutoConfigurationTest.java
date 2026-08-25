package com.basicframework.framework.mq.redis.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;

class BasicFrameworkRedisMQConsumerAutoConfigurationTest {

    private static final String STREAM_KEY = "test-stream";
    private static final String GROUP = "test-group";

    @Test
    void createConsumerGroup_whenGroupAlreadyExists_ignoresBusyGroupError() {
        RedisSystemException busyGroup = new RedisSystemException(
                "Redis command failed", new IllegalStateException("BUSYGROUP Consumer Group name already exists"));

        assertThatCode(() ->
                        BasicFrameworkRedisMQConsumerAutoConfiguration.createConsumerGroup(STREAM_KEY, GROUP, () -> {
                            throw busyGroup;
                        }))
                .doesNotThrowAnyException();
    }

    @Test
    void createConsumerGroup_whenRedisFails_failsStartupWithContext() {
        RedisSystemException redisFailure =
                new RedisSystemException("Redis connection failed", new IllegalStateException("connection reset"));

        assertThatThrownBy(() ->
                        BasicFrameworkRedisMQConsumerAutoConfiguration.createConsumerGroup(STREAM_KEY, GROUP, () -> {
                            throw redisFailure;
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(STREAM_KEY)
                .hasMessageContaining(GROUP)
                .hasCause(redisFailure);
    }
}
