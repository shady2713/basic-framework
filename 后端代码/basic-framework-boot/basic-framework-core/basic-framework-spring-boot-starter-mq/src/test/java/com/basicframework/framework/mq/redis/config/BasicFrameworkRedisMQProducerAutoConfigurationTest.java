package com.basicframework.framework.mq.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.interceptor.RedisMessageInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class BasicFrameworkRedisMQProducerAutoConfigurationTest {

    @Test
    void redisMQTemplate_keepsTheRedisTemplateAndRegistersInterceptorsInOrder() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisMessageInterceptor firstInterceptor = mock(RedisMessageInterceptor.class);
        RedisMessageInterceptor secondInterceptor = mock(RedisMessageInterceptor.class);
        BasicFrameworkRedisMQProducerAutoConfiguration configuration =
                new BasicFrameworkRedisMQProducerAutoConfiguration();

        RedisMQTemplate redisMQTemplate =
                configuration.redisMQTemplate(redisTemplate, List.of(firstInterceptor, secondInterceptor));

        assertThat(redisMQTemplate.getRedisTemplate()).isSameAs(redisTemplate);
        assertThat(redisMQTemplate.getInterceptors()).containsExactly(firstInterceptor, secondInterceptor);
    }
}
