package com.basicframework.framework.mq.redis.core.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.interceptor.RedisMessageInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class AbstractRedisStreamMessageListenerTest {

    private static final String GROUP = "test-group";

    @Test
    void configureDefaultGroup_initializesOnlyListenersWithoutAnExplicitGroup() {
        TestStreamMessageListener listener = new TestStreamMessageListener();

        listener.configureDefaultGroup("application-name");
        listener.configureDefaultGroup("another-application");

        assertThat(listener.getGroup()).isEqualTo("application-name");
    }

    @Test
    void getGroup_whenListenerWasNotInitialized_failsLoudly() {
        TestStreamMessageListener listener = new TestStreamMessageListener();

        assertThatThrownBy(listener::getGroup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未初始化");
    }

    @Test
    void configureDefaultGroup_whenApplicationNameIsBlank_failsLoudly() {
        TestStreamMessageListener listener = new TestStreamMessageListener();

        assertThatThrownBy(() -> listener.configureDefaultGroup(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void onMessage_assignsRecordIdAcknowledgesAndRunsInterceptorsInOrder() {
        Fixture fixture = fixture();
        ObjectRecord<String, String> record = record("{\"messageId\":\"\",\"payload\":\"hello\"}", "12-3");

        fixture.listener().onMessage(record);

        assertThat(fixture.listener().consumedMessage().getPayload()).isEqualTo("hello");
        assertThat(fixture.listener().consumedMessage().getMessageId()).isEqualTo("12-3");
        InOrder order = inOrder(fixture.firstInterceptor(), fixture.secondInterceptor(), fixture.streamOperations());
        order.verify(fixture.firstInterceptor())
                .consumeMessageBefore(fixture.listener().consumedMessage());
        order.verify(fixture.secondInterceptor())
                .consumeMessageBefore(fixture.listener().consumedMessage());
        order.verify(fixture.streamOperations()).acknowledge(GROUP, record);
        order.verify(fixture.secondInterceptor())
                .consumeMessageAfter(fixture.listener().consumedMessage());
        order.verify(fixture.firstInterceptor())
                .consumeMessageAfter(fixture.listener().consumedMessage());
    }

    @Test
    void onMessage_nullJsonPayloadMovesTheRecordToNonRetryableDeadLetter() {
        Fixture fixture = fixture();
        ObjectRecord<String, String> record = record("null", "13-0");

        fixture.listener().onMessage(record);

        verify(fixture.deadLetterService())
                .deadLetter(
                        eq(record),
                        eq(GROUP),
                        eq(1L),
                        eq(RedisStreamFailureKind.NON_RETRYABLE),
                        argThat(failure -> failure instanceof IllegalArgumentException
                                && failure.getMessage().contains("不能为 null")));
        verifyNoInteractions(fixture.firstInterceptor(), fixture.secondInterceptor(), fixture.streamOperations());
    }

    @Test
    void onRecoveredMessage_nonRetryableFailureDeadLettersAndStillRunsAfterInterceptors() {
        Fixture fixture = fixture();
        RuntimeException failure = new IllegalArgumentException("invalid business payload");
        fixture.listener().setFailure(failure, false);
        ObjectRecord<String, String> record = record("{\"messageId\":\"event-1\"}", "14-0");

        fixture.listener().onRecoveredMessage(record, 4L);

        verify(fixture.deadLetterService())
                .deadLetter(record, GROUP, 4L, RedisStreamFailureKind.NON_RETRYABLE, failure);
        verify(fixture.firstInterceptor()).consumeMessageBefore(any());
        verify(fixture.secondInterceptor()).consumeMessageBefore(any());
        verify(fixture.secondInterceptor()).consumeMessageAfter(any());
        verify(fixture.firstInterceptor()).consumeMessageAfter(any());
        verify(fixture.streamOperations(), org.mockito.Mockito.never()).acknowledge(any(), any());
    }

    @Test
    void onMessage_retryableFailureIsPropagatedForStreamContainerRetry() {
        Fixture fixture = fixture();
        RuntimeException failure = new IllegalStateException("transient failure");
        fixture.listener().setFailure(failure, true);
        ObjectRecord<String, String> record = record("{\"messageId\":\"event-2\"}", "15-0");

        assertThatThrownBy(() -> fixture.listener().onMessage(record)).isSameAs(failure);

        verifyNoInteractions(fixture.deadLetterService());
        verify(fixture.secondInterceptor()).consumeMessageAfter(any());
        verify(fixture.firstInterceptor()).consumeMessageAfter(any());
        verify(fixture.streamOperations(), org.mockito.Mockito.never()).acknowledge(any(), any());
    }

    @Test
    void explicitGroupConstructor_initializesStreamKeyAndGroup() {
        ExplicitGroupListener listener = new ExplicitGroupListener("orders", "billing-group");

        assertThat(listener.getStreamKey()).isEqualTo("orders");
        assertThat(listener.getGroup()).isEqualTo("billing-group");
    }

    @Test
    void onMessage_defaultRetryabilityPropagatesFailuresForContainerRetry() {
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        RedisStreamDeadLetterService deadLetterService = mock(RedisStreamDeadLetterService.class);
        when(redisMQTemplate.getRedisTemplate()).thenReturn(mock(StringRedisTemplate.class));
        when(redisMQTemplate.getInterceptors()).thenReturn(List.of());
        when(redisMQTemplate.getRedisTemplate().opsForStream()).thenReturn(streamOperations);
        DefaultRetryableListener listener = new DefaultRetryableListener();
        listener.setRedisMQTemplate(redisMQTemplate);
        listener.setDeadLetterService(deadLetterService);
        ReflectionTestUtils.setField(listener, "group", GROUP);
        listener.setFailure(new IllegalStateException("transient"));
        ObjectRecord<String, String> record = record("{\"messageId\":\"event-3\"}", "16-0");

        assertThatThrownBy(() -> listener.onMessage(record)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(deadLetterService);
    }

    @Test
    void noArgConstructor_whenMessageInstantiationFails_propagatesFailure() {
        assertThatThrownBy(() -> new UninstantiableMessageListener()).isInstanceOf(IllegalAccessException.class);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        RedisMessageInterceptor firstInterceptor = mock(RedisMessageInterceptor.class);
        RedisMessageInterceptor secondInterceptor = mock(RedisMessageInterceptor.class);
        RedisStreamDeadLetterService deadLetterService = mock(RedisStreamDeadLetterService.class);
        when(redisMQTemplate.getRedisTemplate()).thenReturn(redisTemplate);
        when(redisMQTemplate.getInterceptors()).thenReturn(List.of(firstInterceptor, secondInterceptor));
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        TestStreamMessageListener listener = new TestStreamMessageListener();
        listener.setRedisMQTemplate(redisMQTemplate);
        listener.setDeadLetterService(deadLetterService);
        ReflectionTestUtils.setField(listener, "group", GROUP);
        return new Fixture(listener, streamOperations, deadLetterService, firstInterceptor, secondInterceptor);
    }

    @SuppressWarnings("unchecked")
    private static ObjectRecord<String, String> record(String payload, String id) {
        ObjectRecord<String, String> record = mock(ObjectRecord.class);
        when(record.getValue()).thenReturn(payload);
        when(record.getId()).thenReturn(RecordId.of(id));
        return record;
    }

    private record Fixture(
            TestStreamMessageListener listener,
            StreamOperations<String, Object, Object> streamOperations,
            RedisStreamDeadLetterService deadLetterService,
            RedisMessageInterceptor firstInterceptor,
            RedisMessageInterceptor secondInterceptor) {}

    public static final class TestStreamMessage extends AbstractRedisStreamMessage {

        private String payload;

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    private static final class TestStreamMessageListener extends AbstractRedisStreamMessageListener<TestStreamMessage> {

        private TestStreamMessage consumedMessage;
        private RuntimeException failure;
        private boolean retryable;

        @Override
        public void onMessage(TestStreamMessage message) {
            if (failure != null) {
                throw failure;
            }
            consumedMessage = message;
        }

        @Override
        protected boolean isRetryable(RuntimeException exception) {
            return retryable;
        }

        private void setFailure(RuntimeException exception, boolean retryable) {
            this.failure = exception;
            this.retryable = retryable;
        }

        private TestStreamMessage consumedMessage() {
            return consumedMessage;
        }
    }

    private static final class ExplicitGroupListener extends AbstractRedisStreamMessageListener<TestStreamMessage> {

        private ExplicitGroupListener(String streamKey, String group) {
            super(streamKey, group);
        }

        @Override
        public void onMessage(TestStreamMessage message) {}
    }

    private static final class DefaultRetryableListener extends AbstractRedisStreamMessageListener<TestStreamMessage> {

        private RuntimeException failure;

        @Override
        public void onMessage(TestStreamMessage message) {
            if (failure != null) {
                throw failure;
            }
        }

        private void setFailure(RuntimeException exception) {
            this.failure = exception;
        }
    }

    @SuppressWarnings("rawtypes")
    private abstract static class RawTypeStreamListener extends AbstractRedisStreamMessageListener {}

    private static final class DoubleRawStreamListener extends RawTypeStreamListener {

        @Override
        public void onMessage(AbstractRedisStreamMessage message) {}
    }

    public static final class PrivateCtorStreamMessage extends AbstractRedisStreamMessage {

        private PrivateCtorStreamMessage() {}
    }

    private static final class UninstantiableMessageListener
            extends AbstractRedisStreamMessageListener<PrivateCtorStreamMessage> {

        @Override
        public void onMessage(PrivateCtorStreamMessage message) {}
    }
}
