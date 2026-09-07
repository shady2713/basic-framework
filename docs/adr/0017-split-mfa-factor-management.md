# ADR 0017：按安全职责拆分 MFA 服务

> 注：本 ADR 中与 WebAuthn 相关的组件与路径已被 ADR 0029 删除取代；TOTP、恢复码
> 与拆分结构仍然有效。

## 背景

MFA 因子管理和登录实现同时负责一次性挑战、TOTP、WebAuthn、恢复码、二次验证与因子生命周期。两个实现分别需要八个和十一个生产依赖，既不符合构造器参数上限，也使挑战归属、密码学材料、会话绑定和事务审计边界难以独立审查。

## 决策

入口服务只保留事务与操作审计编排，内部按安全职责拆分：

- `MfaChallengeManager` 统一生成高熵令牌，并以原子读取删除方式校验挑战用途和用户归属。
- `MfaTotpEnrollmentManager` 负责 TOTP 密钥加密、首次注册和绑定因子的轮换。
- `MfaWebAuthnEnrollmentManager` 负责注册 ceremony、用户验证结果和凭据尺寸约束。
- `MfaFactorLifecycleManager` 负责安全摘要、最后因子保护以及恢复码生命周期。
- `MfaMethodPolicy` 统一部署开关、超级管理员强制策略和认证方式顺序。
- `MfaRequiredEnrollmentFlow`、`MfaLoginFlow` 与 `MfaStepUpFlow` 分别编排首次注册、登录认证和会话提权。
- `MfaCredentialVerifier` 统一执行 TOTP 防重放、恢复码原子消费和 WebAuthn 用户及签名计数绑定。
- `MfaAuthenticationAudit` 只记录认证失败元数据，禁止记录验证码、恢复码和 WebAuthn 响应。

每个组件使用构造器注入且最多五个依赖。禁止通过依赖容器对象隐藏耦合；恢复码轮换继续位于入口事务内，任何因子持久化失败都必须整体回滚。

## 影响

- 因子管理入口不再持有数据库、密码学与权限服务的混合依赖。
- 登录入口从十一个字段注入收敛为四个构造器依赖，生产代码字段注入机械基线降为零。
- 一次性挑战与用户归属校验只有一个实现位置。
- 新组件必须满足新文件单文件覆盖率门槛，并覆盖无效挑战、无效验证码、最后因子和 WebAuthn 用户验证路径。
- 对外认证接口与 fail-closed 语义保持不变；step-up 标记仅在绑定会话的凭据验证成功后写入。
