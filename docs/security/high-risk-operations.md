# 高风险操作与 MFA step-up 清单

本文是管理端高风险操作分级的事实源。新增或修改 Controller 命令时，先按本文
判断是否需要 `@MfaStepUp`，并同步修改所属模块的
`SecuritySensitiveOperationTest`；不得由业务页面自行判断或绕过。

## 分级原则

近期 MFA 二次验证只用于以下边界：账号接管或批量账号管理、权限提升、凭证或
控制面配置读取与变更、会话强制撤销、作业执行控制。普通业务 CRUD 不因使用
POST/PUT/DELETE 就自动升级，仍由专用权限、参数校验、操作确认和审计保护。

该机制是绑定当前 access token 的短时重新认证窗口，不是绑定每个请求参数的交易
签名。服务端注解是权威执行点；前端只负责完成挑战，并最多重试一次原请求。
分级参考 OWASP 的
[MFA 敏感操作建议](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)
和
[服务端交易授权原则](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html)。

## 必须 step-up 的端点

| 风险边界 | Controller | 受保护方法 |
| --- | --- | --- |
| 账号创建、认证标识变更、禁用、重置、解除登录锁定、删除、批量导入和敏感导出 | `UserController` | `createUser`、`updateUser`、`deleteUser`、`deleteUserList`、`updateUserPassword`、`updateUserStatus`、`unlockLogin`、`exportUserList`、`importExcel` |
| 本人联系方式/密码与 MFA 因子管理 | `UserProfileController` | `updateUserProfile`、`updateUserProfilePassword`、`startManagedTotpEnrollment`、`finishManagedTotpEnrollment`、`removeMfaFactor`、`resetMfaRecoveryCodes` |
| 角色、菜单权限和数据范围分配 | `PermissionController` | `assignRoleMenu`、`assignRoleDataScope`、`assignUserRole` |
| 权限资源定义 | `MenuController` | `createMenu`、`updateMenu`、`deleteMenu`、`deleteMenuList` |
| 角色定义 | `RoleController` | `createRole`、`updateRole`、`deleteRole`、`deleteRoleList` |
| 强制撤销用户会话 | `UserSessionController` | `revokeSession`、`revokeSessionList` |
| 短信渠道凭证状态读取与变更 | `SmsChannelController` | `createSmsChannel`、`updateSmsChannel`、`deleteSmsChannel`、`deleteSmsChannelList`、`getSmsChannel`、`getSmsChannelPage` |
| 动态参数控制面读取、导出与变更 | `ConfigController` | `createConfig`、`updateConfig`、`deleteConfig`、`deleteConfigList`、`getConfig`、`getConfigPage`、`exportConfig` |
| 文件存储凭证状态读取、切主、测试与变更 | `FileConfigController` | `createFileConfig`、`updateFileConfig`、`updateFileConfigMaster`、`deleteFileConfig`、`deleteFileConfigList`、`getFileConfig`、`getFileConfigPage`、`testFileConfig` |
| 定时任务定义、启停、同步和立即执行 | `JobController` | `createJob`、`updateJob`、`updateJobStatus`、`deleteJob`、`deleteJobList`、`triggerJob`、`syncJob` |

`UserProfileController#updateUserProfile` 同时承载昵称、头像、邮箱和手机号；手机号可
参与忘记密码的短信校验链路，因此当前按整个命令保护，避免仅靠客户端字段拆分形成旁路。

权限分配的 Controller 权限与 MFA 只证明请求经过高风险入口。`PermissionService`
还必须接收当前操作者编号；凡授予、撤销或修改 `super_admin` 角色，操作者本身必须
持有启用中的 `super_admin`。后台任务和集成调用也不得使用目标用户编号冒充操作者。

## 不使用 step-up 的管理命令

| Controller 范围 | 结论与理由 |
| --- | --- |
| `AuthController`、`CaptchaController`、`SmsCallbackController` | 登录、MFA ceremony、验证码和第三方回调属于认证协议或验签入口；在这些入口叠加 step-up 会形成循环。它们依赖一次性挑战、限流、签名/来源校验和审计。 |
| `DeptController`、`PostController`、`DictTypeController`、`DictDataController` | 组织与字典主数据属于普通管理 CRUD，使用专用权限、约束和删除矩阵，不按 HTTP 动词强制 MFA。 |
| `NoticeController`、`NotifyTemplateController`、`NotifyMessageController`、`SmsTemplateController` | 内容与消息操作使用专用权限和审计；渠道凭证由 `SmsChannelController` 单独升级保护。 |
| `UserProfileController` 的首次 MFA 注册 | 普通用户尚无第二因子，入口使用当前密码重新认证和服务端一次性 ceremony；注册后的管理命令才要求 step-up。 |
| `FileController` | 文件上传、登记和删除属于资源生命周期，按权限、路径校验和删除矩阵治理；不得因所有删除动作而统一要求 MFA。 |

## 防回退与变更规则

- system 与 infra 模块的 `SecuritySensitiveOperationTest` 对上述已登记 Controller
  做精确集合断言；删除注解、漏加同一 Controller 的新高风险方法或增加未登记注解
  都会使测试失败。新增 Controller 仍须在评审时同时登记到本目录和测试。
- 客户端仅在 HTTP 403 且业务码为 `1_002_000_016` 时发起挑战；普通 JSON 和下载
  Blob 错误体都必须识别。并发失败共享一次挑战，成功后每个原请求最多重试一次。
- 变更分级时必须同时更新本文、Controller 注解和测试。扩大到普通 CRUD，或改成
  请求参数绑定的逐交易授权，需先记录新的安全决策。
