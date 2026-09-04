# ComicAtlas Architecture Index

**版本**: 2.1
**日期**: 2026-09-04
**状态**: 现行

---

## 文档导航

| 文档 | 主题 | 阅读对象 |
|------|------|----------|
| [README.md](./README.md) | 当前架构总览（统一入口） | 所有开发者 |
| [01-system-overview.md](./01-system-overview.md) | 系统全景（架构分层、模块职责、技术栈） | 所有开发者 |
| [02-import-pipeline.md](./02-import-pipeline.md) | 导入流水线（统一导入流程、metadata.json） | 所有开发者 |
| [03-storage.md](./03-storage.md) | 存储设计（StorageService 抽象、MANGA_ROOT 布局） | 所有开发者 |
| [09-media-lifecycle-capabilities.md](./09-media-lifecycle-capabilities.md) | 媒体处理、任务管线与生命周期总览 | 所有开发者 |
| [shared-module-boundaries.md](./shared-module-boundaries.md) | 跨服务契约与持久化模块边界 | 后端 |
| [../frontend/08-frontend-architecture.md](../frontend/08-frontend-architecture.md) | 当前前端目录与路由结构 | 前端 |
| [../database/schema.md](../database/schema.md) | 当前数据库结构与状态枚举 | 后端 |

> `01-product.md`、`02-navigation.md`、`03-reading.md`、`04-management.md`、`05-domain.md`、`06-api.md`、`07-frontend.md`、`08-migration.md` 是 0.2 时代历史设计稿，仅保留决策背景；不要用其中的旧路由、旧包名或未完成计划指导当前实现。当前接口以 [`docs/api.md`](../api.md) 为准。

---

## 核心原则（一句话）

> **管理服务于阅读，阅读是整个产品唯一的核心体验。**

任何设计决策如果与这条原则冲突，应优先保障阅读体验。

---

## 变更记录

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-07-16 | 0.2 | 确立 Reading / Management 双域架构，冻结为开发基线 |
| 2026-08-08 | 0.2 | 索引补充现行系统文档（01-system-overview / 02-import-pipeline / 03-storage），0.2 设计文档标注历史归档 |
| 2026-08-12 | 0.2 | 文档对齐 v1.5.0 发布定位（管理后台、存储统一端点、回收站、V17-V20 迁移，见 [发布说明](../releases/v1.5.0.md)） |
| 2026-08-12 | 1.5 | 现行架构拆分阅读服务与管理服务：`/api/**` 走阅读服务，`/api/manage/**` 走管理服务 |
| 2026-08-16 | 2.0 | 发布读写服务拆分、共享模块收敛、统一管理命令管线及 V21-V23 迁移，见 [发布说明](../releases/v2.0.0.md) |
| 2026-08-22 | 2.1 | CBZ/ComicInfo、导出格式 V24 与 Worker 业务目录整理，见 [发布说明](../releases/v2.1.0.md) |
| 2026-09-04 | 2.1 | 清理旧导航，补充媒体生命周期总览并标明历史设计边界 |
