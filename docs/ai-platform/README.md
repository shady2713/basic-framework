# 企业 Agent 中台 V1 设计

本目录描述目标产品，不表示对应代码已经存在。当前已实现能力仍以根 `README.md`、
`docs/capability-catalog.md`、现有迁移和测试为准。

## 目标

在现有单租户企业基础框架上建设一套面向私有化客户的 Agent 中台。统一 Vue 管理端承载
全部产品交互；Java 应用拥有产品控制面、领域数据、权限、审计和对外协议；Agent 运行时、
模型网关、向量检索和私有模型作为可替换能力接入。

第一版对外发布必须覆盖完整一级能力，内部研发里程碑只用于控制依赖和验收顺序，不改变
最终首版范围。

## 文档地图

| 文档 | 唯一职责 |
| --- | --- |
| [`v1-scope.md`](v1-scope.md) | 首版功能、角色、验收和非目标 |
| [`architecture.md`](architecture.md) | 逻辑架构、部署拓扑与关键执行链路 |
| [`module-design.md`](module-design.md) | 后端模块、前端路由、能力接缝与依赖方向 |
| [`data-model.md`](data-model.md) | 核心实体、拟建表、状态与保留策略 |
| [`api-and-events.md`](api-and-events.md) | 管理 API、开放 API、SPI、事件和幂等契约 |
| [`security-and-deployment.md`](security-and-deployment.md) | 信任边界、安全不变量、私有化和恢复设计 |
| [`delivery-plan.md`](delivery-plan.md) | 内部实施顺序、质量门禁与首版退出条件 |

## 已确定决策

- 单客户单实例，不建设多租户。
- 现有 Java/Vue 框架是产品基座，全部客户页面集成到现有管理端。
- Spring AI Alibaba 是首个 Agent 运行时候选，不是产品控制面。
- 允许额外部署后端组件，但第三方 UI 不进入客户产品。
- 第一版包含模型统一接入、Token/成本/质量统计、知识库、数据接入、MCP、报表和开放 API。
- 模型不能自由执行 SQL、HTML、Shell 或外部网络请求；所有副作用必须通过受控工具边界。

## 待 POC 决策

| 决策 | 候选 | 选择标准 |
| --- | --- | --- |
| Agent 运行时版本 | Spring AI Alibaba 1.1 稳定线的受控构建；AgentScope Java 作为对照 | 恢复、流式、工具、HITL、升级与依赖安全 |
| 模型网关 | Higress；LiteLLM Proxy | 私有部署、动态配置、路由、用量事件、故障语义与许可证 |
| 混合检索 | OpenSearch；Milvus + 独立全文检索 | 中文全文、向量、过滤、备份、资源占用与运维复杂度 |
| 文档解析 | Java 解析链；隔离 Python Worker | 格式覆盖、解析质量、资源隔离、离线镜像与许可证 |
| 报表 PDF | 浏览器渲染 Worker；暂只交付 HTML | 中文字体、图表、分页、资源隔离和确定性 |

候选组件不得直接决定领域表结构。POC 输出必须包含版本、许可证、SBOM、失败语义、资源基线、
升级路径和可替换验证。
