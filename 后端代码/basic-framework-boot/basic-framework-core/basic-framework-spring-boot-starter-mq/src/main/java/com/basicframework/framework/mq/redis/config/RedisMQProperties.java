package com.basicframework.framework.mq.redis.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Redis MQ 可靠性配置。 */
@ConfigurationProperties(prefix = "basic-framework.mq.redis")
@Validated
@Data
public class RedisMQProperties {

    /** Stream 消费者单次最多拉取的消息数量。 */
    @Min(1)
    @Max(1000)
    private int streamReadBatchSize = 10;

    /** pending 消息转移给当前消费者前必须达到的最小空闲时间。 */
    @NotNull
    private Duration pendingMessageMinIdle = Duration.ofMinutes(5);

    /** 单条消息最大投递次数，包含首次投递。 */
    @Min(1)
    private int maxDeliveryAttempts = 5;

    /** 重试退避上限，平台契约要求不得超过一小时。 */
    @NotNull
    private Duration retryMaxDelay = Duration.ofHours(1);

    /** 指数退避抖动比例，0.2 表示在计算值上下 20% 范围内确定性抖动。 */
    @DecimalMin("0.0")
    @DecimalMax(value = "1.0", inclusive = false)
    private double retryJitterFactor = 0.2D;

    /** 死信处置审计保留时间，安全基线要求至少 30 天。 */
    @NotNull
    private Duration deadLetterAuditRetention = Duration.ofDays(30);

    /** Stream 至少保留的最近消息数量。 */
    @Min(1)
    private int streamMaxLength = 10000;

    /** 单次清理最多删除的消息数量，限制每小时任务占用 Redis 的时间。 */
    @Min(1)
    private int streamCleanupBatchSize = 10000;

    @AssertTrue(message = "basic-framework.mq.redis.pending-message-min-idle 必须大于 0")
    public boolean isPendingMessageMinIdleValid() {
        return pendingMessageMinIdle != null && !pendingMessageMinIdle.isZero() && !pendingMessageMinIdle.isNegative();
    }

    @AssertTrue(message = "basic-framework.mq.redis.retry-max-delay 必须大于 0、不得超过 1 小时且不小于 pending-message-min-idle")
    public boolean isRetryMaxDelayValid() {
        return retryMaxDelay != null
                && !retryMaxDelay.isZero()
                && !retryMaxDelay.isNegative()
                && retryMaxDelay.compareTo(Duration.ofHours(1)) <= 0
                && pendingMessageMinIdle != null
                && retryMaxDelay.compareTo(pendingMessageMinIdle) >= 0;
    }

    @AssertTrue(message = "basic-framework.mq.redis.dead-letter-audit-retention 不得少于 30 天")
    public boolean isDeadLetterAuditRetentionValid() {
        return deadLetterAuditRetention != null && deadLetterAuditRetention.compareTo(Duration.ofDays(30)) >= 0;
    }
}
