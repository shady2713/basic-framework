package com.basicframework.framework.mq.redis.core.pubsub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.interceptor.RedisMessageInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.Message;

class AbstractRedisChannelMessageListenerTest {

    @Test
    void onMessage_consumesMessageAndRunsInterceptorsInStackOrder() {
        Fixture fixture = fixture();
        TestChannelMessage sentMessage = new TestChannelMessage();
        sentMessage.setPayload("hello");

        fixture.listener().onMessage(message(JsonUtils.toJsonString(sentMessage)), new byte[0]);

        TestChannelMessage consumedMessage = fixture.listener().consumedMessage();
        assertThat(consumedMessage.getPayload()).isEqualTo("hello");
        assertThat(consumedMessage.getMessageId()).isEqualTo(sentMessage.getMessageId());
        InOrder order = inOrder(fixture.firstInterceptor(), fixture.secondInterceptor());
        order.verify(fixture.firstInterceptor()).consumeMessageBefore(consumedMessage);
        order.verify(fixture.secondInterceptor()).consumeMessageBefore(consumedMessage);
        order.verify(fixture.secondInterceptor()).consumeMessageAfter(consumedMessage);
        order.verify(fixture.firstInterceptor()).consumeMessageAfter(consumedMessage);
    }

    @Test
    void onMessage_consumerFailureStillRunsAfterInterceptorsInReverseOrder() {
        Fixture fixture = fixture();
        IllegalStateException failure = new IllegalStateException("consumer unavailable");
        fixture.listener().setFailure(failure);
        TestChannelMessage sentMessage = new TestChannelMessage();

        assertThatThrownBy(
                        () -> fixture.listener().onMessage(message(JsonUtils.toJsonString(sentMessage)), new byte[0]))
                .isSameAs(failure);

        InOrder order = inOrder(fixture.firstInterceptor(), fixture.secondInterceptor());
        order.verify(fixture.firstInterceptor()).consumeMessageBefore(org.mockito.ArgumentMatchers.any());
        order.verify(fixture.secondInterceptor()).consumeMessageBefore(org.mockito.ArgumentMatchers.any());
        order.verify(fixture.secondInterceptor()).consumeMessageAfter(org.mockito.ArgumentMatchers.any());
        order.verify(fixture.firstInterceptor()).consumeMessageAfter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onMessage_nullJsonPayloadFailsBeforeInvokingConsumersOrInterceptors() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.listener().onMessage(message("null"), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redis Channel 消息内容不能为 null");

        assertThat(fixture.listener().consumedMessage()).isNull();
        verifyNoInteractions(fixture.redisMQTemplate(), fixture.firstInterceptor(), fixture.secondInterceptor());
    }

    @Test
    void onMessage_missingTemplateFailsFastWithClearConfigurationError() {
        TestChannelMessageListener listener = new TestChannelMessageListener();
        TestChannelMessage sentMessage = new TestChannelMessage();

        assertThatThrownBy(() -> listener.onMessage(message(JsonUtils.toJsonString(sentMessage)), new byte[0]))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("redisMQTemplate must be configured");

        assertThat(listener.consumedMessage()).isNull();
    }

    @Test
    void getChannel_returnsTheResolvedChannelName() {
        Fixture fixture = fixture();

        assertThat(fixture.listener().getChannel()).isEqualTo("TestChannelMessage");
    }

    @Test
    void noArgConstructor_whenMessageTypeMissing_failsFastWithClearError() {
        assertThatThrownBy(() -> new RawTypeChannelListener())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("需要设置消息类型");
    }

    @Test
    void noArgConstructor_whenChannelInstantiationFails_propagatesFailure() {
        assertThatThrownBy(() -> new UninstantiableChannelListener()).isInstanceOf(IllegalAccessException.class);
    }

    private static Fixture fixture() {
        RedisMQTemplate redisMQTemplate = mock(RedisMQTemplate.class);
        RedisMessageInterceptor firstInterceptor = mock(RedisMessageInterceptor.class);
        RedisMessageInterceptor secondInterceptor = mock(RedisMessageInterceptor.class);
        when(redisMQTemplate.getInterceptors()).thenReturn(List.of(firstInterceptor, secondInterceptor));
        TestChannelMessageListener listener = new TestChannelMessageListener();
        listener.setRedisMQTemplate(redisMQTemplate);
        return new Fixture(listener, redisMQTemplate, firstInterceptor, secondInterceptor);
    }

    private static Message message(String payload) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private record Fixture(
            TestChannelMessageListener listener,
            RedisMQTemplate redisMQTemplate,
            RedisMessageInterceptor firstInterceptor,
            RedisMessageInterceptor secondInterceptor) {}

    public static final class TestChannelMessage extends AbstractRedisChannelMessage {

        private String payload;

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    private static final class TestChannelMessageListener
            extends AbstractRedisChannelMessageListener<TestChannelMessage> {

        private TestChannelMessage consumedMessage;
        private RuntimeException failure;

        @Override
        public void onMessage(TestChannelMessage message) {
            if (failure != null) {
                throw failure;
            }
            consumedMessage = message;
        }

        private TestChannelMessage consumedMessage() {
            return consumedMessage;
        }

        private void setFailure(RuntimeException failure) {
            this.failure = failure;
        }
    }

    @SuppressWarnings("rawtypes")
    private static final class RawTypeChannelListener extends AbstractRedisChannelMessageListener {

        @Override
        public void onMessage(AbstractRedisChannelMessage message) {}
    }

    public static final class PrivateCtorChannelMessage extends AbstractRedisChannelMessage {

        private PrivateCtorChannelMessage() {}
    }

    private static final class UninstantiableChannelListener
            extends AbstractRedisChannelMessageListener<PrivateCtorChannelMessage> {

        @Override
        public void onMessage(PrivateCtorChannelMessage message) {}
    }
}
