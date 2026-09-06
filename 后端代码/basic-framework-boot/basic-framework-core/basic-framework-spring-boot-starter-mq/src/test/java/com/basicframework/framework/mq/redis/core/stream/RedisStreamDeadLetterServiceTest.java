package com.basicframework.framework.mq.redis.core.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.ClusterSlotHashUtil;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.hash.HashMapper;

class RedisStreamDeadLetterServiceTest {

    private final RedisStreamDeadLetterService service =
            new RedisStreamDeadLetterService(new StringRedisTemplate(), new RedisMQProperties(), event -> {});

    @Test
    void streamMessage_defaultKeyContainsRedisHashTag() {
        assertThat(new TestStreamMessage().getStreamKey()).isEqualTo("{TestStreamMessage}");
    }

    @Test
    void deadLetterKey_keepsOriginalRedisClusterSlot() {
        String streamKey = "orders:{tenant-1}";
        String deadLetterKey = service.deadLetterKey(streamKey, "billing");

        assertThat(deadLetterKey).isEqualTo("orders:{tenant-1}:dlq:billing");
        assertThat(ClusterSlotHashUtil.calculateSlot(deadLetterKey))
                .isEqualTo(ClusterSlotHashUtil.calculateSlot(streamKey));
        assertThatThrownBy(() -> service.deadLetterKey("orders", "billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash tag");
        assertThatThrownBy(() -> service.deadLetterKey("", "billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.deadLetterKey(streamKey, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消费组");
    }

    @Test
    void disposition_requiresBoundedOperatorAndReason() {
        assertThatThrownBy(() -> service.replay("orders", "billing", "1-0", "", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
        assertThatThrownBy(() -> service.discard("orders", "billing", "1-0", "operator", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> service.discard("orders", "billing", "1-0", "operator", "line1\nline2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("控制字符");
    }

    @Test
    void deadLetter_writesAtomicallyWithoutPersistingFailureMessageAndPublishesAlert() {
        Fixture fixture = fixture();
        ObjectRecord<String, String> record = record("1-0", "{\"messageId\":\"message-1\",\"payload\":\"value\"}");
        IllegalArgumentException failure = new IllegalArgumentException("private failure detail");
        when(fixture.redisTemplate().execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("2-0");

        RecordId result =
                fixture.service().deadLetter(record, "billing", 3L, RedisStreamFailureKind.NON_RETRYABLE, failure);

        assertThat(result).isEqualTo(RecordId.of("2-0"));
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(fixture.redisTemplate())
                .execute(any(DefaultRedisScript.class), keysCaptor.capture(), argumentsCaptor.capture());
        assertThat(keysCaptor.getValue())
                .containsExactly(
                        "orders:{tenant-1}", "orders:{tenant-1}:dlq:billing:index", "orders:{tenant-1}:dlq:billing");
        assertThat(argumentsCaptor.getValue())
                .containsExactly(
                        "1-0",
                        "billing",
                        "message-1",
                        "{\"messageId\":\"message-1\",\"payload\":\"value\"}",
                        "3",
                        "NON_RETRYABLE",
                        IllegalArgumentException.class.getName(),
                        "1704067200000")
                .doesNotContain(failure.getMessage());
        ArgumentCaptor<RedisStreamDeadLetterCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(RedisStreamDeadLetterCreatedEvent.class);
        verify(fixture.eventPublisher()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isEqualTo(new RedisStreamDeadLetterCreatedEvent(
                        "orders:{tenant-1}:dlq:billing", "2-0", "message-1", 3L, RedisStreamFailureKind.NON_RETRYABLE));
    }

    @Test
    void deadLetter_alertFailureDoesNotTurnAPersistedDeadLetterIntoAFailure() {
        Fixture fixture = fixture();
        ObjectRecord<String, String> record = record("1-0", "{\"messageId\":\"message-1\"}");
        when(fixture.redisTemplate().execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("2-0");
        doThrow(new IllegalStateException("alert delivery unavailable"))
                .when(fixture.eventPublisher())
                .publishEvent(any(RedisStreamDeadLetterCreatedEvent.class));

        RecordId result =
                fixture.service().deadLetter(record, "billing", 1L, RedisStreamFailureKind.RETRY_EXHAUSTED, null);

        assertThat(result).isEqualTo(RecordId.of("2-0"));
        verify(fixture.eventPublisher()).publishEvent(any(RedisStreamDeadLetterCreatedEvent.class));
    }

    @Test
    void deadLetter_requiresRedisToReturnTheAtomicWriteResult() {
        Fixture fixture = fixture();
        ObjectRecord<String, String> record = record("1-0", "{\"messageId\":\"message-1\"}");

        assertThatThrownBy(() -> fixture.service()
                        .deadLetter(record, "billing", 1L, RedisStreamFailureKind.RETRY_EXHAUSTED, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis 未返回死信记录编号");

        verifyNoInteractions(fixture.eventPublisher());
    }

    @Test
    void deadLetter_rejectsInvalidReliabilityInputsBeforeCallingRedis() {
        Fixture fixture = fixture();
        ObjectRecord<String, String> record = record("1-0", "{\"messageId\":\"message-1\"}");

        assertThatThrownBy(() ->
                        fixture.service().deadLetter(null, "billing", 1L, RedisStreamFailureKind.RETRY_EXHAUSTED, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("record must not be null");
        assertThatThrownBy(() -> fixture.service()
                        .deadLetter(record, "billing", 0L, RedisStreamFailureKind.RETRY_EXHAUSTED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deliveryCount 必须大于 0");
        assertThatThrownBy(() -> fixture.service().deadLetter(record, "billing", 1L, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("failureKind must not be null");

        verifyNoInteractions(fixture.redisTemplate(), fixture.eventPublisher());
    }

    @Test
    void replay_returnsTheRecordedReplayIdWithoutReadingOrReplayingTheDeadLetterAgain() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "replayRecordId"))
                .thenReturn("9-0");

        RecordId result = fixture.service().replay("orders:{tenant-1}", "billing", "1-0", "admin-1", "retry");

        assertThat(result).isEqualTo(RecordId.of("9-0"));
        verify(fixture.redisTemplate(), never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void replay_republishesTheOriginalPayloadWithAuditMetadata() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        HashMapper<String, String, String> hashMapper = mock(HashMapper.class);
        MapRecord<String, String, String> deadLetterRecord = mock(MapRecord.class);
        String payload = "{\"messageId\":\"message-1\",\"payload\":\"value\"}";
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "replayRecordId"))
                .thenReturn(null);
        when(fixture.redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.range(
                        "orders:{tenant-1}:dlq:billing", org.springframework.data.domain.Range.closed("1-0", "1-0")))
                .thenReturn(List.of(deadLetterRecord));
        when(deadLetterRecord.getValue()).thenReturn(Map.of("originalRecordId", "original-1", "payload", payload));
        when(streamOperations.getHashMapper(String.class)).thenReturn(hashMapper);
        when(hashMapper.toHash(payload)).thenReturn(Map.of("payload", payload));
        when(fixture.redisTemplate().execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("10-0");

        RecordId result = fixture.service().replay("orders:{tenant-1}", "billing", "1-0", "admin-1", "retry");

        assertThat(result).isEqualTo(RecordId.of("10-0"));
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(fixture.redisTemplate())
                .execute(any(DefaultRedisScript.class), keysCaptor.capture(), argumentsCaptor.capture());
        assertThat(keysCaptor.getValue())
                .containsExactly(
                        "orders:{tenant-1}",
                        "orders:{tenant-1}:dlq:billing",
                        "orders:{tenant-1}:dlq:billing:index",
                        "orders:{tenant-1}:dlq:billing:audit:1-0");
        assertThat(argumentsCaptor.getValue())
                .containsExactly(
                        "1-0",
                        "original-1",
                        Long.toString(fixture.properties()
                                .getDeadLetterAuditRetention()
                                .toMillis()),
                        "admin-1",
                        "retry",
                        "1704067200000",
                        "payload",
                        payload);
    }

    @Test
    void replay_whenDeadLetterVanishedConcurrently_returnsTheConcurrentReplayId() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(fixture.redisTemplate().<String, String>opsForStream()).thenReturn(streamOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "replayRecordId"))
                .thenReturn(null, "9-0");
        when(streamOperations.range("orders:{tenant-1}:dlq:billing", Range.closed("1-0", "1-0")))
                .thenReturn(null);

        RecordId result = fixture.service().replay("orders:{tenant-1}", "billing", "1-0", "admin-1", "retry");

        assertThat(result).isEqualTo(RecordId.of("9-0"));
        verify(fixture.redisTemplate(), never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void replay_whenDeadLetterVanishedWithoutConcurrentReplay_rethrows() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(fixture.redisTemplate().<String, String>opsForStream()).thenReturn(streamOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "replayRecordId"))
                .thenReturn(null, null);
        when(streamOperations.range("orders:{tenant-1}:dlq:billing", Range.closed("1-0", "1-0")))
                .thenReturn(null);

        assertThatThrownBy(() -> fixture.service().replay("orders:{tenant-1}", "billing", "1-0", "admin-1", "retry"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("死信记录不存在: 1-0");
    }

    @Test
    void replay_whenRedisReturnsNoReplayId_failsLoudly() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        HashMapper<String, String, String> hashMapper = mock(HashMapper.class);
        @SuppressWarnings("unchecked")
        MapRecord<String, String, String> deadLetterRecord = mock(MapRecord.class);
        String payload = "{\"messageId\":\"message-1\"}";
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "replayRecordId"))
                .thenReturn(null);
        when(fixture.redisTemplate().<String, String>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.range("orders:{tenant-1}:dlq:billing", Range.closed("1-0", "1-0")))
                .thenReturn(List.of(deadLetterRecord));
        when(deadLetterRecord.getValue()).thenReturn(Map.of("originalRecordId", "original-1", "payload", payload));
        when(streamOperations.getHashMapper(String.class)).thenReturn(hashMapper);
        when(hashMapper.toHash(payload)).thenReturn(Map.of());

        assertThatThrownBy(() -> fixture.service().replay("orders:{tenant-1}", "billing", "1-0", "admin-1", "retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis 未返回回放记录编号");
    }

    @Test
    void discard_disposesTheDeadLetterWithAuditMetadata() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        MapRecord<String, String, String> deadLetterRecord = mock(MapRecord.class);
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "disposition"))
                .thenReturn(null);
        when(fixture.redisTemplate().<String, String>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.range("orders:{tenant-1}:dlq:billing", Range.closed("1-0", "1-0")))
                .thenReturn(List.of(deadLetterRecord));
        when(deadLetterRecord.getValue()).thenReturn(Map.of("originalRecordId", "original-1"));
        when(fixture.redisTemplate().execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("DISCARDED");

        fixture.service().discard("orders:{tenant-1}", "billing", "1-0", "admin-1", "cleanup");

        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(fixture.redisTemplate()).execute(any(DefaultRedisScript.class), anyList(), argumentsCaptor.capture());
        assertThat(argumentsCaptor.getValue())
                .containsExactly(
                        "1-0",
                        "original-1",
                        Long.toString(fixture.properties()
                                .getDeadLetterAuditRetention()
                                .toMillis()),
                        "admin-1",
                        "cleanup",
                        "1704067200000");
    }

    @Test
    void discard_whenAlreadyDiscarded_isIdempotent() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "disposition"))
                .thenReturn("DISCARDED");

        fixture.service().discard("orders:{tenant-1}", "billing", "1-0", "admin-1", "cleanup");

        verify(fixture.redisTemplate(), never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void discard_whenDisposedByReplay_failsWithClearError() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "disposition"))
                .thenReturn("REPLAYED");

        assertThatThrownBy(() -> fixture.service().discard("orders:{tenant-1}", "billing", "1-0", "admin-1", "cleanup"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("死信已按其他方式处置: REPLAYED");
    }

    @Test
    void discard_whenDeadLetterVanishedButConcurrentlyDiscarded_isIdempotent() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "disposition"))
                .thenReturn(null, "DISCARDED");
        when(fixture.redisTemplate().<String, String>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.range("orders:{tenant-1}:dlq:billing", Range.closed("1-0", "1-0")))
                .thenReturn(null);

        fixture.service().discard("orders:{tenant-1}", "billing", "1-0", "admin-1", "cleanup");

        verify(fixture.redisTemplate(), never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void discard_whenDeadLetterVanishedWithoutConcurrentDisposition_rethrows() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "disposition"))
                .thenReturn(null, null);
        when(fixture.redisTemplate().<String, String>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.range("orders:{tenant-1}:dlq:billing", Range.closed("1-0", "1-0")))
                .thenReturn(null);

        assertThatThrownBy(() -> fixture.service().discard("orders:{tenant-1}", "billing", "1-0", "admin-1", "cleanup"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("死信记录不存在: 1-0");
    }

    @Test
    void discard_whenRedisReturnsUnexpectedResult_failsLoudly() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        MapRecord<String, String, String> deadLetterRecord = mock(MapRecord.class);
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "disposition"))
                .thenReturn(null);
        when(fixture.redisTemplate().<String, String>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.range("orders:{tenant-1}:dlq:billing", Range.closed("1-0", "1-0")))
                .thenReturn(List.of(deadLetterRecord));
        when(deadLetterRecord.getValue()).thenReturn(Map.of("originalRecordId", "original-1"));
        when(fixture.redisTemplate().execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("REPLAYED");

        assertThatThrownBy(() -> fixture.service().discard("orders:{tenant-1}", "billing", "1-0", "admin-1", "cleanup"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("死信已按其他方式处置: REPLAYED");
    }

    @Test
    void replay_whenDeadLetterLacksOriginalRecordId_failsLoudly() {
        Fixture fixture = fixture();
        HashOperations<String, Object, Object> hashOperations = hashOperations();
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        @SuppressWarnings("unchecked")
        MapRecord<String, String, String> deadLetterRecord = mock(MapRecord.class);
        when(fixture.redisTemplate().opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("orders:{tenant-1}:dlq:billing:audit:1-0", "replayRecordId"))
                .thenReturn(null);
        when(fixture.redisTemplate().<String, String>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.range("orders:{tenant-1}:dlq:billing", Range.closed("1-0", "1-0")))
                .thenReturn(List.of(deadLetterRecord));
        when(deadLetterRecord.getValue()).thenReturn(Map.of("payload", "{}"));

        assertThatThrownBy(() -> fixture.service().replay("orders:{tenant-1}", "billing", "1-0", "admin-1", "retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("死信记录缺少字段: originalRecordId");
    }

    @Test
    void deadLetter_treatsNonTextualMessageIdAsAbsent() {
        Fixture fixture = fixture();
        ObjectRecord<String, String> record = record("1-0", "{\"messageId\":123,\"payload\":\"value\"}");
        when(fixture.redisTemplate().execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("2-0");

        fixture.service().deadLetter(record, "billing", 1L, RedisStreamFailureKind.RETRY_EXHAUSTED, null);

        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(fixture.redisTemplate()).execute(any(DefaultRedisScript.class), anyList(), argumentsCaptor.capture());
        assertThat(argumentsCaptor.getValue()[2]).isEqualTo("");
    }

    @Test
    void deadLetter_toleratesMalformedPayloadForMessageIdExtraction() {
        Fixture fixture = fixture();
        ObjectRecord<String, String> record = record("1-0", "not-json");
        when(fixture.redisTemplate().execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("2-0");

        fixture.service().deadLetter(record, "billing", 1L, RedisStreamFailureKind.RETRY_EXHAUSTED, null);

        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(fixture.redisTemplate()).execute(any(DefaultRedisScript.class), anyList(), argumentsCaptor.capture());
        assertThat(argumentsCaptor.getValue()[2]).isEqualTo("");
    }

    @SuppressWarnings("unchecked")
    private static ObjectRecord<String, String> record(String id, String payload) {
        ObjectRecord<String, String> record = mock(ObjectRecord.class);
        when(record.getRequiredStream()).thenReturn("orders:{tenant-1}");
        when(record.getId()).thenReturn(RecordId.of(id));
        when(record.getValue()).thenReturn(payload);
        return record;
    }

    @SuppressWarnings("unchecked")
    private static HashOperations<String, Object, Object> hashOperations() {
        return mock(HashOperations.class);
    }

    private static Fixture fixture() {
        RedisMQProperties properties = new RedisMQProperties();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        RedisStreamDeadLetterService service = new RedisStreamDeadLetterService(
                redisTemplate,
                properties,
                eventPublisher,
                Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC));
        return new Fixture(service, properties, redisTemplate, eventPublisher);
    }

    private record Fixture(
            RedisStreamDeadLetterService service,
            RedisMQProperties properties,
            StringRedisTemplate redisTemplate,
            ApplicationEventPublisher eventPublisher) {}

    private static final class TestStreamMessage extends AbstractRedisStreamMessage {}
}
