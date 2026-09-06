package com.basicframework.framework.mq.redis.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.mq.redis.core.interceptor.RedisMessageInterceptor;
import com.basicframework.framework.mq.redis.core.pubsub.AbstractRedisChannelMessage;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisMQTemplateTest {

    @Test
    void sendChannel_publishesSerializedMessageAndRunsInterceptorsInStackOrder() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisMQTemplate redisMQTemplate = new RedisMQTemplate(redisTemplate);
        RedisMessageInterceptor firstInterceptor = mock(RedisMessageInterceptor.class);
        RedisMessageInterceptor secondInterceptor = mock(RedisMessageInterceptor.class);
        redisMQTemplate.addInterceptor(firstInterceptor);
        redisMQTemplate.addInterceptor(secondInterceptor);
        TestChannelMessage message = new TestChannelMessage();
        message.setPayload("hello");

        redisMQTemplate.send(message);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(firstInterceptor, secondInterceptor, redisTemplate);
        order.verify(firstInterceptor).sendMessageBefore(message);
        order.verify(secondInterceptor).sendMessageBefore(message);
        order.verify(redisTemplate).convertAndSend(eq(message.getChannel()), payloadCaptor.capture());
        order.verify(secondInterceptor).sendMessageAfter(message);
        order.verify(firstInterceptor).sendMessageAfter(message);
        TestChannelMessage sentMessage = JsonUtils.parseObject(payloadCaptor.getValue(), TestChannelMessage.class);
        assertThat(sentMessage.getPayload()).isEqualTo("hello");
        assertThat(sentMessage.getMessageId()).isEqualTo(message.getMessageId());
    }

    @Test
    void sendStream_addsSerializedRecordAndRunsInterceptorsInStackOrder() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        RecordId expectedRecordId = RecordId.of("1-0");
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(any(Record.class))).thenReturn(expectedRecordId);
        RedisMQTemplate redisMQTemplate = new RedisMQTemplate(redisTemplate);
        RedisMessageInterceptor firstInterceptor = mock(RedisMessageInterceptor.class);
        RedisMessageInterceptor secondInterceptor = mock(RedisMessageInterceptor.class);
        redisMQTemplate.addInterceptor(firstInterceptor);
        redisMQTemplate.addInterceptor(secondInterceptor);
        TestStreamMessage message = new TestStreamMessage();
        message.setPayload("world");

        RecordId actualRecordId = redisMQTemplate.send(message);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Record<String, ?>> recordCaptor = ArgumentCaptor.forClass(Record.class);
        InOrder order = inOrder(firstInterceptor, secondInterceptor, streamOperations);
        order.verify(firstInterceptor).sendMessageBefore(message);
        order.verify(secondInterceptor).sendMessageBefore(message);
        order.verify(streamOperations).add(recordCaptor.capture());
        order.verify(secondInterceptor).sendMessageAfter(message);
        order.verify(firstInterceptor).sendMessageAfter(message);
        assertThat(actualRecordId).isEqualTo(expectedRecordId);
        Record<String, ?> sentRecord = recordCaptor.getValue();
        assertThat(sentRecord.getStream()).isEqualTo(message.getStreamKey());
        TestStreamMessage sentMessage = JsonUtils.parseObject((String) sentRecord.getValue(), TestStreamMessage.class);
        assertThat(sentMessage.getPayload()).isEqualTo("world");
        assertThat(sentMessage.getMessageId()).isEqualTo(message.getMessageId());
    }

    @Test
    void sendChannel_publishFailureStillRunsAfterInterceptorsInReverseOrder() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisMQTemplate redisMQTemplate = new RedisMQTemplate(redisTemplate);
        RedisMessageInterceptor firstInterceptor = mock(RedisMessageInterceptor.class);
        RedisMessageInterceptor secondInterceptor = mock(RedisMessageInterceptor.class);
        redisMQTemplate.addInterceptor(firstInterceptor);
        redisMQTemplate.addInterceptor(secondInterceptor);
        TestChannelMessage message = new TestChannelMessage();
        IllegalStateException failure = new IllegalStateException("Redis unavailable");
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> redisMQTemplate.send(message)).isSameAs(failure);

        InOrder order = inOrder(firstInterceptor, secondInterceptor, redisTemplate);
        order.verify(firstInterceptor).sendMessageBefore(message);
        order.verify(secondInterceptor).sendMessageBefore(message);
        order.verify(redisTemplate).convertAndSend(anyString(), anyString());
        order.verify(secondInterceptor).sendMessageAfter(message);
        order.verify(firstInterceptor).sendMessageAfter(message);
        verify(redisTemplate).convertAndSend(message.getChannel(), JsonUtils.toJsonString(message));
    }

    public static final class TestChannelMessage extends AbstractRedisChannelMessage {

        private String payload;

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    public static final class TestStreamMessage extends AbstractRedisStreamMessage {

        private String payload;

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }
}
