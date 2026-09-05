# 部署与运维 Runbook

> 定位：容器化部署与运维处置的唯一入口。配置事实不在此重复——环境变量与暴露面以
> `application-prod.yaml` 为准，账号分离前提见
> [ADR 0009](adr/0009-separate-flyway-database-principal.md)，RTO/RPO 声称见
> [ADR 0006](adr/0006-greenfield-product-operational-baseline.md)；本文只讲操作步骤。

## 1. compose 一键编排

编排文件：[`后端代码/basic-framework-boot/docker-compose.yaml`](../后端代码/basic-framework-boot/docker-compose.yaml)
（MySQL 8.4 + Redis 7 + 后端 prod profile）。外部镜像同时锁定补丁版本和 OCI 摘要；升级
镜像时必须显式更新摘要并通过容器镜像契约测试。秘密与域名全部经 `.env` 注入，模板见同目录
`.env.example`（占位值不可运行，必须全部替换；缺失变量时 compose 直接拒绝渲染）。

```bash
cd 后端代码/basic-framework-boot
# 1. 构建可执行 jar（镜像分层拷贝依赖 target/basic-framework-server.jar）
./mvnw -q clean package -DskipTests
# 2. 配置环境并替换全部占位值
cp .env.example .env
# 3. 构建镜像并启动（app 等待 MySQL/Redis 健康后启动）
docker compose up -d --build
# 4. 观察状态与日志
docker compose ps
docker compose logs -f app
```

MySQL 的迁移/应用双账号由 `script/mysql/init/` 在数据卷首次初始化时创建（授权范围与
packaged-jar 冒烟测试的验证集一致）；初始化后修改密码不生效，需 `docker compose down -v`
重建数据卷。生产平台部署时按 ADR 0009 自行 provisioning 同等权限的两组账号。

种子管理员口令：seed 数据携带一个固定的 BCrypt 哈希（`数据库文件/basic_framework.sql`
中 `system_users.admin`），仅为保证首个登录周期可用。该口令明文通过任何交付文档外泄后，
所有部署实例的初始口令即已知，因此**每次生产部署必须在首个登录后立即轮换管理员口令**，
并将轮换动作记入部署验收清单；遗留该哈希的实例不得接入公网。

客户端 IP 与可信代理：后端默认不采信 `X-Forwarded-For`/`X-Real-IP`（防伪造代理头绕过
限流与审计）。若部署在 Nginx/负载均衡之后，必须在 `application-prod.yaml` 把代理出口
IP 或网段配置到 `basic-framework.web.trusted-proxies`（如 `10.0.0.0/8`），否则按 IP
的限流与访问审计取到的是代理 IP；未配置时登录限流按代理出口聚合生效，不会比预期更宽松。

## 2. 健康检查与 actuator 暴露面

- 生产环境仅暴露 `GET /actuator/health`，其余端点一律不暴露：事实在
  [`application-prod.yaml`](../后端代码/basic-framework-boot/basic-framework-server/src/main/resources/application-prod.yaml)
  的 `management.endpoints.web` 段。
- 容器内 HEALTHCHECK 探测同一端点，实现见
  [`basic-framework-server/Dockerfile`](../后端代码/basic-framework-boot/basic-framework-server/Dockerfile)
  （非 root 运行、日志目录等事实同在该文件注释中）。
- 手动探测：`curl -fsS http://127.0.0.1:48080/actuator/health`。

## 3. Flyway 迁移失败处置

处置全程使用 Flyway 迁移账号（`FLYWAY_USERNAME`），严禁使用应用账号（仅 DML，执行修复
会失败）或 root（违反 ADR 0009 的最小授权前提）。

1. 定位：app 启动失败且日志含 Flyway 错误；`flyway_schema_history` 中 `success=0` 的记录
   即失败迁移。
2. 恢复 schema 一致性：失败迁移已部分执行的，用迁移账号手工将 schema 修到目标状态。已执行
   的迁移文件永不修改（ADR 0006 发布边界），只允许修复数据库后重新对齐元数据。
3. 执行 repair（清除失败标记、对齐 checksum），任一含 MySQL 支持的 Flyway 11 CLI 均可：

   ```bash
   docker run --rm --network basic-framework_default --env-file .env \
     --entrypoint /bin/sh \
     flyway/flyway:11 \
     -c 'exec /flyway/flyway -url="jdbc:mysql://mysql:3306/basic_framework" \
       -user="$FLYWAY_USERNAME" -password="$FLYWAY_PASSWORD" repair'
   ```

   `--env-file .env` 将凭据直接注入临时容器，单引号中的变量只在容器内展开；不依赖宿主机
   是否导出 `.env`，也不会把密码展开进宿主机命令参数。

4. 重启验证：`docker compose up -d app`，确认启动日志中 Flyway validate 通过、健康检查转绿。
5. 若 schema 损坏无法手工修复，按第 4 节从备份恢复。

## 4. 备份与恢复演练（RTO/RPO 的验证载体）

ADR 0006 声称 RTO 4 小时、RPO 1 小时，本节演练即其验收方式。演练对象是 MySQL 业务数据；
Redis 仅承载缓存与会话，丢失后用户重新登录即可，不纳入恢复目标。

备份（RPO 落点）：

```bash
cd 后端代码/basic-framework-boot
docker compose exec mysql sh -c \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers basic_framework' \
  > backup-"$(date +%F-%H%M)".sql
```

生产环境应将同一命令挂到平台备份任务，备份间隔不长于 1 小时（对齐 RPO）。

恢复演练（验收 RTO，恢复到独立库，避免覆盖在运数据）：

```bash
# 1. 建恢复库并导入备份
docker compose exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE basic_framework_restore"'
docker compose exec -T mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" basic_framework_restore' < backup-<时间戳>.sql
# 2. 抽验：表数量与核心业务表行数
docker compose exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = \"basic_framework_restore\"; SELECT COUNT(*) FROM basic_framework_restore.system_users;"'
# 3. 记录端到端恢复耗时并对照 RTO 4 小时；通过后清理恢复库与演练备份
docker compose exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE basic_framework_restore"'
```

演练结论（耗时、抽验结果、处置人）留档，作为 ADR 0006 恢复目标的持续验收证据。

## 5. 前端镜像

前端镜像构建事实在前端仓库自带文件中，不在此复制：

- [`前端代码/basic-framework-admin/scripts/deploy/Dockerfile`](../前端代码/basic-framework-admin/scripts/deploy/Dockerfile)：node:22-slim 构建 + nginx 运行，暴露 8080。
- [`前端代码/basic-framework-admin/scripts/deploy/build-local-docker-image.sh`](../前端代码/basic-framework-admin/scripts/deploy/build-local-docker-image.sh)：本地一键构建脚本。
