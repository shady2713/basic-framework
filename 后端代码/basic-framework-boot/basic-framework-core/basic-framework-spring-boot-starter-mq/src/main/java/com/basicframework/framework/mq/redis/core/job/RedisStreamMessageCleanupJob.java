package com.basicframework.framework.mq.redis.core.job;

import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;

import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import java.util.List;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Redis Stream 消息清理任务
 * 用于定期清理已消费的消息，防止内存占用过大
 *
 * @see <a href="https://www.cnblogs.com/nanxiang/p/16179519.html">记一次 redis stream 数据类型内存不释放问题</a>
 *
 */
@Slf4j
@AllArgsConstructor
public class RedisStreamMessageCleanupJob {

    private static final String LOCK_KEY = "redis:stream:message-cleanup:lock";

    private final List<AbstractRedisStreamMessageListener<?>> listeners;
    private final RedisMQTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final RedisMQProperties properties;

    /**
     * 每小时执行一次清理任务
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanup() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        // 尝试加锁
        if (lock.tryLock()) {
            try {
                execute();
            } catch (Exception ex) {
                log.error("[cleanup][执行异常，stackTrace({})]", format(ex));
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 执行清理逻辑
     */
    private void execute() {
        StreamOperations<String, String, String> ops =
                redisTemplate.getRedisTemplate().opsForStream();
        listeners.stream()
                .map(AbstractRedisStreamMessageListener::getStreamKey)
                .distinct()
                .forEach(streamKey -> cleanupStream(ops, streamKey));
    }

    private void cleanupStream(StreamOperations<String, String, String> ops, String streamKey) {
        try {
            Long streamSize = ops.size(streamKey);
            if (streamSize == null || streamSize <= properties.getStreamMaxLength()) {
                return;
            }
            RecordId groupBoundary = findEarliestGroupBoundary(ops, streamKey);
            int candidateCount = (int)
                    Math.min(streamSize - properties.getStreamMaxLength(), properties.getStreamCleanupBatchSize());
            List<MapRecord<String, String, String>> records =
                    ops.range(streamKey, Range.unbounded(), Limit.limit().count(candidateCount)).stream()
                            .filter(record -> groupBoundary == null || compare(record.getId(), groupBoundary) < 0)
                            .toList();
            if (records.isEmpty()) {
                return;
            }
            Long deletedCount =
                    ops.delete(streamKey, records.stream().map(MapRecord::getId).toArray(RecordId[]::new));
            if (deletedCount != null && deletedCount > 0) {
                log.info("[cleanupStream][Stream({}) 清理消息数量({})]", streamKey, deletedCount);
            }
        } catch (RuntimeException exception) {
            log.error("[cleanupStream][Stream({}) 清理异常，stackTrace({})]", streamKey, format(exception));
        }
    }

    private static RecordId findEarliestGroupBoundary(StreamOperations<String, String, String> ops, String streamKey) {
        RecordId earliestBoundary = null;
        StreamInfo.XInfoGroups groups = ops.groups(streamKey);
        for (StreamInfo.XInfoGroup group : groups) {
            RecordId groupBoundary = findGroupBoundary(ops, streamKey, group);
            earliestBoundary = earliestBoundary == null ? groupBoundary : earlier(earliestBoundary, groupBoundary);
        }
        return earliestBoundary;
    }

    private static RecordId findGroupBoundary(
            StreamOperations<String, String, String> ops, String streamKey, StreamInfo.XInfoGroup group) {
        Long pendingCount = Objects.requireNonNull(group.pendingCount(), "Redis 未返回消费者组 pending 数量");
        if (pendingCount > 0) {
            PendingMessagesSummary pending =
                    Objects.requireNonNull(ops.pending(streamKey, group.groupName()), "Redis 未返回消费者组 pending 摘要");
            if (pending.getTotalPendingMessages() == 0) {
                throw new IllegalStateException("Redis 消费者组 pending 数量与摘要不一致");
            }
            return pending.minRecordId();
        }
        return RecordId.of(Objects.requireNonNull(group.lastDeliveredId(), "Redis 未返回消费者组最后投递 ID"));
    }

    private static RecordId earlier(RecordId left, RecordId right) {
        return compare(left, right) <= 0 ? left : right;
    }

    private static int compare(RecordId left, RecordId right) {
        int timestampComparison = left.getTimestamp().compareTo(right.getTimestamp());
        if (timestampComparison != 0) {
            return timestampComparison;
        }
        return left.getSequence().compareTo(right.getSequence());
    }
}
