package com.basicframework.framework.mq.redis.core.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ClusterSlotHashUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisStreamDeadLetterServiceTest {

    private final RedisStreamDeadLetterService service =
            new RedisStreamDeadLetterService(new StringRedisTemplate(), new RedisMQProperties(), event -> {});

    @Test
    void streamMessage_defaultKeyContainsRedisHashTag() {
        assertThat(new TestStreamMessage().getStreamKey()).isEqualTo("{TestStreamMessage}");
    }

    @Test
    void deadLetterKey_keepsOriginalRedisClusterSlot() {
        String streamKey = "orders:{tenant-1}";
        String deadLetterKey = service.deadLetterKey(streamKey, "billing");

        assertThat(deadLetterKey).isEqualTo("orders:{tenant-1}:dlq:billing");
        assertThat(ClusterSlotHashUtil.calculateSlot(deadLetterKey))
                .isEqualTo(ClusterSlotHashUtil.calculateSlot(streamKey));
        assertThatThrownBy(() -> service.deadLetterKey("orders", "billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash tag");
        assertThatThrownBy(() -> service.deadLetterKey("", "billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.deadLetterKey(streamKey, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消费组");
    }

    @Test
    void disposition_requiresBoundedOperatorAndReason() {
        assertThatThrownBy(() -> service.replay("orders", "billing", "1-0", "", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
        assertThatThrownBy(() -> service.discard("orders", "billing", "1-0", "operator", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> service.discard("orders", "billing", "1-0", "operator", "line1\nline2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("控制字符");
    }

    private static final class TestStreamMessage extends AbstractRedisStreamMessage {}
}
