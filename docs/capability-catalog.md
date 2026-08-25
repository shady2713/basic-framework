# 能力目录（Capability Catalog）

> 本目录是 core starters 能力接缝的登记处：每个 starter 的契约
> （auto-configuration + properties）、扩展点与消费方式在此各有一个家。
> 行为变更时同步更新本文件与对应条目；规则总纲见根目录 AGENTS.md，
> 业务开发流程见 docs/development-guide.md。

## 原则

- starter 是能力接缝：对外只暴露 auto-configuration 与 `Properties`
  配置类，业务模块通过契约消费，不得进入 starter 内部实现。
- 跨业务模块调用只允许走拥有方模块的 api 包（CommonApi + DTO）。
- 配置默认值只存在于拥有方 auto-configuration 的 Properties 类，
  业务代码不散落兜底字面量；密钥一律来自环境变量。
- 运行时注册物（Job、Listener、Cache）必须有对应卸载路径。

## core 模块清单

### basic-framework-common

共享基础层：统一异常体系与错误码、校验工具、pojo 基类（分页/排序）、
`TracerUtils`（SkyWalking traceId）。无 auto-configuration，不持有业务
语义。能力接缝：`CurrentUserProvider`（`common.security` 包）声明
"当前登录用户身份"SPI，由 security starter 注册实现，消费者经
ObjectProvider 注入、缺省时回退 null。

### basic-framework-spring-boot-starter-web

Web 入口集成。auto-configuration：

- `BasicFrameworkWebAutoConfiguration`：全局异常处理、拦截器/过滤器装配
- `BasicFrameworkApiLogAutoConfiguration`：API 访问日志过滤器
- `BasicFrameworkJacksonAutoConfiguration`：Jackson 序列化统一
- `BasicFrameworkSwaggerAutoConfiguration`：接口文档
- `BasicFrameworkXssAutoConfiguration`：XSS 过滤（`basic-framework.xss`）

Properties：`WebProperties`（`basic-framework.web`）、
`SwaggerProperties`（`basic-framework.swagger`）、`XssProperties`。

访问日志与异常日志统一经 `SensitiveDataSanitizer` 处理：字段名匹配忽略
大小写、下划线和连字符，密码、Token、Secret、API/访问/私钥、会话凭证、
验证码及 L4 敏感个人信息默认从 query、JSON body 和启用记录的响应 data 中
删除；JSON 解析失败只记录固定占位内容。接口特有敏感字段通过
`@ApiAccessLog.sanitizeKeys` 追加，禁止缩减全局规则。

### basic-framework-spring-boot-starter-security

认证与授权。auto-configuration：

- `BasicFrameworkSecurityAutoConfiguration`：安全规则与 Token 过滤器
- `BasicFrameworkWebSecurityConfigurerAdapter`：URL 授权规则
- `BasicFrameworkOperateLogConfiguration`：操作日志切面

Properties：`SecurityProperties`（`basic-framework.security`，含
permit-all 清单和部署注入的凭据主密钥）。该自动配置同时提供
`CredentialCipher`，供业务模块以版本化 AES-GCM 加密必须恢复的 L4 凭据。
消费 `module-system` api 包的
UserSessionCommonApi / PermissionCommonApi / OperateLogCommonApi。
注册 common 层 `CurrentUserProvider` SPI 的实现
（`CurrentUserProviderImpl`，委托 `SecurityFrameworkUtils`），向
mybatis 字段填充、数据权限等 starter 提供当前登录用户身份。

### basic-framework-spring-boot-starter-redis

Redis 连接与缓存抽象。auto-configuration：
`BasicFrameworkRedisAutoConfiguration`、
`BasicFrameworkCacheAutoConfiguration`。配置走 Spring 原生
`spring.data.redis` / 缓存前缀属性。

### basic-framework-spring-boot-starter-protection

服务保障：幂等、分布式锁和限流。auto-configuration：

- `BasicFrameworkIdempotentConfiguration`（幂等注解切面）
- `BasicFrameworkLock4jConfiguration`（lock4j 分布式锁）
- `BasicFrameworkRateLimiterConfiguration`（`@RateLimiter`，登录/短信/
  注册等端点强制挂载，见 AGENTS.md 安全基线）

### basic-framework-spring-boot-starter-mybatis

数据访问。auto-configuration：

- `BasicFrameworkDataSourceAutoConfiguration`：dynamic-datasource + Druid；默认只配置
  主数据源，业务确有路由需求时再由部署配置增加命名数据源
- `BasicFrameworkMybatisAutoConfiguration`：MyBatis-Plus、字段填充、
  分页
- `BasicFrameworkTranslateAutoConfiguration`：easy-trans 字段翻译

### basic-framework-spring-boot-starter-mq

消息队列接缝。当前唯一可用实现为 Redis（Stream / PubSub）：

- `BasicFrameworkRedisMQProducerAutoConfiguration`
- `BasicFrameworkRedisMQConsumerAutoConfiguration`

Stream pending 恢复通过 `XCLAIM` 转移原记录，不复制新记录；消息携带跨重投稳定的
`messageId`，消费方按 at-least-once 契约负责业务幂等。最小 pending 空闲时间由
`basic-framework.mq.redis.pending-message-min-idle` 类型化配置拥有。消费者组启动注册只忽略
Redis 的 `BUSYGROUP` 已存在响应，其他 Redis 错误 fail-fast，避免应用在未注册消费者时假启动。
pending 恢复按监听器、消费者和单消息隔离；默认总投递 5 次，以最小空闲时间为基数做指数退避、
20% 确定性抖动且上限 1 小时。确定性错误由监听器 `isRetryable` 分类并直接进入 DLQ，其余消息
重试耗尽后进入 DLQ；Lua 原子保证 DLQ 写入成功后才 ACK 原 PEL，失败不 ACK。
`RedisStreamDeadLetterCreatedEvent` 供告警适配器订阅；`RedisStreamDeadLetterService` 提供人工幂等
回放/丢弃，未处置死信不自动删除，处置审计至少保留 30 天。默认 Stream key 使用消息类名作为
Redis hash tag；自定义 key 也必须包含非空 hash tag，启动期校验失败，确保 DLQ 与原 Stream 同 slot。
历史清理以同一 Stream 全部消费组的最早 pending/最后投递边界约束删除范围；未确认或尚未投递
消息不会因长度上限被裁剪。保留量与单次删除量分别由 `stream-max-length`、
`stream-cleanup-batch-size` 配置，pending 安全优先于长度上限。

当前只提供 Redis Stream MQ；未提供 RabbitMQ、Kafka 或 RocketMQ 的配置、依赖或条件装配。
能力与使用约束见 starter README。新增 MQ 实现时，提供与 `RedisMQTemplate` 同语义的模板和
成对 auto-configuration，不得只增加配置假象。

### basic-framework-spring-boot-starter-job

定时任务与异步。auto-configuration：
`BasicFrameworkQuartzAutoConfiguration`（Quartz 集群调度）、
`BasicFrameworkAsyncAutoConfiguration`（`@EnableAsync` + TTL 上下文传递 +
受管 `applicationTaskExecutor` 线程池，`basic-framework.async.*` 配置，
优雅关闭时等待在途任务完成）。

### basic-framework-spring-boot-starter-excel

Excel 导入导出与字典翻译：
`BasicFrameworkDictAutoConfiguration`（消费 module-system api 包的
DictDataCommonApi）。

### basic-framework-spring-boot-starter-biz-data-permission

数据权限：`BasicFrameworkDataPermissionAutoConfiguration`（规则引擎）、
`BasicFrameworkDeptDataPermissionAutoConfiguration`（部门级规则，消费
PermissionCommonApi）。

### basic-framework-spring-boot-starter-biz-ip

IP 归属地解析工具（Area 数据）。纯工具接缝，无 auto-configuration。
`AreaConvert`（Excel 地区转换器，实现 fastexcel `Converter`，fastexcel
为 optional 依赖）归属于本 starter 的 `ip.core.convert` 包。

## 业务模块：basic-framework-module-crm（客户档案示例）

按 `docs/development-guide.md` 实现的业务模块，作为架构规约的业务侧参考。

- **职责**：客户档案 CRUD。`dal/dataobject/crm/CustomerDO`、
  `dal/mysql/crm/CustomerMapper`、`service/crm/CustomerService(Impl)`、
  `controller/admin/crm/CustomerController`；Service 单测
  `CustomerServiceImplTest` 10 例。
- **数据表**：`crm_customer`（审计字段、`idx_mobile`；不收集敏感身份数据）。
- **错误码**：独立段 `1_006_000_00x`（`enums/ErrorCodeConstants`，
  `ErrorCodeUniquenessTest` 覆盖不撞码）。
- **权限点**：`crm:customer:create/update/delete/query`；V6 迁移种入
  菜单/按钮/超管授权，与 Controller `@PreAuthorize` 一一对应。
- **字段契约**：`name`/`amount`/`contractDate` 已登记
  `docs/contracts/field-catalog.yaml`，mobile 复用共享规则；三端一致由
  check-field-catalog 门禁钉死。
- **前端**：`views/crm/customer/`（Grid/表单/规则中心复用）+ `api/crm`
  封装；页面/表单 schema 单测 5 例。

## 业务模块 api 包

module-system / module-infra 的对外契约（CommonApi 接口 + DTO）由
`basic-framework-module-system-api` / `basic-framework-module-infra-api`
薄子模块承载，包名与业务模块的 `api` 包一致；实现留在业务模块同名
包内，core starters 只依赖契约类型（ArchUnit 规则 C 对 api 包豁免，
见 ModuleBoundaryArchitectureTest）。

- system 侧（`basic-framework-module-system-api`）：Permission /
  MenuReference / UserSession / OperateLog / DictData
- infra 侧（`basic-framework-module-infra-api`）：ApiAccessLog /
  ApiErrorLog / CodegenReference

业务模块之间也只消费上述薄 API 契约，不得直接访问对方 Mapper、DO 或 ServiceImpl；
同步完整性校验必须加入调用方现有事务。V26 的代码生成父菜单引用是首个双向业务协作
实例，边界决策见 `docs/adr/0007-business-module-api-boundaries.md`。

## 装配与版本

- `basic-framework-dependencies`（BOM）统一管理全部依赖版本；新增
  依赖必须先入 BOM，业务 pom 不写版本号。
- `basic-framework-server` 是唯一应用入口（空壳装配），直接依赖两个
  业务模块与需要的 starters；运行配置集中在
  `basic-framework-server/src/main/resources/application*.yaml`。
