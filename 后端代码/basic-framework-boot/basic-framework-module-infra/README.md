# infra 模块

`basic-framework-module-infra` 提供配置、文件存储、任务调度和运行日志等管理后台基础能力。

## 参数配置契约

- `visible=false` 的配置值视为敏感数据；详情、分页、导出和按 key 查询都不得把原始值返回浏览器。
- 响应脱敏集中在 `ConfigConvert`，新增响应形态必须复用该转换边界。

## 文件上传契约

- 后端直传和预签名上传共用文件名、路径、大小、MIME 与压缩包安全校验。
- 文件客户端、任务注册与文件类型识别失败只记录脱敏后的有界异常堆栈，不记录凭据、文件内容或异常正文。
- 新文件默认私有读取，并记录上传主体；只有请求显式携带 `publicRead=true` 才能匿名读取。私有文件只允许所有者或拥有 `infra:file:query` 的管理员经下载接口读取。
- 预签名上传仅支持私有 S3 存储，客户端只提交一次性凭据，不能决定存储配置、路径或访问地址。
- 私有文件不得写入公开对象存储配置；历史文件在 V37 迁移中显式标记为公开，避免升级改变既有访问语义。
- 完成接口校验存储端实际对象后才返回访问地址；超时或失败对象由 `fileDeletionRetryJob` 持久化重试清理。
- 可调参数由 `basic-framework.file.presigned-upload` 所有，部署环境可使用 `FILE_PRESIGNED_UPLOAD_TTL` 和 `FILE_PRESIGNED_UPLOAD_MAX_SIZE`。
- 文件客户端由工厂统一持有；配置刷新先创建可用替代实例，存储类型切换、配置删除和应用关闭都会释放旧客户端资源。
- S3 刷新使用读写锁隔离在途请求，网络客户端和预签名器必须成对关闭，响应流必须在读取后关闭。

完整决策见 [ADR 0012](../../../docs/adr/0012-file-upload-metadata-boundary.md)、[ADR 0015](../../../docs/adr/0015-resilient-file-deletion.md) 和 [ADR 0018](../../../docs/adr/0018-file-access-visibility.md)。
