# 机器可读契约

本目录只保存由阻塞门禁消费的当前契约，不保存实施过程或静态扫描快照。

## 字段契约

`field-catalog.yaml` 是跨数据库、后端和前端的字段语义事实源，登记长度、格式、
敏感级别、实现位置和共享测试向量。`status: aligned` 表示实现与契约一致；
`status: drift` 必须在同一条目中给出明确 `target`，不得用目标值冒充代码现状。

`node scripts/check-field-catalog.mjs` 阻断以下漂移：

1. 目录结构、边界和测试向量非法；
2. 前端共享正则或显式登记文本与目录不一致；
3. 后端共享正则、`@Size` 或显式登记文本与目录不一致；
4. 契约长度超过数据库列宽。

新增或修改字段时，先更新目录，再同步数据库、双端实现与测试，最后运行门禁。
字段的数据处理要求以 `docs/security/data-classification.md` 为准。

## 数据生命周期契约

`data-lifecycle.json` 为每张最终表登记删除策略，并登记最终 schema 的全部物理外键。
`node scripts/check-data-lifecycle.mjs` 对 Flyway 最终结构、手工快照和契约做一致性检查。
策略语义及新增关系的选择规则见 `docs/data-lifecycle.md`。
