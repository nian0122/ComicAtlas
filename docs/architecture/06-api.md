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
| `ComicController` | Comic | 阅读列表、详情；管理 CRUD、元数据、封面、标签 |
| `CatalogController` | Catalog | 目录树 |
| `ReaderController` | Chapter / Page | 阅读器数据 |
| `HistoryController` | ReadingHistory | 阅读历史 |
| `ImportController` | ImportTask | 导入任务 |
| `TagController` | Tag | 标签管理 |
| `CategoryController` | Category | 分类管理（新增） |
| `StorageController` | Storage | 存储统计、扫描、恢复、清理 |
| `SettingsController` | Settings | 系统设置 |

---

## DTO 按场景组织

同一个 Comic 资源，在不同场景下返回不同 DTO：

| DTO | 场景 | 字段示例 |
|-----|------|----------|
| `ComicSummaryDTO` | 漫画墙 / 列表 | id, title, author, coverUrl, categoryName, tagNames, lastReadAt |
| `ComicDetailDTO` | 阅读详情 | summary + description, catalogTree, readingProgress |
| `ComicManageDTO` | 管理编辑 | detail + sourceType, sourcePath, storagePolicy, createdAt, updatedAt, status |
| `ComicMetadataUpdateDTO` | 元数据更新 | title, author, titleJpn, description, categoryId, tagIds |
| `CoverUpdateDTO` | 封面更新 | pageId |

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

```http
GET    /api/manage/comics
POST   /api/manage/comics
PUT    /api/manage/comics/{id}
DELETE /api/manage/comics/{id}
PATCH  /api/manage/comics/{id}/metadata
PATCH  /api/manage/comics/{id}/cover
PATCH  /api/manage/comics/{id}/tags
```

或继续沿用现有路径：

```http
GET    /api/comics
POST   /api/comics
PUT    /api/comics/{id}
DELETE /api/comics/{id}
PUT    /api/comics/{id}/metadata
PUT    /api/comics/{id}/cover
PUT    /api/comics/{id}/tags
```

**决策**：0.2 优先保留现有 URL，通过 DTO 解耦。如果后续管理端接口大量膨胀，再考虑引入 `/api/admin/*` 或 `/api/manage/*` 前缀。

---

## 接口职责分离原则

| 场景 | 返回 |
|------|------|
| 阅读列表 | 封面、标题、作者、分类、标签、阅读进度 |
| 阅读详情 | 基本信息 + 目录 + 继续阅读按钮 |
| 管理列表 | 来源、状态、创建时间、更新时间、操作按钮 |
| 管理编辑 | 全部可编辑字段 + 危险操作 |

---

## 实施顺序

API 调整放在迁移最后阶段：

1. 前端页面归属调整完成。
2. 观察哪些接口真正需要拆分。
3. 新增必要的 DTO。
4. 调整 Controller 方法签名。
5. 保持 URL 稳定，避免前端大规模改动。
