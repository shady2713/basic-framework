package com.basicframework.framework.mq.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.job.RedisPendingMessageResendJob;
import com.basicframework.framework.mq.redis.core.job.RedisStreamMessageCleanupJob;
import com.basicframework.framework.mq.redis.core.pubsub.AbstractRedisChannelMessageListener;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamDeadLetterService;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

class BasicFrameworkRedisMQConsumerAutoConfigurationTest {

    private static final String STREAM_KEY = "test-stream";
    private static final String GROUP = "test-group";

    @Test
    @SuppressWarnings("unchecked")
    void configureDefaultStreamGroups_initializesEveryListenerOnce() {
        AbstractRedisStreamMessageListener<?> first = mock(AbstractRedisStreamMessageListener.class);
        AbstractRedisStreamMessageListener<?> second = mock(AbstractRedisStreamMessageListener.class);

        BasicFrameworkRedisMQConsumerAutoConfiguration.configureDefaultStreamGroups(
                List.of(first, second), "application-name");

        verify(first).configureDefaultGroup("application-name");
        verify(second).configureDefaultGroup("application-name");
    }

    @Test
    void createConsumerGroup_whenCreateFailsButGroupExists_acceptsDesiredState() {
        RedisSystemException createFailure =
                new RedisSystemException("opaque driver failure", new IllegalStateException("opaque cause"));

        assertThatCode(() -> BasicFrameworkRedisMQConsumerAutoConfiguration.createConsumerGroup(
                        STREAM_KEY,
                        GROUP,
                        () -> {
                            throw createFailure;
                        },
                        () -> true))
                .doesNotThrowAnyException();
    }

    @Test
    void createConsumerGroup_whenRedisFails_failsStartupWithContext() {
        RedisSystemException redisFailure =
                new RedisSystemException("Redis connection failed", new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> BasicFrameworkRedisMQConsumerAutoConfiguration.createConsumerGroup(
                        STREAM_KEY,
                        GROUP,
                        () -> {
                            throw redisFailure;
                        },
                        () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(STREAM_KEY)
                .hasMessageContaining(GROUP)
                .hasCause(redisFailure);
    }

    @Test
    void createConsumerGroup_whenCreateSucceeds_doesNotReadGroupMetadata() {
        Runnable creator = mock(Runnable.class);
        BooleanSupplier verifier = mock(BooleanSupplier.class);

        BasicFrameworkRedisMQConsumerAutoConfiguration.createConsumerGroup(STREAM_KEY, GROUP, creator, verifier);

        verify(creator).run();
        verify(verifier, never()).getAsBoolean();
    }

    @Test
    void createConsumerGroup_whenStateVerificationFails_preservesBothFailures() {
        RedisSystemException createFailure = new RedisSystemException("create failed", null);
        IllegalStateException verificationFailure = new IllegalStateException("verification failed");

        assertThatThrownBy(() -> BasicFrameworkRedisMQConsumerAutoConfiguration.createConsumerGroup(
                        STREAM_KEY,
                        GROUP,
                        () -> {
                            throw createFailure;
                        },
                        () -> {
                            throw verificationFailure;
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(createFailure);
        assertThat(createFailure.getSuppressed()).containsExactly(verificationFailure);
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupExists_readsAuthoritativeRedisMetadata() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> operations = mock(StreamOperations.class);
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup matching = mock(StreamInfo.XInfoGroup.class);
        StreamInfo.XInfoGroup different = mock(StreamInfo.XInfoGroup.class);
        doReturn(operations).when(redisTemplate).opsForStream();
        when(operations.groups(STREAM_KEY)).thenReturn(groups);
        when(matching.groupName()).thenReturn(GROUP);
        when(different.groupName()).thenReturn("different-group");
        when(groups.stream()).thenReturn(Stream.of(different, matching), Stream.of(different));

        assertThat(BasicFrameworkRedisMQConsumerAutoConfiguration.groupExists(redisTemplate, STREAM_KEY, GROUP))
                .isTrue();
        assertThat(BasicFrameworkRedisMQConsumerAutoConfiguration.groupExists(redisTemplate, STREAM_KEY, GROUP))
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupExists_rejectsMissingMetadata() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> operations = mock(StreamOperations.class);
        doReturn(operations).when(redisTemplate).opsForStream();

        assertThatThrownBy(() ->
                        BasicFrameworkRedisMQConsumerAutoConfiguration.groupExists(redisTemplate, STREAM_KEY, GROUP))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Redis 未返回消费者组元数据");
    }

    @Test
    void checkRedisVersion_acceptsSupportedVersions() {
        assertThatCode(() -> BasicFrameworkRedisMQConsumerAutoConfiguration.checkRedisVersion(redisTemplate("5.0.0")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BasicFrameworkRedisMQConsumerAutoConfiguration.checkRedisVersion(redisTemplate("7.4.11")))
                .doesNotThrowAnyException();
    }

    @Test
    void checkRedisVersion_rejectsUnavailableMalformedAndUnsupportedVersionsWithContext() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> unavailableTemplate = mock(RedisTemplate.class);

        assertThatThrownBy(() -> BasicFrameworkRedisMQConsumerAutoConfiguration.checkRedisVersion(unavailableTemplate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis INFO 未返回版本信息");
        assertThatThrownBy(() -> BasicFrameworkRedisMQConsumerAutoConfiguration.checkRedisVersion(redisTemplate(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 redis_version");
        assertThatThrownBy(() -> BasicFrameworkRedisMQConsumerAutoConfiguration.checkRedisVersion(
                        redisTemplate("not-a-version")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解析 Redis 版本")
                .hasCauseInstanceOf(NumberFormatException.class);
        assertThatThrownBy(
                        () -> BasicFrameworkRedisMQConsumerAutoConfiguration.checkRedisVersion(redisTemplate("4.9.9")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("低于最低要求");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisMessageListenerContainer_registersChannelListenersAndWiresTemplate() {
        BasicFrameworkRedisMQConsumerAutoConfiguration configuration =
                new BasicFrameworkRedisMQConsumerAutoConfiguration();
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisMQTemplate.getRedisTemplate()).thenReturn(redisTemplate);
        when(redisTemplate.getRequiredConnectionFactory()).thenReturn(mock(RedisConnectionFactory.class));
        AbstractRedisChannelMessageListener<?> listener = mock(AbstractRedisChannelMessageListener.class);
        when(listener.getChannel()).thenReturn("channel-1");

        RedisMessageListenerContainer container =
                configuration.redisMessageListenerContainer(redisMQTemplate, List.of(listener));

        assertThat(container).isNotNull();
        verify(listener).setRedisMQTemplate(redisMQTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisPendingMessageResendJob_wiresRecoveryDependenciesAndConfiguresDefaultGroups() {
        BasicFrameworkRedisMQConsumerAutoConfiguration configuration =
                new BasicFrameworkRedisMQConsumerAutoConfiguration();
        AbstractRedisStreamMessageListener<?> listener = mock(AbstractRedisStreamMessageListener.class);
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RedisMQProperties properties = new RedisMQProperties();
        RedisStreamDeadLetterService deadLetterService = mock(RedisStreamDeadLetterService.class);

        RedisPendingMessageResendJob job = configuration.redisPendingMessageResendJob(
                List.of(listener), redisMQTemplate, redissonClient, properties, deadLetterService, "app-name");

        assertThat(job).isNotNull();
        verify(listener).configureDefaultGroup("app-name");
    }

    @Test
    void redisStreamDeadLetterService_buildsDeadLetterService() {
        BasicFrameworkRedisMQConsumerAutoConfiguration configuration =
                new BasicFrameworkRedisMQConsumerAutoConfiguration();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisMQProperties properties = new RedisMQProperties();
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        RedisStreamDeadLetterService service =
                configuration.redisStreamDeadLetterService(redisTemplate, properties, eventPublisher);

        assertThat(service).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisStreamMessageCleanupJob_wiresCleanupDependenciesAndConfiguresDefaultGroups() {
        BasicFrameworkRedisMQConsumerAutoConfiguration configuration =
                new BasicFrameworkRedisMQConsumerAutoConfiguration();
        AbstractRedisStreamMessageListener<?> listener = mock(AbstractRedisStreamMessageListener.class);
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RedisMQProperties properties = new RedisMQProperties();

        RedisStreamMessageCleanupJob job = configuration.redisStreamMessageCleanupJob(
                List.of(listener), redisMQTemplate, redissonClient, properties, "app-name");

        assertThat(job).isNotNull();
        verify(listener).configureDefaultGroup("app-name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisStreamMessageListenerContainer_registersStreamListenersAndCreatesConsumerGroups() {
        BasicFrameworkRedisMQConsumerAutoConfiguration configuration =
                new BasicFrameworkRedisMQConsumerAutoConfiguration();
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisMQTemplate.getRedisTemplate()).thenReturn(redisTemplate);
        when(redisTemplate.getRequiredConnectionFactory()).thenReturn(mock(RedisConnectionFactory.class));
        Properties info = new Properties();
        info.setProperty("redis_version", "7.0.0");
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(info);
        StreamOperations<String, Object, Object> operations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(operations);
        AbstractRedisStreamMessageListener<?> listener = mock(AbstractRedisStreamMessageListener.class);
        when(listener.getStreamKey()).thenReturn(STREAM_KEY);
        when(listener.getGroup()).thenReturn(GROUP);
        RedisStreamDeadLetterService deadLetterService = mock(RedisStreamDeadLetterService.class);
        RedisMQProperties properties = new RedisMQProperties();

        StreamMessageListenerContainer<String, ObjectRecord<String, String>> container =
                configuration.redisStreamMessageListenerContainer(
                        redisMQTemplate, List.of(listener), deadLetterService, properties, "app-name");

        assertThat(container).isNotNull();
        verify(listener).configureDefaultGroup("app-name");
        verify(listener).setRedisMQTemplate(redisMQTemplate);
        verify(listener).setDeadLetterService(deadLetterService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisStreamMessageListenerContainer_whenGroupAlreadyExists_keepsRegisteringListener() {
        BasicFrameworkRedisMQConsumerAutoConfiguration configuration =
                new BasicFrameworkRedisMQConsumerAutoConfiguration();
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisMQTemplate.getRedisTemplate()).thenReturn(redisTemplate);
        when(redisTemplate.getRequiredConnectionFactory()).thenReturn(mock(RedisConnectionFactory.class));
        Properties info = new Properties();
        info.setProperty("redis_version", "7.0.0");
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(info);
        StreamOperations<String, Object, Object> operations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(operations);
        doThrow(new RedisSystemException("create group failed", new IllegalStateException("duplicate")))
                .when(operations)
                .createGroup(STREAM_KEY, GROUP);
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup existingGroup = mock(StreamInfo.XInfoGroup.class);
        when(existingGroup.groupName()).thenReturn(GROUP);
        when(groups.stream()).thenReturn(Stream.of(existingGroup));
        when(operations.groups(STREAM_KEY)).thenReturn(groups);
        AbstractRedisStreamMessageListener<?> listener = mock(AbstractRedisStreamMessageListener.class);
        when(listener.getStreamKey()).thenReturn(STREAM_KEY);
        when(listener.getGroup()).thenReturn(GROUP);
        RedisStreamDeadLetterService deadLetterService = mock(RedisStreamDeadLetterService.class);
        RedisMQProperties properties = new RedisMQProperties();

        StreamMessageListenerContainer<String, ObjectRecord<String, String>> container =
                configuration.redisStreamMessageListenerContainer(
                        redisMQTemplate, List.of(listener), deadLetterService, properties, "app-name");

        assertThat(container).isNotNull();
        verify(listener).setRedisMQTemplate(redisMQTemplate);
    }

    @Test
    void buildConsumerName_combinesHostAddressAndProcessId() {
        String consumerName = BasicFrameworkRedisMQConsumerAutoConfiguration.buildConsumerName();

        assertThat(consumerName).matches(".+@\\d+");
    }

    @SuppressWarnings("unchecked")
    private static RedisTemplate<String, Object> redisTemplate(String version) {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        Properties info = new Properties();
        if (version != null) {
            info.setProperty("redis_version", version);
        }
        doReturn(info).when(redisTemplate).execute(any(RedisCallback.class));
        return redisTemplate;
    }
}
