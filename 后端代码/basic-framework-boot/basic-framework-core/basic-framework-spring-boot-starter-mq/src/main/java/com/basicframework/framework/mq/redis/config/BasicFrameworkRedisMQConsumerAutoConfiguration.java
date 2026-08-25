package com.basicframework.framework.mq.redis.config;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import com.basicframework.framework.mq.redis.core.RedisMQTemplate;
import com.basicframework.framework.mq.redis.core.job.RedisPendingMessageResendJob;
import com.basicframework.framework.mq.redis.core.job.RedisStreamMessageCleanupJob;
import com.basicframework.framework.mq.redis.core.pubsub.AbstractRedisChannelMessageListener;
import com.basicframework.framework.mq.redis.core.stream.AbstractRedisStreamMessageListener;
import com.basicframework.framework.mq.redis.core.stream.RedisStreamDeadLetterService;
import com.basicframework.framework.redis.config.BasicFrameworkRedisAutoConfiguration;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Redis 消息队列 Consumer 配置类
 *
 */
@Slf4j
@EnableScheduling // 启用定时任务，用于 RedisPendingMessageResendJob 重发消息
@AutoConfiguration(after = BasicFrameworkRedisAutoConfiguration.class)
@EnableConfigurationProperties(RedisMQProperties.class)
public class BasicFrameworkRedisMQConsumerAutoConfiguration {

    /**
     * 创建 Redis Pub/Sub 广播消费的容器
     */
    @Bean
    @ConditionalOnBean(
            AbstractRedisChannelMessageListener.class) // 只有 AbstractChannelMessageListener 存在的时候，才需要注册 Redis pubsub 监听
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisMQTemplate redisMQTemplate, List<AbstractRedisChannelMessageListener<?>> listeners) {
        // 创建 RedisMessageListenerContainer 对象
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        // 设置 RedisConnection 工厂。
        container.setConnectionFactory(redisMQTemplate.getRedisTemplate().getRequiredConnectionFactory());
        // 添加监听器
        listeners.forEach(listener -> {
            listener.setRedisMQTemplate(redisMQTemplate);
            container.addMessageListener(listener, new ChannelTopic(listener.getChannel()));
            log.info(
                    "[redisMessageListenerContainer][注册 Channel({}) 对应的监听器({})]",
                    listener.getChannel(),
                    listener.getClass().getName());
        });
        return container;
    }

    /**
     * 创建 Redis Stream 重新消费的任务
     */
    @Bean
    @ConditionalOnBean(
            AbstractRedisStreamMessageListener.class) // 只有 AbstractStreamMessageListener 存在的时候，才需要注册 Redis pubsub 监听
    public RedisPendingMessageResendJob redisPendingMessageResendJob(
            List<AbstractRedisStreamMessageListener<?>> listeners,
            RedisMQTemplate redisTemplate,
            RedissonClient redissonClient,
            RedisMQProperties properties,
            RedisStreamDeadLetterService deadLetterService) {
        return new RedisPendingMessageResendJob(
                listeners, redisTemplate, redissonClient, properties, buildConsumerName(), deadLetterService);
    }

    /** 创建 Redis Stream 死信与人工处置服务。 */
    @Bean
    @ConditionalOnBean(AbstractRedisStreamMessageListener.class)
    public RedisStreamDeadLetterService redisStreamDeadLetterService(
            StringRedisTemplate redisTemplate, RedisMQProperties properties, ApplicationEventPublisher eventPublisher) {
        return new RedisStreamDeadLetterService(redisTemplate, properties, eventPublisher);
    }

    /**
     * 创建 Redis Stream 消息清理任务
     */
    @Bean
    @ConditionalOnBean(AbstractRedisStreamMessageListener.class)
    public RedisStreamMessageCleanupJob redisStreamMessageCleanupJob(
            List<AbstractRedisStreamMessageListener<?>> listeners,
            RedisMQTemplate redisTemplate,
            RedissonClient redissonClient,
            RedisMQProperties properties) {
        return new RedisStreamMessageCleanupJob(listeners, redisTemplate, redissonClient, properties);
    }

    /**
     * 创建 Redis Stream 集群消费的容器
     *
     * 基础知识：<a href="https://www.geek-book.com/src/docs/redis/redis/redis.io/commands/xreadgroup.html">Redis Stream 的 xreadgroup 命令</a>
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnBean(
            AbstractRedisStreamMessageListener.class) // 只有 AbstractStreamMessageListener 存在的时候，才需要注册 Redis pubsub 监听
    public StreamMessageListenerContainer<String, ObjectRecord<String, String>> redisStreamMessageListenerContainer(
            RedisMQTemplate redisMQTemplate,
            List<AbstractRedisStreamMessageListener<?>> listeners,
            RedisStreamDeadLetterService deadLetterService) {
        StringRedisTemplate redisTemplate = redisMQTemplate.getRedisTemplate();
        checkRedisVersion(redisTemplate);
        // 第一步，创建 StreamMessageListenerContainer 容器
        // 创建 options 配置
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, ObjectRecord<String, String>>
                containerOptions = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .batchSize(10) // 一次性最多拉取多少条消息
                        .targetType(String.class) // 目标类型。统一使用 String，通过自己封装的 AbstractStreamMessageListener 去反序列化
                        .build();
        // 创建 container 对象
        StreamMessageListenerContainer<String, ObjectRecord<String, String>> container =
                StreamMessageListenerContainer.create(
                        redisMQTemplate.getRedisTemplate().getRequiredConnectionFactory(), containerOptions);

        // 第二步，注册监听器，消费对应的 Stream 主题
        String consumerName = buildConsumerName();
        listeners.forEach(listener -> {
            String deadLetterKey = deadLetterService.deadLetterKey(listener.getStreamKey(), listener.getGroup());
            log.info(
                    "[redisStreamMessageListenerContainer][开始注册 StreamKey({})、DLQ({}) 对应的监听器({})]",
                    listener.getStreamKey(),
                    deadLetterKey,
                    listener.getClass().getName());
            createConsumerGroup(listener.getStreamKey(), listener.getGroup(), () -> redisTemplate
                    .opsForStream()
                    .createGroup(listener.getStreamKey(), listener.getGroup()));
            // 设置 listener 对应的 redisTemplate
            listener.setRedisMQTemplate(redisMQTemplate);
            listener.setDeadLetterService(deadLetterService);
            // 创建 Consumer 对象
            Consumer consumer = Consumer.from(listener.getGroup(), consumerName);
            // 设置 Consumer 消费进度，以最小消费进度为准
            StreamOffset<String> streamOffset = StreamOffset.create(listener.getStreamKey(), ReadOffset.lastConsumed());
            // 设置 Consumer 监听
            StreamMessageListenerContainer.StreamReadRequestBuilder<String> builder =
                    StreamMessageListenerContainer.StreamReadRequest.builder(streamOffset)
                            .consumer(consumer)
                            .autoAcknowledge(false) // 不自动 ack
                            .cancelOnError(throwable -> false); // 默认配置，发生异常就取消消费，显然不符合预期；因此，我们设置为 false
            container.register(builder.build(), listener);
            log.info(
                    "[redisStreamMessageListenerContainer][完成注册 StreamKey({}) 对应的监听器({})]",
                    listener.getStreamKey(),
                    listener.getClass().getName());
        });
        return container;
    }

    static void createConsumerGroup(String streamKey, String group, Runnable groupCreator) {
        try {
            groupCreator.run();
        } catch (RuntimeException exception) {
            if (containsRedisErrorCode(exception, "BUSYGROUP")) {
                log.debug("[createConsumerGroup][StreamKey({}) 的消费者组({}) 已存在]", streamKey, group);
                return;
            }
            throw new IllegalStateException(
                    StrUtil.format("创建 Redis Stream({}) 消费者组({}) 失败", streamKey, group), exception);
        }
    }

    private static boolean containsRedisErrorCode(Throwable throwable, String errorCode) {
        Throwable current = throwable;
        while (current != null) {
            if (StrUtil.contains(current.getMessage(), errorCode)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 构建消费者名字，使用本地 IP + 进程编号的方式。
     * 参考自 RocketMQ clientId 的实现
     *
     * @return 消费者名字
     */
    public static String buildConsumerName() {
        return String.format("%s@%d", SystemUtil.getHostInfo().getAddress(), SystemUtil.getCurrentPID());
    }

    /**
     * 校验 Redis 版本号，是否满足最低的版本号要求！
     */
    public static void checkRedisVersion(RedisTemplate<String, ?> redisTemplate) {
        // 获得 Redis 版本
        Properties info = redisTemplate.execute((RedisCallback<Properties>) RedisServerCommands::info);
        String version = MapUtil.getStr(info, "redis_version");
        // 校验最低版本必须大于等于 5.0.0
        int majorVersion = Integer.parseInt(StrUtil.subBefore(version, '.', false));
        if (majorVersion < 5) {
            throw new IllegalStateException(StrUtil.format("当前 Redis 版本为 {}，低于最低要求的 5.0.0，请升级 Redis", version));
        }
    }
}
