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

## 数据权限分类契约

`data-permission-exemptions.json` 只登记不适用部门/本人行级过滤的应用表、具体理由、
替代控制机制及生产源码证据；
受保护表以各模块 `DeptDataPermissionRuleCustomizer` 的显式运行时登记为事实源，平台托管表
沿用 `data-lifecycle.json` 的分类。`node scripts/check-data-permission.mjs` 核对最终 Flyway
schema、数据库快照、运行时表/列登记和豁免台账，保证每张应用表必须“受保护”或“显式豁免”
且不能同时属于两类。每张豁免表必须由 `controls` 覆盖，并拥有通过 `table` 显式绑定的独立
生产源码证据；不能用同组中另一张表的证据兜底。证据文件缺失或约定片段漂移都会阻断。
新增表未完成该安全决策时，contracts gate 默认阻断。

## 覆盖率棘轮契约

`coverage-baseline.json` 保存 JaCoCo 与 Vitest 已采集源文件的单文件行覆盖率下限。
后端、前端 Harness 在测试完成后分别执行
`node scripts/check-coverage-ratchet.mjs backend|frontend`，阻断报告缺失、单文件覆盖率下降、
基线被下调以及未登记文件。已有基线只能持平或上调；新增源码至少达到 80%。

后端任一含生产源码的 Maven 模块未产出 JaCoCo 报告时，其全部非 `package-info.java`
源码按 0% 纳入检查，不能再因“整模块没有报告”而被忽略，也不存在源码豁免清单。
独立 `*-api` 薄契约模块同样必须运行测试并生成报告；默认方法、显式构造器等可执行契约逻辑
按实测值进入正常 `backend` 基线，纯抽象声明和被 JaCoCo 过滤的生成代码自然不产生可覆盖行。

测试确实增加覆盖率后，运行完整后端和前端覆盖率测试，再执行
`node scripts/check-coverage-ratchet.mjs --update`。更新命令同样校验 Git 基线，不能用来绕过回退。
该脚本位于仓库根目录；显式 `any` 报告脚本位于
`前端代码/basic-framework-admin/scripts/`，应在此前端目录执行。
前端只排除测试、声明文件、生成代码、入口装配和无 `<script>` 行为的纯 SVG 图稿；
带业务行为的 Vue 组件仍全部进入覆盖率统计。

普通 `.ts` 文件只有在 TypeScript 编译后完全没有 JavaScript 时才能排除。精确清单位于
`frontend-coverage-exclusions.json`，前端测试逐文件执行编译验证；文件一旦出现运行时代码，
门禁立即失败，不能通过目录通配符绕开覆盖率。

## 字段注入棘轮契约

`field-injection-baseline.json` 保存后端生产代码中 `@Resource`、`@Autowired`、`@Inject` 和字段级
`@Value` 注解总数的只降不升上限。`node scripts/check-field-injection.mjs` 忽略注释与字符串字面量，
扫描全部 `src/main/java`；当前总量超过上限、人为上调上限、任一现有文件数量增加，
或新文件出现注入注解都会阻断 contracts gate。新增代码使用构造器注入；改造旧代码后
同步降低上限，直到归零。

## 源码质量契约

`node scripts/check-source-quality.mjs` 扫描 Git 已跟踪及未忽略的新源码，阻断超过 800 行的
Java、JavaScript、TypeScript、Vue、PowerShell 和 Shell 逻辑文件，并要求独立注释行中的
`TODO`、`FIXME`、`XXX` 在同一行携带负责人或问题引用。无 `<script>` 的纯 SVG Vue 图稿按
静态资产处理，不以图形路径数量冒充逻辑复杂度。脚本自身测试与实际扫描同时接入 Windows、
Linux 的 contracts gate，避免规则只停留在文档。

## 显式 any 棘轮契约

`explicit-any-baseline.json` 保存 ESLint `@typescript-eslint/no-explicit-any` 命中的只降不升上限。
前端 `pnpm check` 扫描全部 TypeScript 与 Vue 源文件；当前总量超过上限、人为上调上限、
任一相对 Git 基线的现有文件数量增加，或新文件引入显式 `any` 都会阻断 frontend gate。
修复旧类型债务后同步降低上限，最终归零后再将 ESLint 规则提升为全局 error。
在 `前端代码/basic-framework-admin` 目录执行
`node scripts/check-explicit-any.mjs --report` 可按命中数量降序查看文件级债务分布，
用于选择下一批治理目标；该只读模式不替代阻塞门禁。

## MyBatis Mapper 接口与 0% 基线政策

按"无 H2/BaseDbUnitTest 层"的仓库政策，MyBatis-Plus Mapper 接口由框架代理在真实
JDBC 会话中执行，单元测试无法驱动其字节码，因此 `*Mapper.java` 接口保持 0% 单文件
基线属设计政策而非未清偿债务；其行为由 service 层测试的 mock 契约与
`-Pintegration` 下的 Testcontainers 集成测试共同钉住，不增设豁免清单（棘轮版本 3
不允许豁免）。新增 Mapper 时可并入既有集成测试验证。其余 0% 文件按清偿时间表逐 PR
回填，优先覆盖改动文件、配置类、API 实现与任务类。

## 覆盖率达 0% 存量债务的清偿时间表

基线生成时部分存量文件的单文件下限为 0%（棘轮机制见上一节）。清偿规则：

- 增量债务零容忍：新文件低于 80% 或触碰已登记文件导致下降，门禁直接失败。
- 清理顺序按治理杠杆排列：PR 实际改动的文件优先（触及即按 ≥80% 新文件规则立账）、
  纯死代码先行删除、核心契约（构造器注入、限流、会话）相关文件次之、页面级 VO 最后。
- 每个被清理的文件在 PR 中随 `coverage-baseline.json` 上调而结清；单次 PR 上调不低于
  5% 的存量文件即视为本周期偿债目标达成，运维复盘以此统计。

## 前端覆盖率排除契约

`frontend-coverage-exclusions.json` 只允许排除编译后无运行时 JS 的纯类型文件；普通 `.ts` 文件
只有在 TypeScript 编译后完全没有 JavaScript 时才能排除。精确清单位于
`frontend-coverage-exclusions.json`，前端测试逐文件执行编译验证；文件一旦出现运行时代码，
门禁立即失败，不能通过目录通配符绕开覆盖率。
