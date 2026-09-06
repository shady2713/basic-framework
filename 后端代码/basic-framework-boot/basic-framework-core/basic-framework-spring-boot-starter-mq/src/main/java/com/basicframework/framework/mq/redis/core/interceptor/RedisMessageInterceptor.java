package com.basicframework.framework.mq.redis.core.interceptor;

import com.basicframework.framework.mq.redis.core.message.AbstractRedisMessage;

/**
 * {@link AbstractRedisMessage} 消息拦截器
 * 通过拦截器，作为插件机制，实现拓展。
 * 例如消息追踪、审计或协议头补充
 *
 */
public interface RedisMessageInterceptor {

    default void sendMessageBefore(AbstractRedisMessage message) {}

    default void sendMessageAfter(AbstractRedisMessage message) {}

    default void consumeMessageBefore(AbstractRedisMessage message) {}

    default void consumeMessageAfter(AbstractRedisMessage message) {}
}
