package com.basicframework.framework.mq.redis.core.stream;

import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.mq.redis.config.RedisMQProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Redis Stream 死信写入、人工回放和丢弃操作。 */
@Slf4j
public class RedisStreamDeadLetterService {

    private static final int MAX_OPERATOR_LENGTH = 100;
    private static final int MAX_REASON_LENGTH = 500;
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("[A-Za-z0-9._:@-]{1,100}");
    private static final String DISPOSITION_DISCARDED = "DISCARDED";
    private static final String FIELD_ORIGINAL_RECORD_ID = "originalRecordId";
    private static final String FIELD_PAYLOAD = "payload";
    private static final String FIELD_REPLAY_RECORD_ID = "replayRecordId";

    private static final DefaultRedisScript<String> DEAD_LETTER_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('XPENDING', KEYS[1], ARGV[2])
            local existing = redis.call('HGET', KEYS[2], ARGV[1])
            if existing then
                redis.call('XACK', KEYS[1], ARGV[2], ARGV[1])
                return existing
            end
            local deadLetterId = redis.call('XADD', KEYS[3], '*',
                'originalStream', KEYS[1], 'group', ARGV[2], 'originalRecordId', ARGV[1],
                'messageId', ARGV[3], 'payload', ARGV[4], 'deliveryCount', ARGV[5],
                'failureKind', ARGV[6], 'failureType', ARGV[7], 'failedAt', ARGV[8])
            redis.call('HSET', KEYS[2], ARGV[1], deadLetterId)
            redis.call('XACK', KEYS[1], ARGV[2], ARGV[1])
            return deadLetterId
            """,
            String.class);

    private static final DefaultRedisScript<String> REPLAY_SCRIPT = new DefaultRedisScript<>(
            """
            local existing = redis.call('HGET', KEYS[4], 'replayRecordId')
            if existing then return existing end
            local indexType = redis.call('TYPE', KEYS[3]).ok
            if indexType ~= 'none' and indexType ~= 'hash' then
                return redis.error_reply('DLQ_INDEX_WRONG_TYPE')
            end
            if #redis.call('XRANGE', KEYS[2], ARGV[1], ARGV[1], 'COUNT', 1) == 0 then
                return redis.error_reply('DLQ_RECORD_NOT_FOUND')
            end
            local command = {'XADD', KEYS[1], '*'}
            for index = 7, #ARGV do table.insert(command, ARGV[index]) end
            local replayId = redis.call(unpack(command))
            redis.call('HSET', KEYS[4], 'disposition', 'REPLAYED', 'operator', ARGV[4],
                'reason', ARGV[5], 'disposedAt', ARGV[6], 'replayRecordId', replayId,
                'originalRecordId', ARGV[2], 'deadLetterRecordId', ARGV[1])
            redis.call('PEXPIRE', KEYS[4], ARGV[3])
            redis.call('XDEL', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[2])
            return replayId
            """,
            String.class);

    private static final DefaultRedisScript<String> DISCARD_SCRIPT = new DefaultRedisScript<>(
            """
            local existing = redis.call('HGET', KEYS[4], 'disposition')
            if existing then return existing end
            local indexType = redis.call('TYPE', KEYS[3]).ok
            if indexType ~= 'none' and indexType ~= 'hash' then
                return redis.error_reply('DLQ_INDEX_WRONG_TYPE')
            end
            if #redis.call('XRANGE', KEYS[2], ARGV[1], ARGV[1], 'COUNT', 1) == 0 then
                return redis.error_reply('DLQ_RECORD_NOT_FOUND')
            end
            redis.call('HSET', KEYS[4], 'disposition', 'DISCARDED', 'operator', ARGV[4],
                'reason', ARGV[5], 'disposedAt', ARGV[6], 'originalRecordId', ARGV[2],
                'deadLetterRecordId', ARGV[1])
            redis.call('PEXPIRE', KEYS[4], ARGV[3])
            redis.call('XDEL', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[2])
            return 'DISCARDED'
            """,
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisMQProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public RedisStreamDeadLetterService(
            StringRedisTemplate redisTemplate, RedisMQProperties properties, ApplicationEventPublisher eventPublisher) {
        this(redisTemplate, properties, eventPublisher, Clock.systemUTC());
    }

    RedisStreamDeadLetterService(
            StringRedisTemplate redisTemplate,
            RedisMQProperties properties,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 原子写入死信并确认原消息。脚本失败时原消息保持 pending。
     *
     * @param record 原消息
     * @param group 消费组
     * @param deliveryCount 总投递次数
     * @param failureKind 死信原因
     * @param failure 消费异常；重试耗尽但无最后异常对象时可为空
     * @return 死信记录编号
     */
    public RecordId deadLetter(
            ObjectRecord<String, String> record,
            String group,
            long deliveryCount,
            RedisStreamFailureKind failureKind,
            RuntimeException failure) {
        Objects.requireNonNull(record, "record must not be null");
        if (deliveryCount < 1L) {
            throw new IllegalArgumentException("deliveryCount 必须大于 0");
        }
        Objects.requireNonNull(failureKind, "failureKind must not be null");
        String streamKey = record.getRequiredStream();
        String originalRecordId = record.getId().getValue();
        String deadLetterKey = deadLetterKey(streamKey, group);
        String messageId = extractMessageId(record.getValue());
        String deadLetterId = redisTemplate.execute(
                DEAD_LETTER_SCRIPT,
                List.of(streamKey, deadLetterIndexKey(deadLetterKey), deadLetterKey),
                originalRecordId,
                group,
                messageId,
                record.getValue(),
                Long.toString(deliveryCount),
                failureKind.name(),
                failure == null ? "" : failure.getClass().getName(),
                Long.toString(clock.millis()));
        if (deadLetterId == null) {
            throw new IllegalStateException("Redis 未返回死信记录编号");
        }
        log.error(
                "[deadLetter][Stream({}) 消费者组({}) 消息({})进入死信({})，业务消息({})，投递次数({})，原因({})]",
                streamKey,
                group,
                originalRecordId,
                deadLetterId,
                messageId,
                deliveryCount,
                failureKind);
        publishAlert(new RedisStreamDeadLetterCreatedEvent(
                deadLetterKey, deadLetterId, messageId, deliveryCount, failureKind));
        return RecordId.of(deadLetterId);
    }

    /**
     * 人工幂等回放死信；原消息的 messageId 保持不变。
     * 调用方必须在管理边界完成权限/MFA 校验，并传入服务端认证身份，不能信任请求体中的 operator。
     *
     * @param streamKey 原 Stream
     * @param group 原消费组
     * @param deadLetterRecordId 死信记录编号
     * @param operator 操作人
     * @param reason 回放原因
     * @return 回放后的 Stream 记录编号
     */
    public RecordId replay(String streamKey, String group, String deadLetterRecordId, String operator, String reason) {
        validateDisposition(operator, reason);
        String deadLetterKey = deadLetterKey(streamKey, group);
        String auditKey = dispositionAuditKey(deadLetterKey, deadLetterRecordId);
        Object existingReplayId = redisTemplate.opsForHash().get(auditKey, FIELD_REPLAY_RECORD_ID);
        if (existingReplayId != null) {
            return RecordId.of(existingReplayId.toString());
        }
        Map<String, String> deadLetter;
        try {
            deadLetter = getDeadLetter(deadLetterKey, deadLetterRecordId);
        } catch (IllegalArgumentException exception) {
            Object concurrentReplayId = redisTemplate.opsForHash().get(auditKey, FIELD_REPLAY_RECORD_ID);
            if (concurrentReplayId != null) {
                return RecordId.of(concurrentReplayId.toString());
            }
            throw exception;
        }
        String originalRecordId = requireField(deadLetter, FIELD_ORIGINAL_RECORD_ID);
        String payload = requireField(deadLetter, FIELD_PAYLOAD);
        List<String> arguments = buildReplayArguments(deadLetterRecordId, originalRecordId, operator, reason, payload);
        String replayRecordId = redisTemplate.execute(
                REPLAY_SCRIPT,
                List.of(streamKey, deadLetterKey, deadLetterIndexKey(deadLetterKey), auditKey),
                arguments.toArray());
        if (replayRecordId == null) {
            throw new IllegalStateException("Redis 未返回回放记录编号");
        }
        return RecordId.of(replayRecordId);
    }

    /**
     * 人工丢弃死信并保留处置审计。
     * 调用方必须在管理边界完成权限/MFA 校验，并传入服务端认证身份，不能信任请求体中的 operator。
     *
     * @param streamKey 原 Stream
     * @param group 原消费组
     * @param deadLetterRecordId 死信记录编号
     * @param operator 操作人
     * @param reason 丢弃原因
     */
    public void discard(String streamKey, String group, String deadLetterRecordId, String operator, String reason) {
        validateDisposition(operator, reason);
        String deadLetterKey = deadLetterKey(streamKey, group);
        String auditKey = dispositionAuditKey(deadLetterKey, deadLetterRecordId);
        Object existingDisposition = redisTemplate.opsForHash().get(auditKey, "disposition");
        if (DISPOSITION_DISCARDED.equals(existingDisposition)) {
            return;
        }
        if (existingDisposition != null) {
            throw new IllegalStateException("死信已按其他方式处置: " + existingDisposition);
        }
        Map<String, String> deadLetter;
        try {
            deadLetter = getDeadLetter(deadLetterKey, deadLetterRecordId);
        } catch (IllegalArgumentException exception) {
            Object concurrentDisposition = redisTemplate.opsForHash().get(auditKey, "disposition");
            if (DISPOSITION_DISCARDED.equals(concurrentDisposition)) {
                return;
            }
            throw exception;
        }
        String originalRecordId = requireField(deadLetter, FIELD_ORIGINAL_RECORD_ID);
        String result = redisTemplate.execute(
                DISCARD_SCRIPT,
                List.of(streamKey, deadLetterKey, deadLetterIndexKey(deadLetterKey), auditKey),
                deadLetterRecordId,
                originalRecordId,
                Long.toString(properties.getDeadLetterAuditRetention().toMillis()),
                operator,
                reason,
                Long.toString(clock.millis()));
        if (!DISPOSITION_DISCARDED.equals(result)) {
            throw new IllegalStateException("死信已按其他方式处置: " + result);
        }
    }

    /**
     * 获得指定消费组的死信 Stream key。
     *
     * @param streamKey 原 Stream
     * @param group 消费组
     * @return 死信 Stream key
     */
    public String deadLetterKey(String streamKey, String group) {
        requireRedisHashTag(streamKey);
        if (StrUtil.isBlank(group)) {
            throw new IllegalArgumentException("Redis Stream 消费组不能为空");
        }
        return streamKey + ":dlq:" + group;
    }

    private List<String> buildReplayArguments(
            String deadLetterRecordId, String originalRecordId, String operator, String reason, String payload) {
        List<String> arguments = new ArrayList<>();
        arguments.add(deadLetterRecordId);
        arguments.add(originalRecordId);
        arguments.add(Long.toString(properties.getDeadLetterAuditRetention().toMillis()));
        arguments.add(operator);
        arguments.add(reason);
        arguments.add(Long.toString(clock.millis()));
        StreamOperations<String, String, String> operations = redisTemplate.opsForStream();
        operations.getHashMapper(String.class).toHash(payload).forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        return arguments;
    }

    private Map<String, String> getDeadLetter(String deadLetterKey, String deadLetterRecordId) {
        List<MapRecord<String, String, String>> records = redisTemplate
                .<String, String>opsForStream()
                .range(deadLetterKey, Range.closed(deadLetterRecordId, deadLetterRecordId));
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("死信记录不存在: " + deadLetterRecordId);
        }
        return records.get(0).getValue();
    }

    private void publishAlert(RedisStreamDeadLetterCreatedEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException exception) {
            log.error(
                    "[deadLetter][发布死信告警事件失败，死信({})已持久化，stackTrace({})]",
                    event.deadLetterRecordId(),
                    format(exception));
        }
    }

    private static String extractMessageId(String payload) {
        try {
            JsonNode messageId = JsonUtils.parseTree(payload).path("messageId");
            return messageId.isTextual() ? messageId.asText() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String requireField(Map<String, String> record, String field) {
        String value = record.get(field);
        if (StrUtil.isBlank(value)) {
            throw new IllegalStateException("死信记录缺少字段: " + field);
        }
        return value;
    }

    private static void validateDisposition(String operator, String reason) {
        if (StrUtil.isBlank(operator)
                || operator.length() > MAX_OPERATOR_LENGTH
                || !OPERATOR_PATTERN.matcher(operator).matches()) {
            throw new IllegalArgumentException("operator 必须是服务端认证标识且仅包含字母、数字及 ._:@-");
        }
        if (StrUtil.isBlank(reason)
                || reason.length() > MAX_REASON_LENGTH
                || reason.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("reason 必填、不得包含控制字符且长度不得超过 " + MAX_REASON_LENGTH);
        }
    }

    private static void requireRedisHashTag(String streamKey) {
        if (StrUtil.isBlank(streamKey)) {
            throw new IllegalArgumentException("Redis Stream key 不能为空");
        }
        int open = streamKey.indexOf('{');
        int close = open < 0 ? -1 : streamKey.indexOf('}', open + 1);
        if (open < 0 || close <= open + 1) {
            throw new IllegalArgumentException("Redis Stream key 必须包含非空 hash tag: " + streamKey);
        }
    }

    private static String deadLetterIndexKey(String deadLetterKey) {
        return deadLetterKey + ":index";
    }

    private static String dispositionAuditKey(String deadLetterKey, String deadLetterRecordId) {
        return deadLetterKey + ":audit:" + deadLetterRecordId;
    }
}
