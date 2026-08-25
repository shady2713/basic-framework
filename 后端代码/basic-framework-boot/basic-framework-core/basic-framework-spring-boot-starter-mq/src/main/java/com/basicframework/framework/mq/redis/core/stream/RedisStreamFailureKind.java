package com.basicframework.framework.mq.redis.core.stream;

/** Redis Stream 消息进入死信队列的原因。 */
public enum RedisStreamFailureKind {
    NON_RETRYABLE,
    RETRY_EXHAUSTED
}
