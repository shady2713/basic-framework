package com.basicframework.framework.mq.redis.core.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.mq.redis.core.message.AbstractRedisMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link RedisMessageInterceptor} 单元测试
 *
 */
class RedisMessageInterceptorTest {

    @Test
    void defaultHookMethods_areInvokedThroughInterfaceDispatch() {
        TestRedisMessage message = new TestRedisMessage();
        List<String> events = new ArrayList<>();
        RedisMessageInterceptor interceptor = new RedisMessageInterceptor() {

            @Override
            public void sendMessageAfter(AbstractRedisMessage afterMessage) {
                events.add("after:" + afterMessage.getMessageId());
            }
        };

        interceptor.sendMessageBefore(message);
        interceptor.sendMessageAfter(message);
        interceptor.consumeMessageBefore(message);
        interceptor.consumeMessageAfter(message);

        // 默认实现均为空操作；重写的钩子只记录自己的调用
        assertThat(events).containsExactly("after:" + message.getMessageId());
    }

    @Test
    void defaultHookMethods_doNotThrowWhenChainIsFullyDefault() {
        RedisMessageInterceptor interceptor = new RedisMessageInterceptor() {};
        AbstractRedisMessage message = new TestRedisMessage();

        // 全默认链路可安全执行，不抛异常
        interceptor.sendMessageBefore(message);
        interceptor.sendMessageAfter(message);
        interceptor.consumeMessageBefore(message);
        interceptor.consumeMessageAfter(message);
    }

    /** 仅为实例化抽象消息的测试实现。 */
    static final class TestRedisMessage extends AbstractRedisMessage {}
}
