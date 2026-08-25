# ADR 0007：业务模块通过薄 API 契约协作

- 状态：已接受

## 背景

根 `AGENTS.md` 要求业务模块不得访问另一模块的内部实现，跨模块调用只能通过拥有方的
`api` 包。`basic-framework-module-system-api` 与 `basic-framework-module-infra-api`
已经作为不持有实现的契约薄模块存在，但 ArchUnit 规则 A/B 曾把目标模块的 `api` 包也
一并禁止，导致规则与架构文字及薄模块用途不一致。

V26 需要闭环 `infra_codegen_table.parent_menu_id`：infra 写入必须由 system 判断菜单是否
可作为父菜单，system 删除或把菜单改为按钮时必须由 infra 判断是否仍有活动代码生成配置。
直接访问对方 Mapper、跨模块 SQL 或把业务接口下沉到 framework common 都会模糊所有权。

## 决策

1. 业务模块可以依赖另一业务模块的 `*-api` 薄模块，并且生产代码只能引用目标模块
   `..api..` 包中的公开契约和 DTO。
2. ArchUnit 规则 A/B 继续阻断所有目标模块非 `api` 包依赖；规则 C 对 framework 的既有
   api 豁免保持不变。
3. 双向协作拆成两个单向契约：system 拥有菜单可用性契约，infra 拥有代码生成引用查询契约。
   两个实现模块不直接形成 Maven 循环，契约模块也不持有实现。
4. 不使用通用事件绕过同步一致性要求；需要阻断删除或锁定父记录的校验必须在原事务中完成。

## 后果

- 模块所有权与编译依赖方向可由 ArchUnit 持续验证，新增跨模块能力无需访问实现内部。
- 双向业务关系会增加两个明确的 API 依赖；若未来关系被移除，契约、依赖和测试必须同时删除。
- 运行时需要由唯一 server 装配两个实现模块；缺少任一实现会在 Spring 启动时显式失败。
