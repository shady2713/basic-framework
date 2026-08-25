package com.basicframework.framework.mq.redis.core.stream;

/**
 * Redis Stream 死信创建事件，供监控或告警适配器订阅。
 *
 * @param deadLetterKey 死信 Stream
 * @param deadLetterRecordId 死信记录编号
 * @param messageId 业务消息编号
 * @param deliveryCount 总投递次数
 * @param failureKind 死信原因
 */
public record RedisStreamDeadLetterCreatedEvent(
        String deadLetterKey,
        String deadLetterRecordId,
        String messageId,
        long deliveryCount,
        RedisStreamFailureKind failureKind) {}
