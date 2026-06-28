# ComicAtlas Phase 1.5 设计文档（修订版）

**日期**: 2026-06-28
**阶段**: 核心导入 + 管理 + 阅读

> 原始 Phase 1 文档（2026-06-25）基于 e-hentai 下载方案，实际开发中已大幅调整。
> 本文档为最终落地版本。

---

## 1. 核心定位

ComicAtlas = 统一接收不同来源的漫画 → 导入 → 管理 → 阅读。

**不是下载工具**。

---

## 2. 导入模型

### 2.1 两种导入方式

| 方式 | sourceType | 文件处理 | storage_type |
|------|-----------|---------|-------------|
| **ZIP Import** | ZIP | 解压 → 搬到 `D:/manga/hq/{id}/{ch}/` | MANAGED |
| **Register** | REGISTER | 原地不动，只写 DB | EXTERNAL |

### 2.2 API

```
POST /api/tasks/import
{ "sourceType": "ZIP",      "sourcePath": "D:/downloads/comic.zip" }
{ "sourceType": "REGISTER", "sourcePath": "F:/games/comics/h_photograph/写真/ComicA" }
```

### 2.3 Worker 处理链

```
ImportTaskHandler (路由)
  ├─ ZIP    → ZipImportHandler    → DirectoryParser → importManaged
  └─ REGISTER → DirectoryImportHandler → DirectoryParser → importExternal
```

### 2.4 DirectoryParser Contract

- 输入：目录路径 → `findComicRoot()` 定位漫画根目录
- 输出：`ComicMetadata`（chapters + pages + hqStatus + lqStatus）
- HQ/LQ 配对：`h_photograph/{rel}` → `l_photograph/{rel}`
- 0 字节 HQ → `hqStatus=MISSING`

---

## 3. 存储模型

| comic 字段 | MANAGED | EXTERNAL |
|-----------|---------|----------|
| `storage_type` | MANAGED | EXTERNAL |
| `root_key` | — | LOCAL |
| `relative_path` | — | `h_photograph/写真/ComicA/` |

配置：`worker.storage-roots.LOCAL=F:/games/comics`

### page 字段

| 字段 | 说明 |
|------|------|
| `hq_status` | PENDING / READY / MISSING |
| `lq_status` | PENDING / READY / FAILED |
| `lq_size` | LQ 文件大小 |

---

## 4. 数据库核心表

- **comic**: id, title, author, storage_type, root_key, relative_path, status, source_type, source_ref
- **chapter**: id, comic_id, title, chapter_no, page_count
- **page**: id, chapter_id, page_number, image_name, hq_status, lq_status, file_size, width, height
- **import_task**: id, comic_id, status, source_type, source_ref
- **tag / comic_tag**: 标签关联
- **reading_history**: 阅读进度 UPSERT
- **operation_log**: 操作审计

---

## 5. 服务拓扑

```
Vue3 SPA → Nginx → Gateway(:8000) → API Service(:8010) → MySQL + Redis
                                         ↕ RabbitMQ
                                    Worker Service(:8020) → FileSystem
```

---

## 6. MQ 消息

| 方向 | Exchange | RoutingKey | Queue |
|------|----------|-----------|-------|
| API→W | comic.import | task.created | import.task.queue |
| W→A | comic.import | task.completed | import.result.queue |
| W→A | comic.task | status.changed | task.status.queue |

---

## 7. LQ 策略

- **MANAGED**: 导入后 `lqStatus=PENDING`，手动触发生成
- **EXTERNAL**: 已有 LQ 则 `READY`，无则 `PENDING`

---

## 8. 前端路由

```
/comics          → ComicListPage
/comics/:id      → ComicDetailPage
/comics/:id/read → ReaderPage (?chapterId=&page=)
/import          → ImportPage
/history         → HistoryPage
/dashboard       → DashboardPage
/operations      → OperationLogPage
```

---

## 9. 技术栈

| 层 | 技术 |
|----|------|
| 前端 | Vue3 + TypeScript + Pinia + Element Plus |
| 网关 | Spring Cloud Gateway |
| API | Spring Boot 3 + MyBatis Plus + RabbitMQ |
| Worker | Spring Boot 3 + RabbitMQ + aria2c + Jsoup |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7.x |
| 部署 | Docker Compose |
