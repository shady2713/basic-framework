package com.basicframework.framework.mq.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.mq.redis.config.BasicFrameworkRedisMQProducerAutoConfiguration;
import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.job.RedisPendingMessageResendJob;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessage;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamDeadLetterCreatedEvent;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamDeadLetterService;
import com.basicframework.framework.redis.config.BasicFrameworkRedisAutoConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 Redis 验证有限重试、原子 DLQ 以及人工幂等处置。 */
@Testcontainers
@SpringBootTest(
        classes = RedisMQDeadLetterIT.TestApplication.class,
        properties = {"spring.application.name=redis-mq-dead-letter", "spring.main.web-application-type=none"})
class RedisMQDeadLetterIT {

    private static final String REDIS_PASSWORD = "integration-only";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @Autowired
    private RedisMQTemplate redisMQTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private RedisMQProperties properties;
    private RedisStreamDeadLetterService deadLetterService;
    private List<Object> publishedEvents;

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @BeforeEach
    void setUp() {
        properties = new RedisMQProperties();
        properties.setPendingMessageMinIdle(Duration.ofMillis(1));
        properties.setRetryJitterFactor(0D);
        publishedEvents = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher eventPublisher = publishedEvents::add;
        deadLetterService = new RedisStreamDeadLetterService(redisTemplate, properties, eventPublisher);
        redisTemplate.delete(sourceStreamKey());
    }

    @AfterEach
    void clearSourceStream() {
        redisTemplate.delete(sourceStreamKey());
    }

    @Test
    void retryableFailure_afterFiveDeliveries_movesToDeadLetterAndAcknowledgesOriginal() {
        String group = uniqueGroup("retry");
        RetryDeadLetterListener listener = listener(group, true);
        PendingFixture fixture = createPending(group, "retryable");
        RedisPendingMessageResendJob job = recoveryJob(listener);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            job.messageResend();
            assertThat(pendingCount(group)).isZero();
        });

        MapRecord<String, String, String> deadLetter = singleDeadLetter(group);
        assertThat(deadLetter.getValue())
                .containsEntry("originalRecordId", fixture.recordId().getValue())
                .containsEntry("messageId", fixture.message().getMessageId())
                .containsEntry("deliveryCount", "5")
                .containsEntry("failureKind", "RETRY_EXHAUSTED");
        assertThat(listener.attempts()).isEqualTo(4);
        assertDeadLetterEvent(deadLetter, 5L);
        cleanupGroup(group);
    }

    @Test
    void nonRetryableFailure_onFirstDelivery_movesDirectlyToDeadLetter() {
        String group = uniqueGroup("non-retryable");
        RetryDeadLetterListener listener = listener(group, false);
        PendingFixture fixture = createPending(group, "non-retryable");

        listener.onMessage(fixture.record());

        assertThat(pendingCount(group)).isZero();
        assertThat(singleDeadLetter(group).getValue())
                .containsEntry("deliveryCount", "1")
                .containsEntry("failureKind", "NON_RETRYABLE")
                .containsEntry("failureType", IllegalArgumentException.class.getName());
        assertThat(listener.attempts()).isOne();
        cleanupGroup(group);
    }

    @Test
    void malformedPayload_onFirstDelivery_movesDirectlyToDeadLetter() {
        String group = uniqueGroup("malformed");
        RetryDeadLetterListener listener = listener(group, true);
        PendingFixture fixture = createPending(group, "stored-payload");
        ObjectRecord<String, String> malformed =
                ObjectRecord.create(sourceStreamKey(), "{").withId(fixture.recordId());

        listener.onMessage(malformed);

        assertThat(pendingCount(group)).isZero();
        assertThat(singleDeadLetter(group).getValue())
                .containsEntry("payload", "{")
                .containsEntry("failureKind", "NON_RETRYABLE");
        assertThat(listener.attempts()).isZero();
        cleanupGroup(group);
    }

    @Test
    void deadLetterWriteFailure_doesNotAcknowledgeOriginal() {
        String group = uniqueGroup("dlq-failure");
        RetryDeadLetterListener listener = listener(group, false);
        PendingFixture fixture = createPending(group, "write-failure");
        String deadLetterKey = deadLetterService.deadLetterKey(fixture.message().getStreamKey(), group);
        redisTemplate.opsForValue().set(deadLetterKey, "wrong-type");

        assertThatThrownBy(() -> listener.onMessage(fixture.record())).isInstanceOf(RuntimeException.class);

        assertThat(pendingCount(group)).isOne();
        redisTemplate.delete(deadLetterKey);
        cleanupGroup(group);
    }

    @Test
    void missingConsumerGroup_deadLetterDoesNotLeavePartialRecord() {
        String group = uniqueGroup("missing-group");
        RetryDeadLetterListener listener = listener(group, false);
        PendingFixture fixture = createPending(group, "missing-group");
        redisTemplate.opsForStream().destroyGroup(sourceStreamKey(), group);

        assertThatThrownBy(() -> listener.onMessage(fixture.record())).isInstanceOf(RuntimeException.class);

        assertThat(deadLetters(group)).isEmpty();
    }

    @Test
    void replay_repeatedRequestPublishesOnceAndKeepsThirtyDayAudit() {
        String group = uniqueGroup("replay");
        RetryDeadLetterListener listener = listener(group, false);
        PendingFixture fixture = createPending(group, "replay-payload");
        listener.onMessage(fixture.record());
        MapRecord<String, String, String> deadLetter = singleDeadLetter(group);
        Long sizeBeforeReplay =
                redisTemplate.opsForStream().size(fixture.message().getStreamKey());

        RecordId firstReplay = deadLetterService.replay(
                fixture.message().getStreamKey(), group, deadLetter.getId().getValue(), "admin-1", "修复数据后回放");
        RecordId repeatedReplay = deadLetterService.replay(
                fixture.message().getStreamKey(), group, deadLetter.getId().getValue(), "admin-1", "修复数据后回放");

        assertThat(repeatedReplay).isEqualTo(firstReplay);
        assertThat(redisTemplate.opsForStream().size(fixture.message().getStreamKey()))
                .isEqualTo(sizeBeforeReplay + 1L);
        assertThat(deadLetters(group)).isEmpty();
        RetryDeadLetterMessage replayed = readMessage(fixture.message().getStreamKey(), firstReplay);
        assertThat(replayed.getMessageId()).isEqualTo(fixture.message().getMessageId());
        assertThat(replayed.getPayload()).isEqualTo("replay-payload");
        assertDispositionAudit(
                group,
                deadLetter.getId(),
                new DispositionExpectation("REPLAYED", "admin-1", "修复数据后回放", firstReplay.getValue()));
        cleanupGroup(group);
    }

    @Test
    void dispositionIndexWrongType_doesNotPublishOrDeleteDeadLetter() {
        String group = uniqueGroup("wrong-index");
        RetryDeadLetterListener listener = listener(group, false);
        PendingFixture fixture = createPending(group, "wrong-index");
        listener.onMessage(fixture.record());
        RecordId deadLetterId = singleDeadLetter(group).getId();
        String indexKey = deadLetterService.deadLetterKey(sourceStreamKey(), group) + ":index";
        redisTemplate.delete(indexKey);
        redisTemplate.opsForValue().set(indexKey, "wrong-type");
        Long streamSize = redisTemplate.opsForStream().size(sourceStreamKey());

        assertThatThrownBy(() ->
                        deadLetterService.replay(sourceStreamKey(), group, deadLetterId.getValue(), "admin-1", "错误索引"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() ->
                        deadLetterService.discard(sourceStreamKey(), group, deadLetterId.getValue(), "admin-1", "错误索引"))
                .isInstanceOf(RuntimeException.class);
        assertThat(redisTemplate.opsForStream().size(sourceStreamKey())).isEqualTo(streamSize);
        assertThat(deadLetters(group)).hasSize(1);
        redisTemplate.delete(indexKey);
        cleanupGroup(group);
    }

    @Test
    void discard_repeatedRequestDoesNotRecreateOrDeleteAudit() {
        String group = uniqueGroup("discard");
        RetryDeadLetterListener listener = listener(group, false);
        PendingFixture fixture = createPending(group, "discard-payload");
        listener.onMessage(fixture.record());
        RecordId deadLetterId = singleDeadLetter(group).getId();

        deadLetterService.discard(
                fixture.message().getStreamKey(), group, deadLetterId.getValue(), "admin-2", "业务确认无需处理");
        deadLetterService.discard(
                fixture.message().getStreamKey(), group, deadLetterId.getValue(), "admin-2", "业务确认无需处理");

        assertThat(deadLetters(group)).isEmpty();
        assertDispositionAudit(
                group, deadLetterId, new DispositionExpectation("DISCARDED", "admin-2", "业务确认无需处理", null));
        cleanupGroup(group);
    }

    @Test
    void missingDeadLetter_replayAndDiscardFailWithoutPublishing() {
        String group = uniqueGroup("missing");
        String missingRecordId = "1-0";

        assertThatThrownBy(
                        () -> deadLetterService.replay(sourceStreamKey(), group, missingRecordId, "admin-1", "不存在记录"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("死信记录不存在");
        assertThatThrownBy(
                        () -> deadLetterService.discard(sourceStreamKey(), group, missingRecordId, "admin-1", "不存在记录"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("死信记录不存在");
        assertThat(redisTemplate.opsForStream().size(sourceStreamKey())).isZero();
    }

    private RetryDeadLetterListener listener(String group, boolean retryable) {
        return new RetryDeadLetterListener(redisMQTemplate, deadLetterService, group, retryable);
    }

    private RedisPendingMessageResendJob recoveryJob(RetryDeadLetterListener listener) {
        return new RedisPendingMessageResendJob(
                List.of(listener), redisMQTemplate, redissonClient, properties, "recovery-process", deadLetterService);
    }

    @SuppressWarnings("unchecked")
    private PendingFixture createPending(String group, String payload) {
        RetryDeadLetterMessage seed = new RetryDeadLetterMessage("seed");
        redisMQTemplate.send(seed);
        redisTemplate.opsForStream().createGroup(seed.getStreamKey(), ReadOffset.latest(), group);
        RetryDeadLetterMessage message = new RetryDeadLetterMessage(payload);
        RecordId recordId = redisMQTemplate.send(message);
        List<MapRecord<String, String, String>> records = redisTemplate
                .<String, String>opsForStream()
                .read(
                        Consumer.from(group, "initial-process"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(seed.getStreamKey(), ReadOffset.lastConsumed()));
        assertThat(records).hasSize(1);
        ObjectRecord<String, String> record =
                redisTemplate.<String, String>opsForStream().map(records.get(0), String.class);
        assertThat(record.getId()).isEqualTo(recordId);
        return new PendingFixture(message, recordId, record);
    }

    private long pendingCount(String group) {
        PendingMessagesSummary pending = redisTemplate.opsForStream().pending(sourceStreamKey(), group);
        return pending.getTotalPendingMessages();
    }

    private MapRecord<String, String, String> singleDeadLetter(String group) {
        assertThat(deadLetters(group)).hasSize(1);
        return deadLetters(group).get(0);
    }

    private List<MapRecord<String, String, String>> deadLetters(String group) {
        return redisTemplate
                .<String, String>opsForStream()
                .range(deadLetterService.deadLetterKey(sourceStreamKey(), group), Range.unbounded());
    }

    private RetryDeadLetterMessage readMessage(String streamKey, RecordId recordId) {
        List<MapRecord<String, String, String>> records = redisTemplate
                .<String, String>opsForStream()
                .range(streamKey, Range.closed(recordId.getValue(), recordId.getValue()));
        assertThat(records).hasSize(1);
        ObjectRecord<String, String> record =
                redisTemplate.<String, String>opsForStream().map(records.get(0), String.class);
        return JsonUtils.parseObject(record.getValue(), RetryDeadLetterMessage.class);
    }

    private void assertDeadLetterEvent(MapRecord<String, String, String> deadLetter, long deliveryCount) {
        assertThat(publishedEvents)
                .filteredOn(RedisStreamDeadLetterCreatedEvent.class::isInstance)
                .singleElement()
                .satisfies(event -> {
                    RedisStreamDeadLetterCreatedEvent deadLetterEvent = (RedisStreamDeadLetterCreatedEvent) event;
                    assertThat(deadLetterEvent.deadLetterKey()).isEqualTo(deadLetter.getRequiredStream());
                    assertThat(deadLetterEvent.deadLetterRecordId())
                            .isEqualTo(deadLetter.getId().getValue());
                    assertThat(deadLetterEvent.deliveryCount()).isEqualTo(deliveryCount);
                });
    }

    private void assertDispositionAudit(String group, RecordId deadLetterRecordId, DispositionExpectation expectation) {
        String deadLetterKey = deadLetterService.deadLetterKey(sourceStreamKey(), group);
        String auditKey = deadLetterKey + ":audit:" + deadLetterRecordId.getValue();
        Map<Object, Object> audit = redisTemplate.opsForHash().entries(auditKey);
        assertThat(audit)
                .containsEntry("disposition", expectation.disposition())
                .containsEntry("operator", expectation.operator())
                .containsEntry("reason", expectation.reason());
        if (expectation.replayRecordId() != null) {
            assertThat(audit).containsEntry("replayRecordId", expectation.replayRecordId());
        }
        assertThat(redisTemplate.getExpire(auditKey))
                .isPositive()
                .isLessThanOrEqualTo(properties.getDeadLetterAuditRetention().toSeconds());
    }

    private void cleanupGroup(String group) {
        String streamKey = sourceStreamKey();
        redisTemplate.opsForStream().destroyGroup(streamKey, group);
        redisTemplate.delete(deadLetterService.deadLetterKey(streamKey, group));
    }

    private static String uniqueGroup(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String sourceStreamKey() {
        return new RetryDeadLetterMessage().getStreamKey();
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
        RedisAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        BasicFrameworkRedisAutoConfiguration.class,
        BasicFrameworkRedisMQProducerAutoConfiguration.class
    })
    static class TestApplication {}

    private record PendingFixture(
            RetryDeadLetterMessage message, RecordId recordId, ObjectRecord<String, String> record) {}

    private record DispositionExpectation(String disposition, String operator, String reason, String replayRecordId) {}

    static final class RetryDeadLetterListener extends AbstractRedisStreamMessageListener<RetryDeadLetterMessage> {

        private final AtomicInteger attempts = new AtomicInteger();
        private final boolean retryable;

        RetryDeadLetterListener(
                RedisMQTemplate redisMQTemplate,
                RedisStreamDeadLetterService deadLetterService,
                String group,
                boolean retryable) {
            this.retryable = retryable;
            setRedisMQTemplate(redisMQTemplate);
            setDeadLetterService(deadLetterService);
            ReflectionTestUtils.setField(this, "group", group);
        }

        @Override
        public void onMessage(RetryDeadLetterMessage message) {
            attempts.incrementAndGet();
            throw retryable
                    ? new IllegalStateException("transient integration failure")
                    : new IllegalArgumentException("permanent integration failure");
        }

        @Override
        protected boolean isRetryable(RuntimeException failure) {
            return retryable;
        }

        int attempts() {
            return attempts.get();
        }
    }

    public static final class RetryDeadLetterMessage extends AbstractRedisStreamMessage {

        private String payload;

        public RetryDeadLetterMessage() {}

        RetryDeadLetterMessage(String payload) {
            this.payload = payload;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }
}
