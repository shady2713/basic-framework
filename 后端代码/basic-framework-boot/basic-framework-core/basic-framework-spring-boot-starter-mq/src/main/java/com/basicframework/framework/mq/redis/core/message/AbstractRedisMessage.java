package com.basicframework.framework.mq.redis.core.message;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

/**
 * Redis 消息抽象基类
 *
 */
@Data
public abstract class AbstractRedisMessage {

    /** 跨重投保持不变的消息标识，供消费方实现业务幂等。 */
    private String messageId = UUID.randomUUID().toString();

    /**
     * 头
     */
    private Map<String, String> headers = new HashMap<>();

    public String getHeader(String key) {
        return headers.get(key);
    }

    public void addHeader(String key, String value) {
        headers.put(key, value);
    }
}
