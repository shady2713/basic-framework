# ADR 0029：删除零消费的短信渠道、WebAuthn 因子与无人使用的 UI 插件

- 状态：已接受
- 取代：ADR 0006 中 MFA 行的 WebAuthn 部分；ADR 0017 中 WebAuthn 相关组件

## 背景

功能清单审计（减法轮）确认两组"为框架而框架"的冗余：

1. **渠道冗余**：短信渠道同时接入阿里云与腾讯云，MFA 同时支持 TOTP、恢复码与
   WebAuthn，但部署形态只用到阿里云短信与 TOTP 系因子；腾讯云 SDK、
   webauthn-server-core 及其回调端点、凭证表分支均为零消费死面。
2. **UI 冗余**：`@vben/plugins` 下的 echarts、code-editor 子路径与
   `@vben/common-ui` 的 col-page、content-wrap、count-to、ellipsis-text、
   json-viewer 组件在全仓库无任何生产消费点；登录页短信登录端点
   (`/system/auth/sms-login`) 前端无调用，纯后端死端点。

保留判据沿用 ADR 0028：面向下游交付的能力缝可以零仓库内消费者，但必须 README
钉住契约并有测试证明可用。被删项均不满足——它们是"可选渠道/可选插件"而非框架
契约，删除不缩小对外承诺。

## 决策

1. 短信仅保留阿里云：删除 TencentSmsClient、`/system/sms/callback/tencent` 端点、
   `SmsChannelEnum.TENCENT` 与种子字典行；渠道/模板表历史数据列不动（仅停止新增）。
2. MFA 因子仅保留 TOTP + 一次性恢复码：删除 WebAuthn 注册/认证/step-up 全链、
   webauthn-server-core 依赖与前端封装；`mfa_factor` 表与历史迁移保留为不可变记录。
3. 删除 `@vben/plugins` 的 echarts、code-editor 子路径及 echarts、codemirror 依赖；
   删除上述五个零消费 common-ui 组件；删除 `/system/auth/sms-login` 端点、
   `AdminAuthService.smsLogin`、`ADMIN_MEMBER_LOGIN`/`LOGIN_MOBILE` 枚举值与对应
   种子字典行。`send-sms-code` 保留（忘记密码链路依赖）。
4. 文档同批更新：ADR 0006 MFA 行改为 TOTP 事实、0017 标注被本 ADR 取代的部分、
   field-catalog 与 high-risk-operations 清除死引用。

## 后果

- 依赖面缩小：移除 webauthn-server-core、腾讯云 SDK、echarts、codemirror 及
  @types/codemirror；SBOM 与 Trivy 扫描面同步缩小。
- 端点契约变更：`sms-login`、`callback/tencent`、MFA webauthn 系端点删除属于
  破坏性变更；对外承诺仅覆盖存活渠道（aliyun 回调、TOTP/恢复码、密码登录与
  忘记密码链路）。
- 测试口径：SmsLogServiceImpl 的验证码脱敏白名单由 `SmsSceneEnum` 派生，场景
  收缩后自动收缩；集成测试的跨渠道唯一索引断言改用与厂商无关的 LEGACY 标签。
