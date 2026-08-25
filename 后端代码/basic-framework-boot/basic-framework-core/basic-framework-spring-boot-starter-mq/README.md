# basic-framework-spring-boot-starter-mq

消息队列能力接缝。当前唯一实现是 **Redis 消息队列**（Stream 与 Pub/Sub
两种模式），供业务模块异步解耦使用。

## 提供的能力

- `RedisMQTemplate`：发送 Stream / Pub/Sub 消息。
- Stream 模式：继承 `AbstractRedisStreamMessage` 定义消息，
  继承 `AbstractRedisStreamMessageListener` 消费；支持消费组与待确认
  消息有限重投（`RedisPendingMessageResendJob`）、死信与人工处置
  （`RedisStreamDeadLetterService`）及历史消息清理（`RedisStreamMessageCleanupJob`）。
- Pub/Sub 模式：继承 `AbstractRedisChannelMessage` 定义消息，
  继承 `AbstractRedisChannelMessageListener` 消费。
- `RedisMessageInterceptor`：消息发送/消费的拦截扩展点。

## 依赖

`basic-framework-spring-boot-starter-redis`。两个自动配置
（Producer / Consumer）经 `AutoConfiguration.imports` 注册。

## 使用约束

- 业务模块只依赖本 starter 的抽象类型，不直接操作 Redis Stream 命令。
- Pub/Sub 不持久化，丢消息可接受的场景才用；需要可靠投递用 Stream。
- Stream 是 at-least-once 语义：每条消息携带跨重投不变的 `messageId`，消费方必须以该值建立业务 inbox/唯一约束，确保重复投递不会重复产生不可接受副作用。
- pending 恢复使用原记录 `XCLAIM` 转移，不复制新记录；默认总投递 5 次（含首次），以 `pending-message-min-idle` 的 5 分钟为基数做指数退避并加入 20% 确定性抖动，`retry-max-delay` 硬限制为不超过 1 小时。
- pending 恢复按监听器、消费者和单消息隔离故障；业务确定性错误覆盖监听器的 `isRetryable` 返回 `false`，首次失败直接进入 DLQ；其余失败在第五次投递后进入 DLQ，不阻断后续健康消息。
- DLQ 按原 Stream 与消费组隔离。默认 Stream key 为带 hash tag 的 `{消息类名}`；覆盖 `getStreamKey` 时也必须提供非空 hash tag，否则启动失败。DLQ key 保留该 tag，确保与原 Stream 位于同一 Redis Cluster slot。Lua 原子执行“写 DLQ + ACK 原 PEL”，写入失败时不 ACK；DLQ 仅保存异常类型，不保存可能含敏感值的异常正文。
- `RedisStreamDeadLetterCreatedEvent` 提供告警扩展点；`RedisStreamDeadLetterService.replay/discard` 要求操作者和原因，重复/并发回放只发布一次，处置审计至少保留 30 天。未处置 DLQ 不参与自动历史清理。
- `RedisStreamDeadLetterService` 是内部能力接缝，不是授权边界。若管理端暴露处置接口，Controller 必须校验专用权限与 MFA，并从服务端登录态生成 `operator`；禁止信任请求参数中的操作者。
- Stream 消费者组在应用启动时串行注册；只有 Redis 明确返回 `BUSYGROUP`（组已存在）时继续启动，连接、权限或命令错误会携带 Stream/组上下文使启动失败。
- Stream 历史清理会检查同一 Stream 的全部消费组，只删除早于“最近保留边界、各组最早 pending/最后投递边界”中最早 ID 的记录。因此 pending 和尚未投递的消息优先于长度上限保留，ACK 后才会进入后续清理。
- 可靠性配置统一位于 `basic-framework.mq.redis.*`：`max-delivery-attempts=5`、`retry-jitter-factor=0.2`、`retry-max-delay=1h`、`dead-letter-audit-retention=30d`；历史配置 `stream-max-length=10000`、`stream-cleanup-batch-size=10000` 保持不变。非法边界在启动期失败。

## 验证

本 starter 的 `RedisMQIT` / `RedisMQDeadLetterIT` 使用真实 Redis 容器验证 Pub/Sub 与 Stream
发布/消费、Stream ACK、有限重试、原子 DLQ、DLQ 写失败不 ACK、人工幂等处置、安全历史清理及 `RedisMessageInterceptor` 生命周期。
通过 Maven `integration` profile 运行；该 profile 的 JaCoCo 行覆盖率棘轮为 89%。

## 扩展实现

当前只提供 Redis 实现。新增 MQ Provider 时按「starter = 能力接缝」规范扩展，
必须定义与现有可靠性契约等价的确认、重试、死信、幂等和停机语义，并同步
`docs/capability-catalog.md`。
