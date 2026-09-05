package com.basicframework.framework.mq.redis.core.job;

import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;

import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamDeadLetterService;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamFailureKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.scheduling.annotation.Scheduled;

/** 恢复进程异常退出后遗留的 pending 消息；单条失败保留 pending，且不阻断其他消息。 */
@Slf4j
@AllArgsConstructor
public class RedisPendingMessageResendJob {

    private static final String LOCK_KEY = "redis:stream:pending-message-resend:lock";

    private final List<AbstractRedisStreamMessageListener<?>> listeners;
    private final RedisMQTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final RedisMQProperties properties;
    private final String recoveryConsumerName;
    private final RedisStreamDeadLetterService deadLetterService;

    /**
     * 一分钟执行一次,这里选择每分钟的 35 秒执行，是为了避免整点任务过多的问题
     */
    @Scheduled(cron = "35 * * * * ?")
    public void messageResend() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        // 尝试加锁
        if (lock.tryLock()) {
            try {
                execute();
            } catch (Exception ex) {
                log.error("[messageResend][执行异常，stackTrace({})]", format(ex));
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
        listeners.forEach(listener -> processListenerSafely(ops, listener));
    }

    private void processListenerSafely(
            StreamOperations<String, String, String> ops, AbstractRedisStreamMessageListener<?> listener) {
        try {
            PendingMessagesSummary summary =
                    Objects.requireNonNull(ops.pending(listener.getStreamKey(), listener.getGroup()));
            Map<String, Long> pendingMessagesPerConsumer = summary.getPendingMessagesPerConsumer();
            pendingMessagesPerConsumer.forEach(
                    (consumerName, count) -> processConsumerSafely(ops, listener, consumerName, count));
        } catch (Exception exception) {
            log.error(
                    "[processListener][Stream({}) 消费者组({}) pending 恢复异常，stackTrace({})]",
                    listener.getStreamKey(),
                    listener.getGroup(),
                    format(exception));
        }
    }

    private void processConsumerSafely(
            StreamOperations<String, String, String> ops,
            AbstractRedisStreamMessageListener<?> listener,
            String consumerName,
            long pendingMessageCount) {
        try {
            log.info("[processConsumer][消费者({}) 消息数量({})]", consumerName, pendingMessageCount);
            PendingMessages pendingMessages = ops.pending(
                    listener.getStreamKey(),
                    Consumer.from(listener.getGroup(), consumerName),
                    Range.unbounded(),
                    pendingMessageCount);
            pendingMessages.forEach(pendingMessage -> processPendingMessageSafely(ops, listener, pendingMessage));
        } catch (Exception exception) {
            log.error(
                    "[processConsumer][Stream({}) 消费者组({}) 消费者({}) pending 恢复异常，stackTrace({})]",
                    listener.getStreamKey(),
                    listener.getGroup(),
                    consumerName,
                    format(exception));
        }
    }

    private void processPendingMessageSafely(
            StreamOperations<String, String, String> ops,
            AbstractRedisStreamMessageListener<?> listener,
            PendingMessage pendingMessage) {
        RecordId recordId = RecordId.of(pendingMessage.getIdAsString());
        try {
            if (pendingMessage.getTotalDeliveryCount() >= properties.getMaxDeliveryAttempts()) {
                moveExhaustedMessageToDeadLetter(ops, listener, pendingMessage, recordId);
                return;
            }
            Duration retryDelay = calculateRetryDelay(recordId.getValue(), pendingMessage.getTotalDeliveryCount());
            if (pendingMessage.getElapsedTimeSinceLastDelivery().compareTo(retryDelay) < 0) {
                return;
            }
            List<MapRecord<String, String, String>> claimed =
                    ops.claim(listener.getStreamKey(), listener.getGroup(), recoveryConsumerName, retryDelay, recordId);
            if (claimed.isEmpty()) {
                return;
            }
            List<ObjectRecord<String, String>> records = ops.map(claimed, String.class);
            long deliveryCount = pendingMessage.getTotalDeliveryCount() + 1L;
            records.forEach(record -> listener.onRecoveredMessage(record, deliveryCount));
            log.info("[processPendingMessage][消息({})重新处理完成，第({})次投递]", recordId, deliveryCount);
        } catch (Exception exception) {
            log.error(
                    "[processPendingMessage][Stream({}) 消费者组({}) 消息({})恢复失败，保留 pending 等待重试，stackTrace({})]",
                    listener.getStreamKey(),
                    listener.getGroup(),
                    recordId,
                    format(exception));
        }
    }

    private void moveExhaustedMessageToDeadLetter(
            StreamOperations<String, String, String> ops,
            AbstractRedisStreamMessageListener<?> listener,
            PendingMessage pendingMessage,
            RecordId recordId) {
        List<MapRecord<String, String, String>> records =
                ops.range(listener.getStreamKey(), Range.closed(recordId.getValue(), recordId.getValue()));
        if (records == null || records.isEmpty()) {
            throw new IllegalStateException("pending 消息正文不存在: " + recordId);
        }
        ObjectRecord<String, String> record = ops.map(records.get(0), String.class);
        deadLetterService.deadLetter(
                record,
                listener.getGroup(),
                pendingMessage.getTotalDeliveryCount(),
                RedisStreamFailureKind.RETRY_EXHAUSTED,
                null);
    }

    Duration calculateRetryDelay(String recordId, long deliveryCount) {
        long baseMillis = properties.getPendingMessageMinIdle().toMillis();
        long maxMillis = properties.getRetryMaxDelay().toMillis();
        int exponent = (int) Math.min(Math.max(deliveryCount - 1L, 0L), 30L);
        double exponentialMillis = Math.min((double) maxMillis, Math.scalb((double) baseMillis, exponent));
        double unitJitter = (Integer.toUnsignedLong(Objects.hash(recordId, deliveryCount)) % 20001L) / 10000D - 1D;
        long jitteredMillis = Math.round(exponentialMillis * (1D + properties.getRetryJitterFactor() * unitJitter));
        return Duration.ofMillis(Math.max(1L, Math.min(maxMillis, jitteredMillis)));
    }
}
