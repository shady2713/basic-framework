package com.basicframework.framework.mq.redis.core.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AbstractRedisMessage} 单元测试
 *
 */
class AbstractRedisMessageTest {

    private final TestRedisMessage message = new TestRedisMessage();

    @Test
    void messageId_isGeneratedPerInstance() {
        assertThat(message.getMessageId()).isNotBlank();
        assertThat(new TestRedisMessage().getMessageId()).isNotEqualTo(message.getMessageId());
    }

    @Test
    void headers_startEmptyAndRoundTripThroughAddGet() {
        assertThat(message.getHeaders()).isEmpty();
        assertThat(message.getHeader("missing")).isNull();

        message.addHeader("traceId", "trace-1");
        assertThat(message.getHeader("traceId")).isEqualTo("trace-1");
        assertThat(message.getHeaders()).containsExactlyEntriesOf(Map.of("traceId", "trace-1"));
    }

    @Test
    void addHeader_overwritesExistingValue() {
        message.addHeader("traceId", "trace-1");
        message.addHeader("traceId", "trace-2");

        assertThat(message.getHeader("traceId")).isEqualTo("trace-2");
        assertThat(message.getHeaders()).hasSize(1);
    }

    @Test
    void headers_canBeReplacedWholesale() {
        message.addHeader("traceId", "trace-1");
        message.setHeaders(Map.of("channel", "sms"));

        assertThat(message.getHeaders()).containsExactlyEntriesOf(Map.of("channel", "sms"));
        assertThat(message.getHeader("traceId")).isNull();
    }

    /** 仅为实例化抽象基类的测试实现。 */
    static final class TestRedisMessage extends AbstractRedisMessage {}
}
