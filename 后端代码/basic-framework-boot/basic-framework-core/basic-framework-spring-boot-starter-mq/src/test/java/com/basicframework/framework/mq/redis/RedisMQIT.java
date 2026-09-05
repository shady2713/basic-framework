package com.basicframework.framework.mq.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basicframework.framework.mq.redis.config.BasicFrameworkRedisMQConsumerAutoConfiguration;
import com.basicframework.framework.mq.redis.config.BasicFrameworkRedisMQProducerAutoConfiguration;
import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.interceptor.RedisMessageInterceptor;
import com.basicframework.framework.mq.redis.core.job.RedisPendingMessageResendJob;
import com.basicframework.framework.mq.redis.core.job.RedisStreamMessageCleanupJob;
import com.basicframework.framework.mq.redis.core.message.AbstractRedisMessage;
import com.basicframework.framework.mq.redis.core.pubsub.AbstractRedisChannelMessage;
import com.basicframework.framework.mq.redis.core.pubsub.AbstractRedisChannelMessageListener;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessage;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamDeadLetterService;
import com.basicframework.framework.redis.config.BasicFrameworkRedisAutoConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 Redis 验证 MQ 自动配置、发布、消费、Stream ACK、安全清理与拦截器生命周期。 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = RedisMQIT.TestApplication.class,
        properties = {
            "spring.application.name=redis-mq-integration",
            "spring.main.web-application-type=none",
            "spring.task.scheduling.enabled=false",
            "basic-framework.mq.redis.pending-message-min-idle=10ms"
        })
class RedisMQIT {

    private static final String REDIS_PASSWORD = "integration-only";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
                    "redis:7.4.11@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7"))
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @Autowired
    private RedisMQTemplate redisMQTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TestChannelListener channelListener;

    @Autowired
    private TestStreamListener streamListener;

    @Autowired
    private EventTrace eventTrace;

    @Autowired
    private RedisMQProperties redisMQProperties;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisStreamDeadLetterService deadLetterService;

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @AfterEach
    void clearTestState() {
        channelListener.clear();
        streamListener.clear();
        eventTrace.clear();
    }

    @Test
    void pubSub_publishConsumesAndRunsInterceptors() throws InterruptedException {
        TestChannelMessage message = new TestChannelMessage("channel-payload");

        redisMQTemplate.send(message);

        assertThat(channelListener.poll(Duration.ofSeconds(10))).isEqualTo("channel-payload");
        assertThat(eventTrace.pollConsumption(Duration.ofSeconds(10))).isEqualTo("consume-after:TestChannelMessage");
        assertThat(eventTrace.snapshot())
                .contains("send-before:TestChannelMessage", "send-after:TestChannelMessage")
                .containsSubsequence("send-before:TestChannelMessage", "send-after:TestChannelMessage")
                .containsSubsequence(
                        "consume-before:TestChannelMessage",
                        "listener:TestChannelMessage",
                        "consume-after:TestChannelMessage");
    }

    @Test
    void stream_publishConsumesAcknowledgesAndRunsInterceptors() throws InterruptedException {
        TestStreamMessage message = new TestStreamMessage("stream-payload");

        RecordId recordId = redisMQTemplate.send(message);

        assertThat(recordId).isNotNull();
        assertThat(streamListener.poll(Duration.ofSeconds(10))).isEqualTo("stream-payload");
        assertThat(eventTrace.pollConsumption(Duration.ofSeconds(10))).isEqualTo("consume-after:TestStreamMessage");
        PendingMessagesSummary pending =
                redisTemplate.opsForStream().pending(message.getStreamKey(), streamListener.getGroup());
        assertThat(pending.getTotalPendingMessages()).isZero();
        assertThat(eventTrace.snapshot())
                .contains("send-before:TestStreamMessage", "send-after:TestStreamMessage")
                .containsSubsequence("send-before:TestStreamMessage", "send-after:TestStreamMessage")
                .containsSubsequence(
                        "consume-before:TestStreamMessage",
                        "listener:TestStreamMessage",
                        "consume-after:TestStreamMessage");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stream_pendingFromStoppedProcessClaimsOriginalRecordWithoutCopying() throws InterruptedException {
        String recoveryGroup = "restart-test-" + UUID.randomUUID();
        RecoveryStreamMessage seed = new RecoveryStreamMessage("seed");
        String streamKey = seed.getStreamKey();
        RecoveryStreamListener recoveryListener = new RecoveryStreamListener(redisMQTemplate, recoveryGroup);
        RedisPendingMessageResendJob recoveryJob = new RedisPendingMessageResendJob(
                List.of(recoveryListener),
                redisMQTemplate,
                redissonClient,
                redisMQProperties,
                "restarted-process",
                deadLetterService);
        redisMQTemplate.send(seed);
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), recoveryGroup);
            RecoveryStreamMessage message = new RecoveryStreamMessage("restart-payload");
            RecordId recordId = redisMQTemplate.send(message);
            Long sizeBeforeRecovery = redisTemplate.opsForStream().size(streamKey);

            List<MapRecord<String, String, String>> pendingRecords = redisTemplate
                    .<String, String>opsForStream()
                    .read(
                            Consumer.from(recoveryGroup, "stopped-process"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
            assertThat(pendingRecords)
                    .singleElement()
                    .extracting(MapRecord::getId)
                    .isEqualTo(recordId);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                recoveryJob.messageResend();
                assertThat(recoveryListener.queuedPayloads()).isEqualTo(1);
            });

            assertThat(recoveryListener.poll(Duration.ofSeconds(1))).isEqualTo("restart-payload");
            assertThat(recoveryListener.pollMessageId(Duration.ofSeconds(1))).isEqualTo(message.getMessageId());
            PendingMessagesSummary pending = redisTemplate.opsForStream().pending(streamKey, recoveryGroup);
            assertThat(pending.getTotalPendingMessages()).isZero();
            assertThat(redisTemplate.opsForStream().size(streamKey)).isEqualTo(sizeBeforeRecovery);
        } finally {
            redisTemplate.opsForStream().destroyGroup(streamKey, recoveryGroup);
            redisTemplate.delete(streamKey);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void stream_pendingFailuresDoNotBlockOtherListenersOrFollowingRecords() throws InterruptedException {
        String group = "poison-test-" + UUID.randomUUID();
        PoisonRecoveryListener recoveryListener = new PoisonRecoveryListener(redisMQTemplate, group);
        CleanupStreamListener missingStreamListener =
                new CleanupStreamListener("missing-stream-" + UUID.randomUUID(), group);
        RedisPendingMessageResendJob recoveryJob = new RedisPendingMessageResendJob(
                List.of(missingStreamListener, recoveryListener),
                redisMQTemplate,
                redissonClient,
                redisMQProperties,
                "recovery-process",
                deadLetterService);
        PoisonRecoveryMessage seed = new PoisonRecoveryMessage("seed");
        String streamKey = seed.getStreamKey();
        redisMQTemplate.send(seed);
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), group);
            RecordId poisonRecordId = redisMQTemplate.send(new PoisonRecoveryMessage("poison"));
            RecordId healthyRecordId = redisMQTemplate.send(new PoisonRecoveryMessage("healthy"));
            List<MapRecord<String, String, String>> pendingRecords = redisTemplate
                    .<String, String>opsForStream()
                    .read(
                            Consumer.from(group, "stopped-process"),
                            StreamReadOptions.empty().count(2),
                            StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
            assertThat(pendingRecords).extracting(MapRecord::getId).containsExactly(poisonRecordId, healthyRecordId);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                recoveryJob.messageResend();
                assertThat(recoveryListener.queuedPayloads()).isEqualTo(1);
            });

            assertThat(recoveryListener.poll(Duration.ofSeconds(1))).isEqualTo("healthy");
            PendingMessagesSummary pending = redisTemplate.opsForStream().pending(streamKey, group);
            assertThat(pending.getTotalPendingMessages()).isEqualTo(1);
            assertThat(pending.minRecordId()).isEqualTo(poisonRecordId);
            assertThat(redisTemplate
                            .opsForStream()
                            .range(
                                    streamKey,
                                    Range.of(
                                            Range.Bound.inclusive(poisonRecordId.getValue()),
                                            Range.Bound.inclusive(poisonRecordId.getValue()))))
                    .hasSize(1);
        } finally {
            redisTemplate.opsForStream().destroyGroup(streamKey, group);
            redisTemplate.delete(streamKey);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void stream_cleanupPreservesPendingRecordsAndTrimsAcknowledgedHistory() {
        String streamKey = "cleanup-stream-" + UUID.randomUUID();
        String group = "cleanup-group-" + UUID.randomUUID();
        String consumer = "cleanup-consumer";
        CleanupStreamListener cleanupListener = new CleanupStreamListener(streamKey, group);
        RedisMQProperties cleanupProperties = new RedisMQProperties();
        cleanupProperties.setStreamMaxLength(3);
        cleanupProperties.setStreamCleanupBatchSize(10);
        RedisStreamMessageCleanupJob cleanupJob = new RedisStreamMessageCleanupJob(
                List.of(cleanupListener), redisMQTemplate, redissonClient, cleanupProperties);
        StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
        try {
            List<RecordId> recordIds = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                recordIds.add(streamOperations.add(streamKey, Map.of("payload", Integer.toString(index))));
            }
            streamOperations.createGroup(streamKey, ReadOffset.from("0-0"), group);
            List<MapRecord<String, String, String>> delivered = streamOperations.read(
                    Consumer.from(group, consumer),
                    StreamReadOptions.empty().count(6),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
            assertThat(delivered).hasSize(6);
            RecordId pendingRecordId = delivered.get(0).getId();
            RecordId[] acknowledgedIds =
                    delivered.stream().skip(1).map(MapRecord::getId).toArray(RecordId[]::new);
            streamOperations.acknowledge(streamKey, group, acknowledgedIds);

            cleanupJob.cleanup();

            assertThat(streamOperations.size(streamKey)).isEqualTo(6);
            assertThat(streamOperations.range(
                            streamKey,
                            Range.of(
                                    Range.Bound.inclusive(pendingRecordId.getValue()),
                                    Range.Bound.inclusive(pendingRecordId.getValue()))))
                    .hasSize(1);

            streamOperations.acknowledge(streamKey, group, pendingRecordId);
            cleanupJob.cleanup();

            assertThat(streamOperations.size(streamKey)).isEqualTo(3);
            List<RecordId> remainingIds = streamOperations.range(streamKey, Range.unbounded()).stream()
                    .map(MapRecord::getId)
                    .toList();
            assertThat(remainingIds).containsExactlyElementsOf(recordIds.subList(3, 6));
        } finally {
            streamOperations.destroyGroup(streamKey, group);
            redisTemplate.delete(streamKey);
        }
    }

    @SpringBootConfiguration
    @Import(MQTestConfiguration.class)
    @ImportAutoConfiguration({
        RedisAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        BasicFrameworkRedisAutoConfiguration.class,
        BasicFrameworkRedisMQProducerAutoConfiguration.class,
        BasicFrameworkRedisMQConsumerAutoConfiguration.class
    })
    static class TestApplication {}

    @TestConfiguration(proxyBeanMethods = false)
    static class MQTestConfiguration {

        @Bean
        EventTrace eventTrace() {
            return new EventTrace();
        }

        @Bean
        RedisMessageInterceptor recordingInterceptor(EventTrace eventTrace) {
            return new RecordingInterceptor(eventTrace);
        }

        @Bean
        TestChannelListener testChannelListener(EventTrace eventTrace) {
            return new TestChannelListener(eventTrace);
        }

        @Bean
        TestStreamListener testStreamListener(EventTrace eventTrace) {
            return new TestStreamListener(eventTrace);
        }
    }

    static final class EventTrace {

        private final List<String> events = new CopyOnWriteArrayList<>();
        private final BlockingQueue<String> consumptions = new LinkedBlockingQueue<>();

        void add(String event) {
            events.add(event);
            if (event.startsWith("consume-after:")) {
                consumptions.add(event);
            }
        }

        List<String> snapshot() {
            return List.copyOf(events);
        }

        String pollConsumption(Duration timeout) throws InterruptedException {
            return consumptions.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void clear() {
            events.clear();
            consumptions.clear();
        }
    }

    static final class RecordingInterceptor implements RedisMessageInterceptor {

        private final EventTrace eventTrace;

        RecordingInterceptor(EventTrace eventTrace) {
            this.eventTrace = eventTrace;
        }

        @Override
        public void sendMessageBefore(AbstractRedisMessage message) {
            eventTrace.add("send-before:" + message.getClass().getSimpleName());
        }

        @Override
        public void sendMessageAfter(AbstractRedisMessage message) {
            eventTrace.add("send-after:" + message.getClass().getSimpleName());
        }

        @Override
        public void consumeMessageBefore(AbstractRedisMessage message) {
            eventTrace.add("consume-before:" + message.getClass().getSimpleName());
        }

        @Override
        public void consumeMessageAfter(AbstractRedisMessage message) {
            eventTrace.add("consume-after:" + message.getClass().getSimpleName());
        }
    }

    static final class TestChannelListener extends AbstractRedisChannelMessageListener<TestChannelMessage> {

        private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
        private final EventTrace eventTrace;

        TestChannelListener(EventTrace eventTrace) {
            this.eventTrace = eventTrace;
        }

        @Override
        public void onMessage(TestChannelMessage message) {
            eventTrace.add("listener:" + message.getClass().getSimpleName());
            payloads.add(message.getPayload());
        }

        String poll(Duration timeout) throws InterruptedException {
            return payloads.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void clear() {
            payloads.clear();
        }
    }

    static final class TestStreamListener extends AbstractRedisStreamMessageListener<TestStreamMessage> {

        private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> messageIds = new LinkedBlockingQueue<>();
        private final EventTrace eventTrace;

        TestStreamListener(EventTrace eventTrace) {
            this.eventTrace = eventTrace;
        }

        @Override
        public void onMessage(TestStreamMessage message) {
            eventTrace.add("listener:" + message.getClass().getSimpleName());
            payloads.add(message.getPayload());
            messageIds.add(message.getMessageId());
        }

        String poll(Duration timeout) throws InterruptedException {
            return payloads.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        String pollMessageId(Duration timeout) throws InterruptedException {
            return messageIds.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        int queuedPayloads() {
            return payloads.size();
        }

        void clear() {
            payloads.clear();
            messageIds.clear();
        }
    }

    static final class RecoveryStreamListener extends AbstractRedisStreamMessageListener<RecoveryStreamMessage> {

        private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> messageIds = new LinkedBlockingQueue<>();

        RecoveryStreamListener(RedisMQTemplate redisMQTemplate, String group) {
            setRedisMQTemplate(redisMQTemplate);
            ReflectionTestUtils.setField(this, "group", group);
        }

        @Override
        public void onMessage(RecoveryStreamMessage message) {
            payloads.add(message.getPayload());
            messageIds.add(message.getMessageId());
        }

        String poll(Duration timeout) throws InterruptedException {
            return payloads.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        String pollMessageId(Duration timeout) throws InterruptedException {
            return messageIds.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        int queuedPayloads() {
            return payloads.size();
        }
    }

    static final class CleanupStreamListener extends AbstractRedisStreamMessageListener<CleanupStreamMessage> {

        CleanupStreamListener(String streamKey, String group) {
            super(streamKey, group);
        }

        @Override
        public void onMessage(CleanupStreamMessage message) {}
    }

    static final class PoisonRecoveryListener extends AbstractRedisStreamMessageListener<PoisonRecoveryMessage> {

        private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();

        PoisonRecoveryListener(RedisMQTemplate redisMQTemplate, String group) {
            setRedisMQTemplate(redisMQTemplate);
            ReflectionTestUtils.setField(this, "group", group);
        }

        @Override
        public void onMessage(PoisonRecoveryMessage message) {
            if ("poison".equals(message.getPayload())) {
                throw new IllegalStateException("expected poison message");
            }
            payloads.add(message.getPayload());
        }

        String poll(Duration timeout) throws InterruptedException {
            return payloads.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        int queuedPayloads() {
            return payloads.size();
        }
    }

    public static final class TestChannelMessage extends AbstractRedisChannelMessage {

        private String payload;

        public TestChannelMessage() {}

        TestChannelMessage(String payload) {
            this.payload = payload;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    public static final class TestStreamMessage extends AbstractRedisStreamMessage {

        private String payload;

        public TestStreamMessage() {}

        TestStreamMessage(String payload) {
            this.payload = payload;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    public static final class RecoveryStreamMessage extends AbstractRedisStreamMessage {

        private String payload;

        public RecoveryStreamMessage() {}

        RecoveryStreamMessage(String payload) {
            this.payload = payload;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    public static final class CleanupStreamMessage extends AbstractRedisStreamMessage {}

    public static final class PoisonRecoveryMessage extends AbstractRedisStreamMessage {

        private String payload;

        public PoisonRecoveryMessage() {}

        PoisonRecoveryMessage(String payload) {
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
