package com.basicframework.framework.mq.redis.core.stream;

import com.basicframework.framework.mq.redis.core.message.AbstractRedisMessage;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Redis Stream Message 抽象类
 *
 */
public abstract class AbstractRedisStreamMessage extends AbstractRedisMessage {

    /**
     * 获得 Redis Stream Key，默认使用带 Redis Cluster hash tag 的类名
     *
     * @return Stream key
     */
    @JsonIgnore // 避免序列化
    public String getStreamKey() {
        return "{" + getClass().getSimpleName() + "}";
    }
}
