package com.basicframework.framework.mq.redis.core.stream;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.TypeUtil;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.interceptor.RedisMessageInterceptor;
import com.basicframework.framework.mq.redis.core.message.AbstractRedisMessage;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.stream.StreamListener;

/**
 * Redis Stream 监听器抽象类，用于实现集群消费
 *
 * @param <T> 消息类型。一定要填写噢，不然会报错
 *
 */
public abstract class AbstractRedisStreamMessageListener<T extends AbstractRedisStreamMessage>
        implements StreamListener<String, ObjectRecord<String, String>> {

    /**
     * 消息类型
     */
    private final Class<T> messageType;
    /**
     * Redis Channel
     */
    @Getter
    private final String streamKey;

    /**
     * Redis 消费者分组，默认使用 spring.application.name 名字
     */
    @Value("${spring.application.name}")
    @Getter
    private String group;
    /**
     * RedisMQTemplate
     */
    @Setter
    private RedisMQTemplate redisMQTemplate;

    @Setter
    private RedisStreamDeadLetterService deadLetterService;

    @SneakyThrows
    protected AbstractRedisStreamMessageListener() {
        this.messageType = getMessageClass();
        this.streamKey = messageType.getDeclaredConstructor().newInstance().getStreamKey();
    }

    protected AbstractRedisStreamMessageListener(String streamKey, String group) {
        this.messageType = null;
        this.streamKey = streamKey;
        this.group = group;
    }

    @Override
    public final void onMessage(ObjectRecord<String, String> message) {
        consumeRecord(message, 1L);
    }

    /**
     * 处理 pending 恢复任务重新投递的消息。
     *
     * @param message Redis Stream 记录
     * @param deliveryCount 本次投递后的总次数
     */
    public final void onRecoveredMessage(ObjectRecord<String, String> message, long deliveryCount) {
        consumeRecord(message, deliveryCount);
    }

    private void consumeRecord(ObjectRecord<String, String> message, long deliveryCount) {
        T messageObj;
        try {
            messageObj = JsonUtils.parseObject(message.getValue(), messageType);
        } catch (RuntimeException exception) {
            deadLetter(message, deliveryCount, exception);
            return;
        }
        if (StrUtil.isBlank(messageObj.getMessageId())) {
            messageObj.setMessageId(message.getId().getValue());
        }
        try {
            consumeMessageBefore(messageObj);
            this.onMessage(messageObj);
            requireRedisMQTemplate().getRedisTemplate().opsForStream().acknowledge(group, message);
        } catch (RuntimeException exception) {
            if (isRetryable(exception)) {
                throw exception;
            }
            deadLetter(message, deliveryCount, exception);
        } finally {
            consumeMessageAfter(messageObj);
        }
    }

    /**
     * 处理消息
     *
     * @param message 消息
     */
    public abstract void onMessage(T message);

    /**
     * 判断消费异常是否可重试。业务确定性校验错误应覆盖本方法并返回 false。
     *
     * @param failure 消费异常
     * @return 是否进入有限重试
     */
    protected boolean isRetryable(RuntimeException failure) {
        return true;
    }

    /**
     * 通过解析类上的泛型，获得消息类型
     *
     * @return 消息类型
     */
    @SuppressWarnings("unchecked")
    private Class<T> getMessageClass() {
        Type type = TypeUtil.getTypeArgument(getClass(), 0);
        if (type == null) {
            throw new IllegalStateException(
                    String.format("类型(%s) 需要设置消息类型", getClass().getName()));
        }
        return (Class<T>) type;
    }

    private void consumeMessageBefore(AbstractRedisMessage message) {
        List<RedisMessageInterceptor> interceptors = requireRedisMQTemplate().getInterceptors();
        // 正序
        interceptors.forEach(interceptor -> interceptor.consumeMessageBefore(message));
    }

    private void consumeMessageAfter(AbstractRedisMessage message) {
        List<RedisMessageInterceptor> interceptors = requireRedisMQTemplate().getInterceptors();
        // 倒序
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            interceptors.get(i).consumeMessageAfter(message);
        }
    }

    private void deadLetter(ObjectRecord<String, String> message, long deliveryCount, RuntimeException failure) {
        Objects.requireNonNull(deadLetterService, "deadLetterService must be configured")
                .deadLetter(message, group, deliveryCount, RedisStreamFailureKind.NON_RETRYABLE, failure);
    }

    private RedisMQTemplate requireRedisMQTemplate() {
        return Objects.requireNonNull(redisMQTemplate, "redisMQTemplate must be configured");
    }
}
