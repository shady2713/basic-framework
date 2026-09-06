# 基础框架前端

基于 `Vue 3`、`Vite`、`TypeScript`、`Element Plus` 的管理后台前端项目。

## 环境要求

- `Node.js >= 20.19`（与根 README 及 package.json engines 一致）
- `pnpm >= 10`（锁文件由 `pnpm@10.28.2` 生成）

## 快速开始

```bash
pnpm install
pnpm dev:ele
```

默认开发地址：

- `http://localhost:5174`

## 常用命令

```bash
pnpm dev:ele
pnpm build:ele
pnpm test:unit
```

## 目录说明

- `apps/web-ele`：前端应用
- `packages`：共享业务包与基础能力
- `internal`：构建配置与内部工具
- `scripts`：工程脚本

## 发布说明

发布前请按实际环境调整以下配置：

- `apps/web-ele/.env`
- `apps/web-ele/.env.development`
- `apps/web-ele/.env.production`
