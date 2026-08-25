# Security starter

该 starter 负责访问令牌认证、权限表达式、高风险操作的 MFA 二次验证，以及可恢复
凭据的统一加密接缝。

## 可恢复凭据

短信渠道密钥、文件存储密钥和 MFA 秘密统一通过 `CredentialCipher` 保护。密文采用
带版本前缀、随机 nonce 和业务上下文 AAD 的 AES-256-GCM；业务模块不得自行保存
明文或复制密码学实现。生产环境必须通过 `CREDENTIAL_ENCRYPTION_KEY` 注入 Base64
编码的 32 字节主密钥，密钥不得进入仓库、日志或 API 响应。非生产环境未配置时，
调用加解密能力会关闭失败。

## 高风险操作二次验证

Controller 的高风险命令使用 `@MfaStepUp` 标记。切面从当前请求读取访问令牌，
通过 system-api 的 `MfaCommonApi` 校验该会话是否在短时窗口内完成过 MFA。
能力未启用时保持兼容放行；启用后，缺少、过期或属于其他访问令牌的验证状态均拒绝执行。
业务模块不得自行读取验证码或复制 Redis 判断。

客户端先调用 `/system/auth/mfa/step-up/start` 创建一次性挑战，再选择 WebAuthn、
TOTP 或恢复码完成验证。成功状态只绑定当前 access token，TTL 由
`basic-framework.security.mfa.step-up-ttl` 统一配置；刷新令牌不会继承该状态。
