# ComicAtlas Architecture Index

**版本**: 0.2  
**日期**: 2026-07-16  
**状态**: 现行（0.2 设计文档已归档，见文档导航标注）

---

## 文档导航

| 文档 | 主题 | 阅读对象 |
|------|------|----------|
| [01-system-overview.md](./01-system-overview.md) | 系统全景（架构分层、模块职责、技术栈） | 所有开发者 |
| [02-import-pipeline.md](./02-import-pipeline.md) | 导入流水线（统一导入流程、metadata.json） | 所有开发者 |
| [03-storage.md](./03-storage.md) | 存储设计（StorageService 抽象、MANGA_ROOT 布局） | 所有开发者 |
| [01-product.md](./01-product.md) | 产品定位、核心原则、范围边界（0.2 历史设计） | 所有开发者 |
| [02-navigation.md](./02-navigation.md) | 顶层导航、双 Layout、路由总览（0.2 历史设计） | 前端、产品 |
| [03-reading.md](./03-reading.md) | 阅读模块设计（0.2 历史设计） | 前端、后端 |
| [04-management.md](./04-management.md) | 管理模块设计 | 前端、后端 |
| [05-domain.md](./05-domain.md) | 领域模型、数据模型、Category/Tag 设计 | 后端 |
| [06-api.md](./06-api.md) | API 组织原则、Controller/DTO 策略 | 后端、前端 |
| [07-frontend.md](./07-frontend.md) | 前端目录结构、Layout、Router | 前端 |
| [08-migration.md](./08-migration.md) | 0.2 迁移阶段计划 | 所有开发者 |

> 01-product / 02-navigation / 03-reading 为 0.2 时代历史设计文档，保留作归档参考；当前实现以 01-system-overview / 02-import-pipeline / 03-storage 为准。

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
