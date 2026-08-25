# basic-framework

basic-framework 是一个基于 Spring Boot 3 / Java 17 的多模块后端基础脚手架，包含系统管理与基础设施模块，可作为内部业务系统的后端起点。

## 模块说明

- `basic-framework-dependencies`：统一依赖版本管理
- `basic-framework-core`：公共能力与 Spring Boot Starter
- `basic-framework-server`：应用启动模块
- `basic-framework-module-system`：系统管理模块
- `basic-framework-module-infra`：基础设施模块

## 运行要求

- JDK 17+
- 使用仓库内 Maven Wrapper（所有构建命令只运行 `./mvnw`）
- MySQL 8.x（或按配置切换到其它数据库）
- Redis

## 本地启动

1. 修改 `basic-framework-server/src/main/resources/application-local.yaml` 中的数据源与中间件配置。
2. 准备业务所需的数据库结构与初始化数据。
3. 执行 `./mvnw -q verify` 验证工程。
4. 运行 `basic-framework-server` 模块中的 `BasicFrameworkServerApplication` 启动项目。

## MFA 配置

生产 profile 强制启用 MFA，并要求通过 `CREDENTIAL_ENCRYPTION_KEY` 提供 Base64 编码的 32 字节随机主密钥。该密钥统一保护 MFA 秘密、短信渠道密钥和文件存储凭据；缺失或长度错误会阻断启动。可使用 `openssl rand -base64 32` 生成，密钥只进入部署环境的 Secret 管理，不写入配置文件或日志。

管理端只提供内部用户会话，不开放外部客户端授权协议。访问令牌与刷新令牌的默认有效期分别为 30 分钟和 30 天，可通过 `SESSION_ACCESS_TOKEN_TTL`、`SESSION_REFRESH_TOKEN_TTL` 调整；两者必须为正数，且刷新令牌有效期不得短于访问令牌。刷新使用一次性令牌轮换和数据库原子更新，不延长刷新令牌的绝对到期时间。

可选配置为 `MFA_ISSUER`（认证器展示名称，默认 `basic-framework`）、`MFA_CHALLENGE_TTL`（一次性登录挑战有效期，默认 `5m`）和 `MFA_STEP_UP_TTL`（当前 access token 完成二次验证后的高风险操作窗口，默认 `5m`）。生产环境还必须配置 `MFA_WEBAUTHN_RP_ID` 与 `MFA_WEBAUTHN_ALLOWED_ORIGIN`；Origin 必须是 RP ID 范围内的精确 HTTPS Origin。本地开发可通过 `MFA_WEBAUTHN_ENABLED=true`、`MFA_WEBAUTHN_RP_ID=localhost` 和实际前端 Origin 启用浏览器安全密钥。

## 说明

- 仓库中的初始化 SQL 与上游演示资料已移除。
- Docker、脚本与配置均已切换为 `basic-framework` 命名。
