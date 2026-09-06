# basic-framework

Spring Boot 3 / Java 17 后端 + Vue 3 / TypeScript 管理端的基础框架单体仓库。
工程规则的唯一权威来源是根 `AGENTS.md`；业务开发从 `docs/development-guide.md` 进入。

## 仓库布局

```
后端代码/basic-framework-boot/     Maven 多模块后端
  basic-framework-dependencies/    统一依赖版本（BOM）
  basic-framework-core/            starters = 能力接缝（目录见 docs/capability-catalog.md）
  basic-framework-module-system-api/  system 对外契约薄模块（CommonApi + DTO）
  basic-framework-module-system/   系统管理模块
  basic-framework-module-infra-api/   infra 对外契约薄模块（CommonApi + DTO）
  basic-framework-module-infra/    基础设施模块
  basic-framework-server/          启动装配（唯一应用入口）
前端代码/basic-framework-admin/    pnpm + turbo monorepo（主应用 apps/web-ele）
数据库文件/                        手工 SQL 快照（受控，见下文数据库节）
docs/                              契约、ADR、安全规范、开发指南
scripts/                           仓库级校验脚本（CI 直接调用）
.harness/                          DeepSeek Harness 工程模型的可执行门禁适配层
```

## 环境要求

- JDK 17（Maven 由 `./mvnw` 包裹，无需本机安装）
- Node.js >= 20.19，pnpm 10.28.2（`corepack enable` 自动对齐）
- MySQL 8、Redis 7（本地开发可用 Docker 起）
- Docker（`dependencies` 漏洞扫描和 `integration` 集成测试需要）

## 首次 bootstrap

```bash
# 1. 数据库：建库 basic_framework，导入快照（仅首次；之后由 Flyway 接管）
mysql -uroot -p < 数据库文件/basic_framework.sql

# 2. 后端：编译并跑通全部后端门禁
cd 后端代码/basic-framework-boot && ./mvnw -q verify

# 3. 前端：安装依赖并跑通全部前端门禁（check + lint + test:coverage，
# test:coverage 包含单元测试与覆盖率棘轮）
cd 前端代码/basic-framework-admin && corepack enable && pnpm install --frozen-lockfile
pnpm check && pnpm lint && pnpm test:coverage
```

## 本地 run

```bash
# 后端（默认 local profile，端口 48080；数据库/Redis 连接走 application-local.yaml）
# 先 install 让本地仓库拿到全部兄弟模块 jar，再在 server 模块启动
cd 后端代码/basic-framework-boot && ./mvnw -q install -DskipTests
./mvnw spring-boot:run -pl basic-framework-server

# 前端（Element Plus 主应用）
cd 前端代码/basic-framework-admin && pnpm dev:ele
```

容器化部署与运维处置（compose 编排、健康检查、Flyway 修复、备份恢复演练）见
`docs/deployment.md`。

## verify（提交前必跑，可直接映射为阻断 CI job）

Windows 本地与 Linux GitHub Actions 使用同一组 Harness 命名门禁。Windows 完整门禁执行
`& .\.harness\verify.ps1 all`；Linux 执行 `sh .harness/verify.sh all`。将 `all`
替换为 `--list` 可查看单独边界。

| 命令 | 门禁职责 |
|---|---|
| Harness `backend` | backend-build：编译、单测（JaCoCo 覆盖率棘轮）、Spotless、ArchUnit 模块边界（规则 A-D） |
| Harness `integration`（需 Docker） | backend-integration：Testcontainers MySQL 8/Redis 7、Flyway 空库迁移、真实 SQL，以及生产配置下 packaged jar 的 HTTP 健康探测 |
| Harness `frontend` | frontend-build：循环依赖、依赖完整性、typecheck、cspell、lint、vitest 覆盖率棘轮 |
| Harness `dependencies`（需 Docker） | dependency-scan：扫描 Maven 解析态 SBOM、pnpm 锁文件、容器配置和最终应用镜像，阻断 HIGH/CRITICAL；不需要 NVD API Key |
| Harness `contracts` | repo-contracts：字段目录、敏感对象 `toString`、数据生命周期和工程例外到期日漂移检查 |
| Harness `lockfile` | lockfile-integrity：冻结安装验证依赖图与已提交锁文件一致 |
| `bash scripts/doctor.sh`（仓库根） | 本地环境体检（非 CI 门禁）：JDK≥17/Node/pnpm/wrapper/锁文件五查，MySQL 缺失仅告警 |
| 提交时 lefthook 自动执行 | repo-hygiene：空白/换行、secret-scan、commitlint |

`.github/workflows/verify.yml` 将上述边界映射为阻断 job，并以 `aggregate` 作为唯一
聚合检查；GitHub 仓库仍需在 `main` 的 branch protection/ruleset 中把 `aggregate`
配置为合并必需检查。门禁职责和维护约束见 `.harness/README.md`，工程规则仍以根
`AGENTS.md` 为唯一权威来源。`backend-integration` 直接使用 GitHub 托管 Ubuntu
runner 的 Docker daemon；自托管 runner 必须使用 Linux、安装 Docker 并允许 runner
账号访问 daemon。Runner 能力不足属于 CI 环境失败，不得跳过集成门禁。

## 数据库纪律

- `数据库文件/basic_framework.sql` 是便于人工初始化/审阅的受控最新快照，结构变更时随新增 migration 同步；Flyway migration 才是运行时权威历史。
- 一切结构变更在 `basic-framework-server/src/main/resources/db/migration/` 新增 `V{n}__<说明>.sql`；已执行迁移永不修改。
- 本地/CI 启动时 Flyway 自动 `validate + migrate`（`baseline-on-migrate` 接管已导入快照的旧库）。
- 生产环境必须分别注入 `FLYWAY_USERNAME` / `FLYWAY_PASSWORD` 与运行时 `DB_USERNAME` / `DB_PASSWORD`：迁移账号持有 DDL 和 Flyway 所需元数据读取权限，应用账号只持有业务 DML 权限；两者相同会在启动期失败。

## 新增业务入口

新增实体/字段/模块的唯一流程入口是 `docs/development-guide.md`；字段契约登记在
`docs/contracts/field-catalog.yaml`（改动会被 CI 漂移检查拦截）。非平凡决策在
`docs/adr/` 新增一条记录。数据分级与威胁边界分别见
`docs/security/data-classification.md` 和 `docs/security/threat-model.md`。
