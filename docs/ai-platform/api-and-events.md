# API、SPI 与事件设计

## API 面划分

| API 面 | 路径 | 身份 | 用途 |
| --- | --- | --- | --- |
| 管理 API | `/admin-api/ai/**` 等 | 现有用户 Session + RBAC + 必要 MFA | 配置、调试、发布、审计和运维 |
| 开放 API | `/open-api/v1/**` | 机器 API Key/后续可扩展 OAuth2 Client Credentials | 业务系统调用已发布能力 |
| 内部组件 API | 私网地址 | 独立服务凭证/mTLS 候选 | 网关、检索、模型和 Worker |
| 运行探针 | 受控健康路径 | 运维边界 | readiness、liveness 和依赖状态 |

管理 API 与开放 API 使用相同 HTTP 状态语义，但 DTO、认证和兼容周期独立。开放 API 返回稳定、
英文机器错误码和可本地化 message；不暴露 Java 类名、SQL、堆栈、供应商密钥或内部组件地址。

## 开放 API 资源草案

```text
POST   /open-api/v1/applications/{applicationId}/responses
POST   /open-api/v1/applications/{applicationId}/runs
GET    /open-api/v1/runs/{runId}
GET    /open-api/v1/runs/{runId}/events
POST   /open-api/v1/runs/{runId}/cancel
POST   /open-api/v1/runs/{runId}/human-decisions

POST   /open-api/v1/knowledge-bases/{knowledgeBaseId}/search

POST   /open-api/v1/reports/{reportId}/runs
GET    /open-api/v1/report-runs/{reportRunId}
GET    /open-api/v1/report-runs/{reportRunId}/artifacts
GET    /open-api/v1/artifacts/{artifactId}/content
```

开放 API 不提供任意 Agent 草稿执行、任意模型代理、任意 SQL、任意 MCP 调用或管理配置读取。
业务系统只能调用已经发布且显式授予 Scope 的应用、知识检索和报表。

### 同步流式响应

`POST .../responses` 使用 SSE 时，事件类型保持平台语义：

```text
response.created
response.output_text.delta
response.tool.started
response.tool.completed
response.requires_action
response.completed
response.failed
```

SSE 的 `id` 是单次 Run 内单调事件序号。客户端携带 `Last-Event-ID` 重连时，平台从持久事件或
最终状态恢复；无法恢复的临时增量明确返回新的快照事件，不伪造遗漏增量。

### 异步运行

创建成功返回 `202 Accepted` 和 Run 资源位置。任务状态使用稳定 code，轮询支持 `ETag` 或
`updatedAfter`，避免高频传输完整 Step。取消是命令，不用通用 PATCH status。

### 分页

- 管理后台小型配置列表沿用 pageNo/pageSize，最大 200。
- 开放 API 的运行、事件和制品列表默认 cursor 分页，cursor 不透明且带完整性保护。
- 排序字段逐接口 allowlist；不把数据库列名作为公共契约。

## 机器认证

- API Key 至少 256 bit 随机熵，格式包含环境/用途前缀和随机正文。
- 客户端提交 `Authorization: Bearer <api-key>`；不在 query、Cookie 或 URL 传递。
- 数据库存摘要、前缀、创建者、过期、最后使用和撤销状态，原文只显示一次。
- Scope 至少约束 application/report/knowledge 资源与 invoke/read/cancel/decision 动作。
- 每个请求先认证，再鉴权资源，再限流；任一依赖不可用时高风险命令 fail closed。
- 轮换允许短期双 Key，有明确截止时间；撤销立即生效并审计。

API Key 是 L4。请求日志、Trace、指标标签、异常响应和模型上下文都不得包含其正文。

## 幂等

以下命令要求 `Idempotency-Key`：

- 创建异步 Agent Run；
- 创建报表 Run；
- 提交人工决策；
- 触发有外部副作用的已发布应用。

幂等记录键为客户端、操作和 key 的组合，同时保存规范化请求 hash：

- 相同 key + 相同 hash：返回原结果；
- 相同 key + 不同 hash：`409 Conflict`；
- 首次请求仍执行中：返回同一 Run 与当前状态；
- 记录过期后：视为新请求，过期时间必须长于客户端承诺的最大重试窗口。

## 错误分类

| HTTP | 稳定错误类型 | 场景 |
| --- | --- | --- |
| 400 | `invalid_request` | JSON、格式或基础校验失败 |
| 401 | `invalid_client` | API Key 缺失、错误、过期或撤销 |
| 403 | `insufficient_scope` / `resource_forbidden` | 已认证但无资源权限 |
| 404 | `resource_not_found` | 不存在或不向调用方暴露 |
| 409 | `state_conflict` / `idempotency_conflict` | 状态或幂等冲突 |
| 422 | `semantic_validation_failed` | Workflow、参数或 Schema 语义不合法 |
| 429 | `rate_limit_exceeded` | 客户端或应用配额超限 |
| 502 | `upstream_failed` | 模型、MCP 或外部工具确定性失败 |
| 503 | `temporarily_unavailable` | 暂时过载、依赖不可用或熔断 |
| 504 | `upstream_timeout` | 模型、工具或查询超时 |

响应包含平台 `requestId`。供应商原始错误进入受清理的内部诊断记录，不透传给业务系统。

## 内部 SPI

### AgentRuntime

```text
start(plan, context) -> RunHandle + Publisher<RunEvent>
resume(runRef, decision, context) -> Publisher<RunEvent>
cancel(runRef, reason, context) -> CancelResult
capabilities() -> RuntimeCapabilities
health() -> ComponentHealth
```

`AgentExecutionPlan` 由 module-ai 在运行前解析，包含不可变版本引用、规范化工具 Schema、模型路由、
检索策略、Harness、输出 Schema 和关联上下文。Runtime 不回查 AI 业务表来获取“最新配置”。

### ModelGateway

```text
chat(request, context) -> ChatResult
streamChat(request, context) -> Publisher<ModelEvent>
embed(request, context) -> EmbeddingResult
rerank(request, context) -> RerankResult
testConnection(endpointSpec) -> ConnectionTestResult
health() -> ComponentHealth
```

错误统一为认证、限流、超时、内容策略、请求无效、模型不存在、网关故障和未知上游，不以供应商
HTTP 文案作为业务分支。

### VectorSearch

```text
upsert(indexVersion, chunks)
delete(indexVersion, chunkIds)
search(indexVersion, query, filters, limit) -> candidates
activate(knowledgeBaseId, indexVersion)
health()
```

过滤表达式使用平台 AST，不接受页面或 Agent 传入供应商 DSL。

### DataQuery

```text
compile(semanticModelVersion, queryPlan, identity) -> ValidatedQuery
execute(validatedQuery, limits) -> DatasetSnapshot
explain(validatedQuery) -> QueryCostEstimate
```

`ValidatedQuery` 不对开放 API 暴露，且带一次性策略摘要，防止检查后替换 SQL。

### ReportRenderer

```text
renderHtml(templateVersion, datasetSnapshot, narrative, chartSpecs) -> ArtifactRef
renderPdf(htmlArtifactRef, renderPolicy) -> ArtifactRef
```

## 可靠事件

事件进入 Redis Stream，沿用现有 at-least-once、有限重试、DLQ、幂等回放和处置审计契约。

| 事件 | 生产方 | 消费方 |
| --- | --- | --- |
| `AiRunRequested` | AI API/任务 | Run Worker |
| `AiRunStateChanged` | Run Service | 统计、回调、页面投影 |
| `ModelCallRecorded` | Model Gateway Listener | 小时统计、告警 |
| `KnowledgeDocumentVersionCreated` | Knowledge Service | 索引 Worker |
| `KnowledgeIndexActivated` | Knowledge Worker | 缓存失效、审计 |
| `ReportRunRequested` | Report API/Job | Report Worker |
| `ReportArtifactCreated` | Report Service | 发布与回调 |
| `WebhookDeliveryRequested` | 各领域 | Webhook Worker |

事件信封至少包含：

```text
eventId
eventType
eventVersion
occurredAt
messageId
requestId
traceId
actorType/actorId
aggregateType/aggregateId
payload
```

事件版本只做向后兼容扩展。破坏性变化发布新 eventType/version，并在 N/N-1 消费窗口内双写或适配。

## Webhook

- 回调地址创建和变更是高风险操作，必须专用权限、MFA、SSRF 检查和审计。
- 每次投递包含事件 ID、时间戳和 HMAC 签名；签名覆盖原始请求体与时间戳。
- 接收方重复获得同一 eventId 时应幂等；平台有限重试后进入可诊断失败状态。
- 禁止跟随跨域重定向；响应体只读取有限字节，正文按敏感清理后保存摘要。
- 私网、环回、链路本地、云元数据和部署禁止网段默认拒绝，DNS 重绑定在连接时再次校验目标 IP。
