package com.basicframework.framework.mq.redis.core.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisStreamMessageCleanupJobTest {

    private static final String STREAM_KEY = "orders";

    @Test
    void cleanup_deletesOnlyRecordsBeforeTheEarliestConsumerGroupBoundary() {
        Fixture fixture = fixture();
        fixture.properties().setStreamMaxLength(10);
        fixture.properties().setStreamCleanupBatchSize(3);
        RecordId firstId = RecordId.of("1-0");
        RecordId boundaryId = RecordId.of("2-0");
        MapRecord<String, String, String> first = record(firstId);
        MapRecord<String, String, String> boundary = record(boundaryId);
        MapRecord<String, String, String> afterBoundary = record(RecordId.of("3-0"));
        StreamInfo.XInfoGroup group = group(0L, boundaryId.getValue());
        StreamInfo.XInfoGroups groups = groups(group);
        when(fixture.streamOperations().size(STREAM_KEY)).thenReturn(15L);
        when(fixture.streamOperations().groups(STREAM_KEY)).thenReturn(groups);
        when(fixture.streamOperations().range(eq(STREAM_KEY), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(first, boundary, afterBoundary));
        when(fixture.streamOperations().delete(STREAM_KEY, firstId)).thenReturn(1L);

        fixture.job().cleanup();

        verify(fixture.streamOperations()).delete(STREAM_KEY, firstId);
        verify(fixture.lock()).unlock();
    }

    @Test
    void cleanup_preservesTheEarliestPendingRecordAcrossConsumerGroups() {
        Fixture fixture = fixture();
        fixture.properties().setStreamMaxLength(10);
        RecordId firstId = RecordId.of("1-0");
        RecordId pendingBoundaryId = RecordId.of("2-0");
        MapRecord<String, String, String> first = record(firstId);
        MapRecord<String, String, String> pendingBoundary = record(pendingBoundaryId);
        StreamInfo.XInfoGroup group = group(2L, RecordId.of("9-0").getValue());
        StreamInfo.XInfoGroups groups = groups(group);
        String groupName = group.groupName();
        org.springframework.data.redis.connection.stream.PendingMessagesSummary pendingSummary =
                new org.springframework.data.redis.connection.stream.PendingMessagesSummary(
                        groupName,
                        2L,
                        Range.closed(pendingBoundaryId.getValue(), pendingBoundaryId.getValue()),
                        java.util.Map.of("consumer", 2L));
        when(fixture.streamOperations().size(STREAM_KEY)).thenReturn(12L);
        when(fixture.streamOperations().groups(STREAM_KEY)).thenReturn(groups);
        when(fixture.streamOperations().pending(STREAM_KEY, groupName)).thenReturn(pendingSummary);
        when(fixture.streamOperations().range(eq(STREAM_KEY), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(first, pendingBoundary));
        when(fixture.streamOperations().delete(STREAM_KEY, firstId)).thenReturn(1L);

        fixture.job().cleanup();

        verify(fixture.streamOperations()).delete(STREAM_KEY, firstId);
        verify(fixture.streamOperations(), never()).delete(STREAM_KEY, pendingBoundaryId);
        verify(fixture.lock()).unlock();
    }

    @Test
    void cleanup_skipsStreamsThatDoNotExceedTheConfiguredMaximum() {
        Fixture fixture = fixture();
        fixture.properties().setStreamMaxLength(10);
        when(fixture.streamOperations().size(STREAM_KEY)).thenReturn(10L);

        fixture.job().cleanup();

        verify(fixture.streamOperations(), never()).groups(STREAM_KEY);
        verify(fixture.streamOperations(), never()).delete(eq(STREAM_KEY), any(RecordId[].class));
        verify(fixture.lock()).unlock();
    }

    @Test
    void cleanup_skipsExecutionWhenTheDistributedLockIsUnavailable() {
        Fixture fixture = fixture();
        when(fixture.lock().tryLock()).thenReturn(false);

        fixture.job().cleanup();

        verifyNoInteractions(fixture.redisMQTemplate(), fixture.listener());
        verify(fixture.lock(), never()).unlock();
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        RedisMQProperties properties = new RedisMQProperties();
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        AbstractRedisStreamMessageListener<?> listener = mock(AbstractRedisStreamMessageListener.class);
        when(redisMQTemplate.getRedisTemplate()).thenReturn(redisTemplate);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        when(redissonClient.getLock("redis:stream:message-cleanup:lock")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(listener.getStreamKey()).thenReturn(STREAM_KEY);
        RedisStreamMessageCleanupJob job =
                new RedisStreamMessageCleanupJob(List.of(listener), redisMQTemplate, redissonClient, properties);
        return new Fixture(job, properties, redisMQTemplate, streamOperations, lock, listener);
    }

    @SuppressWarnings("unchecked")
    private static MapRecord<String, String, String> record(RecordId recordId) {
        MapRecord<String, String, String> record = mock(MapRecord.class);
        when(record.getId()).thenReturn(recordId);
        return record;
    }

    private static StreamInfo.XInfoGroups groups(StreamInfo.XInfoGroup... groups) {
        StreamInfo.XInfoGroups result = mock(StreamInfo.XInfoGroups.class);
        when(result.iterator()).thenReturn(List.of(groups).iterator());
        return result;
    }

    private static StreamInfo.XInfoGroup group(long pendingCount, String lastDeliveredId) {
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);
        when(group.groupName()).thenReturn("orders-group");
        when(group.pendingCount()).thenReturn(pendingCount);
        when(group.lastDeliveredId()).thenReturn(lastDeliveredId);
        return group;
    }

    private record Fixture(
            RedisStreamMessageCleanupJob job,
            RedisMQProperties properties,
            RedisMQTemplate redisMQTemplate,
            StreamOperations<String, String, String> streamOperations,
            RLock lock,
            AbstractRedisStreamMessageListener<?> listener) {}
}
