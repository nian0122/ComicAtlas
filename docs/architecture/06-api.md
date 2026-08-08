# ComicAtlas 0.2 API 设计

**版本**: 0.2  
**日期**: 2026-07-16  
**状态**: Canonical

---

## 核心原则

> **Controller 继续按资源组织，DTO 按使用场景组织。**

不要为了 URL 前缀美观而去重构 Controller 路径。REST 资源风格没有问题。

---

## Controller 组织

| Controller | 资源 | 说明 |
|------------|------|------|
| `ComicController` | Comic | 阅读列表、详情；管理 CRUD、元数据、标签 |
| `CatalogController` | Catalog | 目录树（只读） |
| `CatalogManagementController` | Catalog | 目录 CRUD（创建/重命名/移动/排序/删除） |
| `ChapterManagementController` | Chapter | 章节 CRUD（创建/重命名/移动/排序/回收） |
| `ReaderController` | Chapter / Page | 阅读器数据 |
| `HistoryController` | ReadingHistory | 阅读历史 |
| `ImportController` | ImportTask | 导入任务（创建/列表/详情/取消/重试/批量） |
| `DirectoryScanTaskController` / `RecoveryTaskController` | 扫描 / 恢复 | 目录扫描与存储恢复任务 |
| `TagController` | Tag | 标签管理 |
| `CategoryController` | Category | 分类管理 |
| `SettingsController` | Settings | 系统设置（Redis 存储） |
| `StorageStatsController` | Storage | 存储统计（`/api/storage/stats`） |
| `StorageOperationController` | Storage | LQ 生成、HQ 删除、转码、导出、刷新元数据（`/api/storage/*`） |
| `AdminController` | Admin | 旧管理入口（scan-recover 已废弃、删除兼容） |
| `AdminStorageController` | Admin | 漫画/章节级存储查询（`/api/admin/storage/comics*`） |
| `AdminDlqController` | Admin | DLQ 死信管理（`/api/admin/dlq/*`） |
| `ManagementTaskController` | ManagementTask | 管理任务中心 |
| `BatchOperationController` | Batch | 批量操作（预览 + 创建） |
| `MediaOperationController` | Media | 允许操作查询（`/api/management/operations`） |
| `OutboxStatsController` | Outbox | Outbox 积压统计 |
| `TrashLifecycleController` | Trash | 回收站（恢复/永久清理/对账） |
| `UploadController` | Upload | 分块上传会话 |
| `MediaManagementController` | Media | 媒体重排/回收 |

---

## DTO 按场景组织

同一个 Comic 资源，在不同场景下返回不同 DTO（VO 即视图对象，见 `api-service/.../comic/dto/`）：

| DTO | 场景 | 字段示例 |
|-----|------|----------|
| `ComicListVO` | 漫画墙 / 列表 | id, title, author, coverUrl, categoryName, status, progressPercent, lastReadChapterId |
| `ComicDetailVO` | 阅读详情 / 管理编辑 | id, title, titleJpn, author, description, coverUrl, sourceType, categoryId, status, version, chapters, tags |
| `ComicMetadataDTO` / `ComicMetadataUpdateDTO` | 元数据读写 | title, author, description, categoryId, tagIds |
| `CreateComicRequest` / `UpdateComicRequest` | 创建 / 更新漫画 | title, titleJpn, author, description, categoryId, tagIds（更新含 `version` 乐观锁） |
| `ComicTagUpdateDTO` | 标签绑定 | tagIds |
| `CatalogNode` / `CatalogVO` | 目录树 / 目录项 | id, title, children, chapters |
| `ChapterVO` / `ChapterRef` | 章节视图 / 引用 | id, chapterNo, title, pageCount |

---

## 阅读端接口示例

```http
GET /api/comics
GET /api/comics/{id}
GET /api/comics/{id}/catalog
GET /api/chapters/{id}
GET /api/history
PUT /api/history/{comicId}
```

阅读端接口只返回阅读所需字段，不暴露来源、存储策略、导入路径等管理字段。

---

## 管理端接口示例

管理端与阅读端共用 `/api/comics` 前缀（未引入 `/api/manage` 前缀），并通过 `/api/management`、`/api/storage`、`/api/trash`、`/api/uploads` 承载管理领域：

```http
GET    /api/comics                              # 列表（含 lifecycle / allowedOperations）
POST   /api/comics                              # 创建空漫画（DRAFT）
PUT    /api/comics/{id}                         # 更新（version 乐观锁，冲突 → 409）
DELETE /api/comics/{id}                         # 回收（创建管理任务，进入回收站）
PUT    /api/comics/{id}/metadata                # 更新元数据
PUT    /api/comics/{id}/tags                    # 绑定标签
GET    /api/comics/{comicId}/catalog            # 目录树（只读）
POST   /api/comics/{comicId}/catalogs           # 目录 CRUD（创建/重命名/移动/排序/删除）
POST   /api/comics/{comicId}/chapters           # 章节 CRUD（创建/重命名/移动/排序/回收）
POST   /api/management/tasks                    # 管理任务中心（创建/列表/取消/重试）
POST   /api/management/batch                    # 批量操作（预览 + 创建）
GET    /api/management/operations/...           # 允许操作查询
POST   /api/storage/{op}/comics/{id}            # 存储操作（lq/delete-hq/transcode/export/refresh-metadata）
POST   /api/trash/comics/{comicId}/restore      # 回收站（恢复/永久清理/对账）
POST   /api/uploads/sessions                    # 分块上传会话
```

> v1.0 起封面候选/设置接口已移除（封面 URL 由 `FileUrlResolver.resolveCover(comicId)` 生成），`DELETE /api/comics/{id}` 语义由硬删改为进入回收站。完整端点见 [`docs/api.md`](../api.md)。

**决策**：0.2 优先保留现有 URL，通过 DTO 解耦；管理领域按资源拆出 `/api/management`、`/api/storage`、`/api/trash`、`/api/uploads` 前缀，未使用 `/api/manage/*`。

---

## 接口职责分离原则

| 场景 | 返回 |
|------|------|
| 阅读列表 | 封面、标题、作者、分类、标签、阅读进度 |
| 阅读详情 | 基本信息 + 目录 + 继续阅读按钮 |
| 管理列表 | 来源、状态、创建时间、更新时间、操作按钮 |
| 管理编辑 | 全部可编辑字段 + 危险操作 |

---

## 事件与 MQ

共享事件 DTO 定义在 `comic-common/.../event/`，共 **37 个 record**（`ComicEvent` sealed 接口 + 各域事件），Jackson 多态序列化（`eventType` 字段）。全部路由到 RabbitMQ，exchange/queue/routingKey 契约见 [`docs/api.md`](../api.md) 第 18.4 节，与 AGENTS.md 的 RABBITMQ 表一致。

要点：

- 事件通过事务性 Outbox 发布（`outbox_message` 表），业务代码不直接操作 RabbitMQ。
- API 侧结果消费以 `inbox_receipt` 幂等（event_id + payload_hash），实现 exactly-once 落库。
- 所有主队列配置 DLX + DLQ；DLQ 消息可经 `/api/admin/dlq/*` 查看、重放或清理。

---

## 实施顺序

API 调整放在迁移最后阶段：

1. 前端页面归属调整完成。
2. 观察哪些接口真正需要拆分。
3. 新增必要的 DTO。
4. 调整 Controller 方法签名。
5. 保持 URL 稳定，避免前端大规模改动。
