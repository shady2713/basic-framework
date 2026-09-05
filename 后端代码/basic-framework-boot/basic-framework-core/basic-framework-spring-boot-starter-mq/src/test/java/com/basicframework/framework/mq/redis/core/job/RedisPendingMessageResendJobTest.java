package com.basicframework.framework.mq.redis.core.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamDeadLetterService;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamFailureKind;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisPendingMessageResendJobTest {

    private static final String STREAM_KEY = "orders";
    private static final String GROUP = "orders-group";
    private static final String RECOVERY_CONSUMER = "recovery-consumer";
    private static final String SOURCE_CONSUMER = "failed-consumer";

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

    @Test
    void messageResend_recoversEligiblePendingMessageAndReleasesLock() {
        Fixture fixture = fixture();
        RecordId recordId = RecordId.of("1-0");
        PendingMessage pendingMessage = pendingMessage(recordId, Duration.ofMinutes(2), 1L);
        MapRecord<String, String, String> claimedRecord = mock(MapRecord.class);
        ObjectRecord<String, String> recoveredRecord = mock(ObjectRecord.class);
        when(fixture.streamOperations().pending(STREAM_KEY, GROUP)).thenReturn(summary(1L));
        when(fixture.streamOperations().pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), eq(1L)))
                .thenReturn(new PendingMessages(GROUP, List.of(pendingMessage)));
        when(fixture.streamOperations().claim(STREAM_KEY, GROUP, RECOVERY_CONSUMER, Duration.ofMinutes(1), recordId))
                .thenReturn(List.of(claimedRecord));
        when(fixture.streamOperations().map(List.of(claimedRecord), String.class))
                .thenReturn(List.of(recoveredRecord));

        fixture.job().messageResend();

        verify(fixture.listener()).onRecoveredMessage(recoveredRecord, 2L);
        verify(fixture.lock()).unlock();
    }

    @Test
    void messageResend_movesExhaustedPendingMessageToDeadLetter() {
        Fixture fixture = fixture();
        RecordId recordId = RecordId.of("2-0");
        PendingMessage pendingMessage = pendingMessage(recordId, Duration.ofMinutes(2), 5L);
        MapRecord<String, String, String> storedRecord = mock(MapRecord.class);
        ObjectRecord<String, String> deadLetterRecord = mock(ObjectRecord.class);
        when(fixture.streamOperations().pending(STREAM_KEY, GROUP)).thenReturn(summary(1L));
        when(fixture.streamOperations().pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), eq(1L)))
                .thenReturn(new PendingMessages(GROUP, List.of(pendingMessage)));
        when(fixture.streamOperations().range(eq(STREAM_KEY), any(Range.class))).thenReturn(List.of(storedRecord));
        when(fixture.streamOperations().map(storedRecord, String.class)).thenReturn(deadLetterRecord);

        fixture.job().messageResend();

        verify(fixture.deadLetterService())
                .deadLetter(deadLetterRecord, GROUP, 5L, RedisStreamFailureKind.RETRY_EXHAUSTED, null);
        verify(fixture.streamOperations(), never())
                .claim(eq(STREAM_KEY), eq(GROUP), eq(RECOVERY_CONSUMER), any(Duration.class), any(RecordId.class));
        verify(fixture.lock()).unlock();
    }

    @Test
    void messageResend_keepsRecentPendingMessageUnclaimed() {
        Fixture fixture = fixture();
        PendingMessage pendingMessage = pendingMessage(RecordId.of("3-0"), Duration.ofSeconds(30), 1L);
        when(fixture.streamOperations().pending(STREAM_KEY, GROUP)).thenReturn(summary(1L));
        when(fixture.streamOperations().pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), eq(1L)))
                .thenReturn(new PendingMessages(GROUP, List.of(pendingMessage)));

        fixture.job().messageResend();

        verify(fixture.streamOperations(), never())
                .claim(eq(STREAM_KEY), eq(GROUP), eq(RECOVERY_CONSUMER), any(Duration.class), any(RecordId.class));
        verify(fixture.lock()).unlock();
    }

    @Test
    void messageResend_skipsExecutionWhenTheDistributedLockIsUnavailable() {
        Fixture fixture = fixture();
        when(fixture.lock().tryLock()).thenReturn(false);

        fixture.job().messageResend();

        verifyNoInteractions(fixture.redisMQTemplate(), fixture.listener(), fixture.deadLetterService());
        verify(fixture.lock(), never()).unlock();
    }

    @Test
    void messageResend_whenRedisAccessFails_logsAndReleasesLock() {
        RedisMQProperties properties = new RedisMQProperties();
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        when(redisMQTemplate.getRedisTemplate()).thenThrow(new IllegalStateException("redis unavailable"));
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock("redis:stream:pending-message-resend:lock")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        RedisPendingMessageResendJob job = new RedisPendingMessageResendJob(
                List.of(mock(AbstractRedisStreamMessageListener.class)),
                redisMQTemplate,
                redissonClient,
                properties,
                RECOVERY_CONSUMER,
                mock(RedisStreamDeadLetterService.class));

        job.messageResend();

        verify(lock).unlock();
    }

    @Test
    void messageResend_whenPendingSummaryUnavailable_skipsConsumersAndLogs() {
        Fixture fixture = fixture();
        when(fixture.streamOperations().pending(STREAM_KEY, GROUP)).thenReturn(null);

        fixture.job().messageResend();

        verify(fixture.streamOperations(), never())
                .pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), anyLong());
        verify(fixture.lock()).unlock();
    }

    @Test
    void messageResend_whenConsumerPendingUnavailable_logsAndContinues() {
        Fixture fixture = fixture();
        when(fixture.streamOperations().pending(STREAM_KEY, GROUP)).thenReturn(summary(1L));
        when(fixture.streamOperations().pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), eq(1L)))
                .thenReturn(null);

        fixture.job().messageResend();

        verify(fixture.streamOperations(), never())
                .claim(eq(STREAM_KEY), eq(GROUP), eq(RECOVERY_CONSUMER), any(Duration.class), any(RecordId.class));
        verify(fixture.lock()).unlock();
    }

    @Test
    void messageResend_whenClaimReturnsNothing_leavesMessagePending() {
        Fixture fixture = fixture();
        RecordId recordId = RecordId.of("4-0");
        PendingMessage pendingMessage = pendingMessage(recordId, Duration.ofMinutes(2), 1L);
        when(fixture.streamOperations().pending(STREAM_KEY, GROUP)).thenReturn(summary(1L));
        when(fixture.streamOperations().pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), eq(1L)))
                .thenReturn(new PendingMessages(GROUP, List.of(pendingMessage)));
        when(fixture.streamOperations().claim(STREAM_KEY, GROUP, RECOVERY_CONSUMER, Duration.ofMinutes(1), recordId))
                .thenReturn(List.of());

        fixture.job().messageResend();

        verify(fixture.listener(), never()).onRecoveredMessage(any(), anyLong());
        verify(fixture.lock()).unlock();
    }

    @Test
    void messageResend_whenRecoveryConsumptionFails_logsAndKeepsMessagePending() {
        Fixture fixture = fixture();
        RecordId recordId = RecordId.of("5-0");
        PendingMessage pendingMessage = pendingMessage(recordId, Duration.ofMinutes(2), 1L);
        MapRecord<String, String, String> claimedRecord = mock(MapRecord.class);
        ObjectRecord<String, String> recoveredRecord = mock(ObjectRecord.class);
        when(fixture.streamOperations().pending(STREAM_KEY, GROUP)).thenReturn(summary(1L));
        when(fixture.streamOperations().pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), eq(1L)))
                .thenReturn(new PendingMessages(GROUP, List.of(pendingMessage)));
        when(fixture.streamOperations().claim(STREAM_KEY, GROUP, RECOVERY_CONSUMER, Duration.ofMinutes(1), recordId))
                .thenReturn(List.of(claimedRecord));
        when(fixture.streamOperations().map(List.of(claimedRecord), String.class))
                .thenReturn(List.of(recoveredRecord));
        doThrow(new IllegalStateException("consumer unavailable"))
                .when(fixture.listener())
                .onRecoveredMessage(recoveredRecord, 2L);

        fixture.job().messageResend();

        verify(fixture.lock()).unlock();
    }

    @Test
    void messageResend_whenExhaustedMessageBodyVanished_failsTheSingleMessage() {
        Fixture fixture = fixture();
        RecordId recordId = RecordId.of("6-0");
        PendingMessage pendingMessage = pendingMessage(recordId, Duration.ofMinutes(2), 5L);
        when(fixture.streamOperations().pending(STREAM_KEY, GROUP)).thenReturn(summary(1L));
        when(fixture.streamOperations().pending(eq(STREAM_KEY), any(Consumer.class), any(Range.class), eq(1L)))
                .thenReturn(new PendingMessages(GROUP, List.of(pendingMessage)));
        when(fixture.streamOperations().range(eq(STREAM_KEY), any(Range.class))).thenReturn(null);

        fixture.job().messageResend();

        verify(fixture.deadLetterService(), never())
                .deadLetter(any(), eq(GROUP), eq(5L), eq(RedisStreamFailureKind.RETRY_EXHAUSTED), any());
        verify(fixture.lock()).unlock();
    }

    private static RedisPendingMessageResendJob job(RedisMQProperties properties) {
        return new RedisPendingMessageResendJob(Collections.emptyList(), null, null, properties, "test-consumer", null);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        RedisMQProperties properties = new RedisMQProperties();
        properties.setPendingMessageMinIdle(Duration.ofMinutes(1));
        properties.setRetryMaxDelay(Duration.ofMinutes(10));
        properties.setRetryJitterFactor(0D);
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        RedisStreamDeadLetterService deadLetterService = mock(RedisStreamDeadLetterService.class);
        AbstractRedisStreamMessageListener<?> listener = mock(AbstractRedisStreamMessageListener.class);
        when(redisMQTemplate.getRedisTemplate()).thenReturn(redisTemplate);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        when(redissonClient.getLock("redis:stream:pending-message-resend:lock")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(listener.getStreamKey()).thenReturn(STREAM_KEY);
        when(listener.getGroup()).thenReturn(GROUP);
        RedisPendingMessageResendJob job = new RedisPendingMessageResendJob(
                List.of(listener), redisMQTemplate, redissonClient, properties, RECOVERY_CONSUMER, deadLetterService);
        return new Fixture(job, redisMQTemplate, streamOperations, lock, deadLetterService, listener);
    }

    private static PendingMessage pendingMessage(RecordId recordId, Duration elapsed, long deliveryCount) {
        return new PendingMessage(recordId, Consumer.from(GROUP, SOURCE_CONSUMER), elapsed, deliveryCount);
    }

    private static PendingMessagesSummary summary(long pendingCount) {
        return new PendingMessagesSummary(
                GROUP, pendingCount, Range.closed("1-0", "9-0"), Map.of(SOURCE_CONSUMER, pendingCount));
    }

    private record Fixture(
            RedisPendingMessageResendJob job,
            RedisMQTemplate redisMQTemplate,
            StreamOperations<String, String, String> streamOperations,
            RLock lock,
            RedisStreamDeadLetterService deadLetterService,
            AbstractRedisStreamMessageListener<?> listener) {}
}
