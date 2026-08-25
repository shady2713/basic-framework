# 核心数据模型设计

本文件是设计清单，不是迁移台账。实施时每张最终表必须经过字段目录、数据分级、删除策略、
索引、物理/逻辑关系和 Flyway 设计；不得直接按本文件批量建表。

## 通用规则

- 主键沿用平台 Long ID 策略；开放 API 把 ID 当不透明字符串，不承诺数值语义。
- 状态使用稳定 code，不使用数据库 ordinal。
- 草稿对象可以更新；已发布版本不可变，修改产生新版本。
- 运行记录引用具体版本 ID，不只保存“当前版本”。
- L4 凭证只保存 `CredentialCipher` 密文或不可逆摘要，不出现在通用配置表。
- 大正文、原文件、模型原始流和 HTML 制品进入文件服务；MySQL 保存引用、摘要和必要索引字段。
- JSON 字段必须有 Schema 版本、大小上限和兼容策略，不能代替关系建模与可查询字段。
- 逻辑跨模块引用由拥有方 API 校验并登记孤儿审计，不直接形成 Maven/领域耦合。

## AI 与模型

### 模型目录

| 拟建表 | 作用 | 关键约束 |
| --- | --- | --- |
| `ai_model_provider` | 供应商与协议类型 | code 唯一；不保存密钥明文 |
| `ai_model_endpoint` | 实际地址和凭证引用 | URL 受出站策略；凭证密文；启停与健康状态分开 |
| `ai_model` | 平台模型目录 | Chat/Embedding/Rerank 类型不可混用 |
| `ai_model_deployment` | 模型与端点的可调用部署 | 上下文、能力、并发和实际模型名 |
| `ai_model_route` | 应用可引用的路由 | 主备、超时、重试、降级与能力要求版本化 |
| `ai_model_price_version` | 价格事实 | 生效区间不重叠；历史禁止改写 |

模型路由引用平台 deployment ID；外部网关内部 ID 只作为 Provider 配置，不进入公共 API。

### 模型调用与统计

| 拟建表 | 作用 | 保留建议 |
| --- | --- | --- |
| `ai_model_call` | 每次实际模型尝试 | 追加保留；按月分区或归档 |
| `ai_model_usage_hourly` | 小时聚合 | 可由明细重建 |
| `ai_model_usage_daily` | 日聚合 | 可由小时/明细重建 |

`ai_model_call` 至少包含：

- `request_id`、`trace_id`、`run_id`、`session_id`；
- `business_client_id`、`application_id`、`agent_version_id`、`user_id`；
- 计划 route/deployment 与实际 provider/endpoint/model；
- 尝试序号、是否降级、状态、标准错误类型和供应商请求 ID；
- 请求开始、首 Token、结束时间与三个耗时；
- input/output/cache-read/cache-write/reasoning Token；
- usage 来源（供应商、网关、估算）、价格版本、币种和确定性成本；
- Prompt/Completion 内容引用或摘要，默认不保存 L3/L4 正文。

成本使用整数最小货币单位或高精度 Decimal，禁止 double。价格规则处理未知 Token 类型时明确标记
`UNPRICED`，不能按零成本静默通过。

## 应用、Agent 与 Workflow

| 拟建表 | 作用 |
| --- | --- |
| `ai_application` | 对外可发布的 AI 应用 |
| `ai_application_version` | 应用入口、Agent/Workflow 和开放策略快照 |
| `ai_agent` | Agent 逻辑身份和草稿元数据 |
| `ai_agent_version` | 不可变 AgentManifest |
| `ai_workflow` | Workflow 逻辑身份 |
| `ai_workflow_version` | 不可变 GraphSpec |
| `ai_prompt` / `ai_prompt_version` | Prompt 逻辑身份与正文版本 |
| `ai_skill` / `ai_skill_version` | Skill 元数据与内容版本 |
| `ai_harness_policy` / `ai_harness_policy_version` | 运行限制与审批策略版本 |
| `ai_agent_binding` | Agent 版本绑定的模型、知识、工具和 Skill 快照 |

`AgentManifest` 至少快照：指令、模型路由、Prompt、Skill、工具 Schema、知识库检索配置、记忆策略、
Harness、输出 Schema 和兼容版本。运行时不得通过“当前配置”重新解析历史版本。

Workflow 的节点和边以有版本的 GraphSpec 保存，同时将名称、类型、入口和发布状态放在可查询列。
GraphSpec 发布前验证无孤儿节点、可达结束节点、并行合并策略、循环上限、输入输出 Schema 和引用版本。

## 工具与 MCP

| 拟建表 | 作用 |
| --- | --- |
| `ai_tool` | 工具逻辑身份、种类和所有权 |
| `ai_tool_version` | 输入输出 Schema、权限、副作用、超时和实现引用 |
| `ai_mcp_server` | MCP 地址、凭证引用、出站策略和状态 |
| `ai_mcp_tool_snapshot` | 一次发现的远端工具 Schema 快照 |
| `ai_tool_call` | 工具调用事实与审计关联 |

MCP 运行绑定已批准 snapshot。远端工具 Schema 改变后进入 `DRIFTED`，必须重新测试和发布，不自动
替换生产 Agent 绑定。

## Session、Memory、Run 与 Workspace

| 拟建表 | 作用 | 生命周期 |
| --- | --- | --- |
| `ai_session` | 会话身份、参与者、应用版本和状态 | 按应用策略关闭/保留 |
| `ai_message` | 有序消息和内容引用 | 追加；更正使用新事件 |
| `ai_memory_item` | 经策略提取的长期记忆 | 可审阅、撤销、过期 |
| `ai_run` | 一次 Agent/Workflow 执行 | 追加状态机 |
| `ai_run_step` | 节点、模型、工具、检索、审批步骤 | 追加；支持父子步骤 |
| `ai_checkpoint_ref` | 运行时检查点稳定引用 | 与 Run 保留策略一致 |
| `ai_workspace` | 运行隔离空间 | 运行结束后按策略清理 |
| `ai_workspace_artifact` | 文件服务对象引用、用途与分级 | 按制品策略 |
| `ai_human_task` | 中断审批任务与决策 | 审计保留 |

Run 状态至少包括 `QUEUED/RUNNING/WAITING_HUMAN/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT`。
状态变化走显式命令和乐观锁，禁止通用 status 更新接口。

`ai_message` 和 `ai_run_step` 使用会话/运行内单调序号唯一约束。流式增量可以进入短期事件存储，
最终消息落库必须幂等，避免重连产生重复正文。

## 评估

| 拟建表 | 作用 |
| --- | --- |
| `ai_eval_dataset` / `ai_eval_dataset_version` | 不可变测试集版本 |
| `ai_evaluator` / `ai_evaluator_version` | 规则、代码、Judge 或人工评估定义 |
| `ai_eval_experiment` | 被测应用/Agent版本和评估组合 |
| `ai_eval_run` | 实验执行状态和汇总 |
| `ai_eval_result` | 单样本、单评估器结果和证据引用 |

Judge 模型调用同样进入 `ai_model_call`，成本归属评估实验，不能从模型统计中遗漏。

## 知识

| 拟建表 | 作用 |
| --- | --- |
| `knowledge_base` | 知识库身份、权限和活动检索配置 |
| `knowledge_source` | 文件、目录或受控同步来源 |
| `knowledge_document` | 文档逻辑身份和业务元数据 |
| `knowledge_document_version` | 原文件引用、摘要、解析器和状态 |
| `knowledge_chunk` | Chunk 文本引用、位置、元数据和内容摘要 |
| `knowledge_index_version` | 一次完整可切换索引版本 |
| `knowledge_index_job` | 解析、切片、Embedding、写索引任务 |
| `knowledge_retrieval_log` | 查询、过滤、候选、重排和引用事实 |

Chunk 主键和索引文档 ID 使用稳定平台 ID。检索服务中的向量可重建；原文件、文档版本、Chunk 内容
引用、解析配置和活动索引版本必须备份。

## 数据与语义层

| 拟建表 | 作用 |
| --- | --- |
| `data_source` | 数据库类型、地址、只读凭证和策略 |
| `data_metadata_snapshot` | 一次元数据发现版本 |
| `data_object` | 允许的 Schema/View/Table |
| `data_field` | 字段类型、语义、枚举、敏感级别和可查询性 |
| `data_relation` | 已审核对象关联 |
| `data_semantic_model` / `data_semantic_model_version` | 可发布语义模型 |
| `data_metric` / `data_metric_version` | 指标口径、维度、过滤和单位 |
| `data_query_policy` | 行数、超时、对象、函数和敏感规则 |
| `data_query_audit` | QueryPlan、SQL 摘要、策略决策和执行结果 |

数据源凭证是 L4；普通查询只返回“已配置”。SQL 正文可能包含业务敏感常量，默认按 L3 处理，
管理列表展示归一化摘要，完整正文仅在专用审计权限下按保留策略访问。

## 报表

| 拟建表 | 作用 |
| --- | --- |
| `report_definition` | 报表逻辑身份和用途 |
| `report_version` | 数据、指标、Agent、模板、图表和发布策略快照 |
| `report_schedule` | 对现有 infra Job 的业务配置和逻辑引用 |
| `report_run` | 一次生成、审核、发布和回调状态 |
| `report_dataset_snapshot` | 确定性输入数据、摘要和来源版本 |
| `report_artifact` | HTML/PDF/JSON 制品的 infra 文件引用 |
| `report_delivery` | 下载、回调或嵌入发布结果 |

数据快照正文可以进入文件服务，表内保存 hash、Schema 版本、记录数、敏感级别和文件引用。模型叙述、
模板渲染和发布分别记录状态，便于仅重试失败阶段。

## 开放 API 客户端

| 拟建表 | 作用 |
| --- | --- |
| `ai_business_client` | 接入业务系统、状态、联系人和允许来源 |
| `ai_business_credential` | API Key 前缀、摘要、过期、轮换和撤销 |
| `ai_business_scope` | 客户端对应用、动作和资源的授权 |
| `ai_idempotency_record` | 命令幂等键、请求摘要和结果引用 |
| `ai_webhook_endpoint` | 回调地址、密钥、事件和出站策略 |
| `ai_webhook_delivery` | 回调尝试、状态、下次重试和响应摘要 |

原始 API Key 只返回一次，MySQL 只存摘要和可识别前缀。Webhook 使用独立签名密钥，不能复用
业务系统调用 API Key。

## 删除与保留初稿

| 数据类别 | 初始策略 |
| --- | --- |
| 已发布定义版本 | 被引用时禁止删除；停用后长期保留 |
| 模型/工具/查询/运行审计 | 追加保留，默认至少 180 天；客户可收紧或延长 |
| 原始 Prompt/Completion | 默认不持久化正文；启用时单独目的、权限和期限 |
| 知识原文件与 Chunk | 软删后撤索引；按知识库策略硬删和审计 |
| Workspace 临时文件 | 默认运行完成后短期清理；正式制品转入长期引用 |
| 报表制品 | 按报表定义保留；删除需处理已发布链接 |
| 统计聚合 | 可重建；明细归档后保留日聚合 |
| 幂等记录 | 至少覆盖客户端最大重试窗口 |

具体天数、删除命令、物理关系和备份范围在实施迁移前进入 `docs/data-lifecycle.md` 与机器台账。
