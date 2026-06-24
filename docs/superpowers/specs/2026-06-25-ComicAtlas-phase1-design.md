# ComicAtlas Phase 1 设计文档

**日期**: 2026-06-25
**版本**: 1.0
**阶段**: Phase 1 - 核心（漫画仓库 + 文件解析 + 导入任务）

---

## 目录

1. [服务拓扑](#1-服务拓扑)
2. [数据库设计](#2-数据库设计)
3. [API 设计](#3-api-设计)
4. [MQ 消息设计](#4-mq-消息设计)
5. [前端路由与组件设计](#5-前端路由与组件设计)
6. [待定决策与风险](#6-待定决策与风险)

---

## 1. 服务拓扑

### 1.1 架构图

```
                    ┌──────────────────┐
                    │   Vue3 + TS SPA  │
                    │  (Nginx 静态部署)  │
                    └────────┬─────────┘
                             │ HTTP/REST
                             ▼
                    ┌──────────────────┐
                    │  API Gateway     │
                    │  SC Gateway:8000 │
                    └──────┬───────────┘
                           │ Nacos 服务发现
              ┌────────────┼────────────┐
              ▼            │            ▼
     ┌────────────────┐   │   ┌────────────────┐
     │ Comic-API      │   │   │ Comic-Worker   │
     │ Service        │   │   │ Service        │
     │ :8010          │   │   │ :8020          │
     │                │   │   │                │
     │ - 漫画 CRUD    │   │   │ - 文件下载      │
     │ - E-H API 调用 │   │   │ - 文件解压      │
     │ - 搜索/详情    │   │   │ - 封面提取      │
     │ - 导入任务管理  │   │   │ - 元数据解析    │
     │ - 阅读记录     │   │   │                │
     │ - 唯一写入MySQL │   │   │ - 不写MySQL ❌  │
     └───────┬────────┘   │   └───────┬────────┘
             │            │           │
             │       ┌────┘           │
             ▼       ▼                │
     ┌──────────────────────┐         │
     │ MySQL (元数据唯一入口) │         │
     │ Redis (缓存/去重)     │         │
     └──────────────────────┘         │
             ▲                        │
             │  RabbitMQ              │
             │  ┌─────────────────────┘
             │  │
     ┌───────┴──┴──────────────────┐
     │      RabbitMQ 消息流         │
     │                             │
     │ API ────▶ ImportTaskCreated │
     │                │            │
     │              Worker         │
     │                │            │
     │ Worker ──▶ ComicImported    │
     │                │            │
     │              API            │
     │           (更新MySQL)        │
     └─────────────────────────────┘
                     │
                     ▼
             ┌──────────────┐
             │ 本地文件系统   │
             │ /manga/       │
             │  ├─ raw/      │
             │  ├─ hq/       │
             │  ├─ lq/       │
             │  ├─ thumbs/   │
             │  ├─ temp/     │
             │  └─ metadata/ │
             └──────────────┘
```

### 1.2 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3.5 + TypeScript + Pinia + Element Plus + Vite |
| 网关 | Spring Cloud Gateway |
| 业务服务 | Spring Boot 3 + Spring Cloud Alibaba + MyBatis Plus |
| 服务发现/配置 | Nacos 2.3 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7.x |
| 消息队列 | RabbitMQ 3.13+ |
| 下载工具 | aria2c (torrent) |
| 图片工具 | Go image-optimizer (复用 comics15) |
| 运行环境 | Java 21, Go 1.21+ |
| 部署 | Docker Compose |

### 1.3 通信方式

| 调用方向 | 方式 | 场景 |
|---------|------|------|
| Gateway → API | HTTP (Nacos 服务发现) | 所有前端请求 |
| API → Worker | RabbitMQ | 创建导入任务、LQ 生成任务、删除任务 |
| Worker → API | RabbitMQ (事件驱动) | 状态变更、导入完成、LQ 完成 |
| Worker → 文件系统 | 本地 I/O | 读写漫画文件 |

### 1.4 数据库写入原则

**API Service 是业务数据的唯一写入入口，Worker 永不直接操作数据库。**

Worker 只做文件 I/O + 发事件。任何 DB 写入全部由 API 通过消费事件完成。

### 1.5 文件目录结构

```
/manga/
  temp/{task_id}/              # 下载/解压临时文件
  hq/{comic_id}/               # HQ 原图
    {chapter_no}/
      001.jpg
      002.jpg
      ...
  lq/{comic_id}/               # LQ WebP
    {chapter_no}/
      001.webp
      002.webp
      ...
  raw/{comic_id}.zip           # 原始压缩包存档
  thumbs/{comic_id}/
    cover.webp
  metadata/{task_id}.json      # 导入元数据（事件驱动生命周期管理）
```

### 1.6 Nginx 静态服务（复用 comics15 模式）

```nginx
# API 反向代理
location /api/ { proxy_pass http://gateway:8000; }

# HQ 原图
location /comic/hq/ { alias /manga/hq/; }

# LQ WebP（缺失返回 204，前端回退 HQ）
location /comic/lq/ {
    alias /manga/lq/;
    try_files $uri @lq_not_found;
}
location @lq_not_found { return 204; }
```

---

## 2. 数据库设计

### 2.1 ER 关系

```
comic ──1:N──▶ chapter ──1:N──▶ page
  │                                │
  └──N:M──▶ tag                    └── 不存路径，运行时动态计算
         (via comic_tag)
```

### 2.2 核心表

**comic（漫画主表）**

```sql
CREATE TABLE comic (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  title               VARCHAR(255)  NOT NULL,
  title_jpn           VARCHAR(255),
  author              VARCHAR(255),
  cover_path          VARCHAR(512),
  total_pages         INT DEFAULT 0,
  file_size           BIGINT DEFAULT 0,
  hq_size             BIGINT DEFAULT 0,
  lq_size             BIGINT DEFAULT 0,
  source_type         VARCHAR(16),   -- E_HENTAI / LOCAL
  source_gallery_id   INT,
  source_gallery_token VARCHAR(32),
  source_url          VARCHAR(512),
  status              VARCHAR(16) DEFAULT 'IMPORTING',  -- IMPORTING / READY / DELETING / DELETED / ERROR
  lq_status           VARCHAR(16) DEFAULT NULL,         -- NULL / PENDING / GENERATING / READY / PARTIAL / FAILED
  category            VARCHAR(64),
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  UNIQUE INDEX idx_source (source_type, source_gallery_id),
  INDEX idx_status (status),
  INDEX idx_created_at (created_at)
);
```

**comic.status（可否阅读）**: IMPORTING → READY → DELETING → DELETED; 任意阶段 → ERROR

**comic.lq_status（LQ 附属状态，独立于 status）**:

| status | lq_status | 含义 |
|--------|-----------|------|
| READY | READY | HQ + LQ 全部就绪 |
| READY | PARTIAL | HQ 就绪，部分页 LQ 失败（仍可阅读） |
| READY | PENDING | HQ 就绪，LQ 未开始（仍可阅读） |
| READY | GENERATING | LQ 后台生成中（仍可阅读） |
| IMPORTING | NULL | 导入中，不可阅读 |

前端只需一处判断：`comic.status !== 'READY'` 即不可阅读。

**chapter（章节表）**

```sql
CREATE TABLE chapter (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  comic_id    BIGINT NOT NULL,
  title       VARCHAR(255),
  chapter_no  INT DEFAULT 1,
  page_count  INT DEFAULT 0,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,

  INDEX idx_comic_chapter (comic_id, chapter_no),
  FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE
);
```

e-hentai 导入时默认创建一个 chapter (chapter_no=1, title=漫画标题)。未来接入有分话结构的资源站（JMComic、哔哩哔哩漫画）时 schema 无需改动。

**page（页面表）**

```sql
CREATE TABLE page (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  chapter_id  BIGINT NOT NULL,
  page_number INT NOT NULL,
  image_name  VARCHAR(255) NOT NULL,    -- 原始文件名，如 001.jpg
  lq_status   VARCHAR(16) DEFAULT 'PENDING',  -- PENDING / READY / FAILED
  width       INT,
  height      INT,
  file_size   BIGINT,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,

  INDEX idx_chapter_page (chapter_id, page_number),
  FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
);
```

**路径不存库**，由 service 层统一动态拼接：

```java
// HQ URL
String hqUrl = "/comic/hq/" + comicId + "/" + chapterNo + "/" + imageName;

// LQ URL
String baseName = FilenameUtils.getBaseName(imageName);
String lqUrl = "/comic/lq/" + comicId + "/" + chapterNo + "/" + baseName + ".webp";
```

迁移时只改 Nginx alias 或环境变量，数据库零改动。

**import_task（导入任务表）**

```sql
CREATE TABLE import_task (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  comic_id         BIGINT,             -- 完成后关联
  source_url       VARCHAR(512),
  status           VARCHAR(16) DEFAULT 'PENDING',
  -- PENDING / DOWNLOADING / EXTRACTING / PARSING / LQ_GENERATING / SUCCESS / FAILED / CANCELLED
  progress         INT DEFAULT 0,
  total_pages      INT,
  downloaded_pages INT DEFAULT 0,
  current_page     INT DEFAULT 0,      -- 断点续传
  downloaded_bytes BIGINT DEFAULT 0,   -- 通用进度（HTTP 和 Torrent 共用）
  download_method  VARCHAR(32) DEFAULT 'HTTP',  -- HTTP / TORRENT / TORRENT_FALLBACK_HTTP
  download_speed   BIGINT DEFAULT 0,   -- bytes/s, Worker 实时计算
  eta_seconds      INT DEFAULT 0,      -- Worker 实时计算
  error_message    VARCHAR(1024),
  retry_count      INT DEFAULT 0,
  start_time       DATETIME,
  end_time         DATETIME,
  duration_ms      BIGINT,
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  INDEX idx_status (status),
  FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE SET NULL
);
```

**完整状态机**:

```
PENDING → DOWNLOADING → EXTRACTING → PARSING → LQ_GENERATING → SUCCESS
   ║          ║            ║            ║            ║              ║
   ╚══════════╩════════════╩════════════╩════════════╩══════════════╝
                                      ↓
                                   FAILED
   ║          ║
   ╚══════════╝
       ↓
   CANCELLED (仅 PENDING/DOWNLOADING 阶段可取消)
```

**tag（标签表）+ comic_tag（关联表）**

```sql
CREATE TABLE tag (
  id   BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255) NOT NULL,
  type VARCHAR(32),            -- language / parody / character / group / artist / male / female / misc

  UNIQUE INDEX idx_name_type (name, type)
);

CREATE TABLE comic_tag (
  comic_id BIGINT NOT NULL,
  tag_id   BIGINT NOT NULL,

  PRIMARY KEY (comic_id, tag_id),
  FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
  FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
);
```

**reading_history（阅读记录）**

```sql
CREATE TABLE reading_history (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  comic_id    BIGINT NOT NULL UNIQUE,   -- 一部漫画只保留一条记录
  chapter_id  BIGINT NOT NULL,
  page_number INT DEFAULT 1,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
  FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
);
```

**operation_log（操作日志）**

```sql
CREATE TABLE operation_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id    VARCHAR(64),            -- 同一任务的所有日志共享 trace_id
  module      VARCHAR(32),            -- IMPORT / DELETE / LQ / SYSTEM
  action      VARCHAR(64),            -- TASK_CREATED / DOWNLOAD_COMPLETED / COMIC_IMPORTED / LQ_COMPLETED / ERROR
  business_id VARCHAR(64),            -- taskId 或 comicId
  detail      VARCHAR(1024),
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,

  INDEX idx_trace_id (trace_id),
  INDEX idx_module_business (module, business_id),
  INDEX idx_created_at (created_at)
);
```

保留策略：定时任务每日凌晨清理 90 天前的日志。

### 2.3 Redis 缓存设计

| Key 模式 | 用途 | TTL |
|----------|------|-----|
| `import:task:{id}` | 导入任务进度（实时更新） | 1h |
| `import:dedup:{type}:{gid}` | 导入去重加速 | **7天**（DB 唯一索引兜底） |
| `comic:hot:list` | 首页热门漫画列表 | 30min |
| `comic:detail:{id}` | 漫画详情缓存 | 1h |
| `mq:msg:{messageId}` | 消息幂等去重 | 24h |
| `dashboard:statistics` | Dashboard 统计数据 | 30min |

去重策略：导入前先查 Redis → 命中则返回"已存在"；未命中则创建任务，DB UNIQUE 约束兜底。Redis key 过期后重复 URL 仍被 DB 拦截。

---

## 3. API 设计

### 3.1 基础约定

- 基础路径: `http://localhost:8000/api`
- 请求与响应格式: JSON
- 字符编码: UTF-8
- 排序映射: 前端传 camelCase 参数（如 `createdAt`），后端 `SORT_MAPPING` 映射到 DB 列

### 3.2 完整路由表

#### 漫画

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/comics` | 漫画列表（分页 + 搜索 + 标签 + 状态过滤 + 排序） |
| GET | `/api/comics/{id}` | 漫画详情（含章节 + 上次阅读位置） |
| DELETE | `/api/comics/{id}` | 异步删除漫画 |

**GET `/api/comics`**

```
Query: page, size, keyword, tag, status, sort
sort: createdAt | updatedAt | title | pageCount | lastReadTime

keyword 搜索:
  WHERE title LIKE %keyword%
     OR EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t
                WHERE t.name LIKE %keyword% AND ct.comic_id = c.id)

Response:
{
  "total": 120,
  "list": [{
    "id": 1001,
    "title": "xxx", "author": "xxx",
    "coverUrl": "/comic/thumbs/1001/cover.webp",
    "pageCount": 200, "category": "doujinshi",
    "tags": ["中文", "原神"],
    "status": "READY", "lqStatus": "READY",
    "lastReadChapterId": 1, "lastReadPage": 56,
    "progressPercent": 28,
    "createdAt": "..."
  }]
}
```

**GET `/api/comics/{id}`**

```json
{
  "id": 1001,
  "title": "xxx", "titleJpn": "xxx", "author": "xxx",
  "coverUrl": "/comic/thumbs/1001/cover.webp",
  "pageCount": 200, "fileSize": 524288000,
  "hqSize": 524288000, "lqSize": 31457280,
  "sourceType": "E_HENTAI",
  "sourceUrl": "https://e-hentai.org/g/12345/abc123/",
  "category": "doujinshi",
  "status": "READY", "lqStatus": "READY",
  "tags": [{"name": "中文", "type": "language"}, {"name": "原神", "type": "parody"}],
  "chapters": [
    {"id": 1, "chapterNo": 1, "title": "xxx", "pageCount": 200}
  ],
  "lastReadChapterId": 1, "lastReadPage": 56,
  "progressPercent": 28,
  "createdAt": "..."
}
```

**DELETE `/api/comics/{id}`**

```
流程: READY → DELETING → Worker 删文件 → DELETED → 定时清理
Response: { "taskId": 999, "status": "DELETING" }
```

#### 章节

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/comics/{id}/chapters/{chapterId}/pages` | 章节页面列表（含 HQ/LQ URL） |

```json
{
  "comicId": 1001, "chapterId": 1, "chapterNo": 1, "chapterTitle": "xxx",
  "pages": [{
    "id": 1, "pageNumber": 1, "imageName": "001.jpg",
    "hqUrl": "/comic/hq/1001/1/001.jpg",
    "lqUrl": "/comic/lq/1001/1/001.webp",
    "lqStatus": "READY",
    "width": 1200, "height": 1800
  }],
  "total": 200,
  "prevChapterId": null, "nextChapterId": null
}
```

#### 导入任务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks/import` | 创建导入任务 |
| GET | `/api/tasks/import` | 任务列表 |
| GET | `/api/tasks/import/{id}` | 任务详情 |
| GET | `/api/tasks/import/{id}/status` | 轻量状态轮询（只返 status + progress） |
| POST | `/api/tasks/import/{id}/cancel` | 取消任务 |
| POST | `/api/tasks/import/{id}/retry` | 重试失败任务 |

**POST `/api/tasks/import`**

```
Request:  { "sourceUrl": "https://e-hentai.org/g/12345/abc123/" }

流程:
  1. 校验 URL + 提取 gid
  2. Redis 去重检查
  3. DB UNIQUE 约束检查
  4. INSERT comic (status=IMPORTING)  ← 预创建，拿 comicId
  5. INSERT import_task (comicId, status=PENDING)
  6. Publish ImportTaskCreated (携带 comicId)
  7. INSERT operation_log (trace_id=import-{taskId})

Response: { "taskId": 1, "status": "PENDING" }
```

**GET `/api/tasks/import/{id}/status`**（轻量轮询）

```json
{ "taskId": 1, "status": "DOWNLOADING", "progress": 45 }
```

前端 2 秒轮询用此接口，不查全量字段。

#### 阅读记录

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/history` | 阅读历史列表 |
| GET | `/api/history/{comicId}` | 单部漫画阅读进度 |
| PUT | `/api/history/{comicId}` | 更新/写入阅读进度（幂等 UPSERT） |

**PUT `/api/history/{comicId}`**

```
Request: { "chapterId": 1, "pageNumber": 56 }

SQL: INSERT INTO reading_history (...)
     ON DUPLICATE KEY UPDATE chapter_id=?, page_number=?, updated_at=NOW()
```

前端同步策略：每 5 页或 30 秒同步一次，离开阅读页时强制同步。

#### 仪表盘

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/dashboard/statistics` | 首页统计数据 |

```json
{
  "comicCount": 120, "pageCount": 24567, "tagCount": 3200,
  "todayImported": 3, "storageUsed": 55574528000,
  "importSuccessCount": 182, "importFailedCount": 5,
  "successRate": 97.3
}
```

`storageUsed = SUM(comic.hq_size + comic.lq_size)`，从 comic 表聚合而非 page 表。

#### 其他

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tags` | 标签列表 |
| GET | `/api/operations` | 操作日志（支持 module/action/businessId/keyword 筛选） |

---

## 4. MQ 消息设计

### 4.1 消息流全景

```
API Service                              Worker Service
─────────────                            ──────────────
    │                                          │
    │── ImportTaskCreated (含 comicId) ─────▶  │  下载 → 解压 → 解析 → 写入 metadata.json
    │                                          │
    │  ◀── TaskStatusChanged ──────────────── │  DOWNLOADING/EXTRACTING/PARSING
    │  ◀── ComicImported (只传引用) ───────── │  { metadataFile: "/manga/metadata/1.json" }
    │── ComicImportedProcessed ───────────▶  │  通知 Worker 清理 metadata + temp
    │                                          │
    │── LQGenerateTask (per chapter) ──────▶  │  Go image-optimizer
    │                                          │
    │  ◀── LQGenerated (只传失败页) ──────────│  { failedPages: [45,87] }
    │                                          │
    │── ComicDeleteRequested ──────────────▶  │  只传 comicId，路径 Worker 规则生成
    │                                          │
    │  ◀── ComicDeleted ─────────────────────│  文件已清理
```

### 4.2 消息定义

所有消息通用字段：`messageId` (UUID, 幂等去重), `timestamp`。

#### ImportTaskCreated (API → Worker)

```
Exchange: comic.import
Routing Key: task.created
Payload:
{
  "messageId": "uuid",
  "taskId": 1,
  "comicId": 1001,             ← 预创建，Worker 直接用于目录命名
  "sourceUrl": "https://e-hentai.org/g/12345/abc123/",
  "sourceType": "E_HENTAI"
}
```

#### TaskStatusChanged (Worker → API)

```
Exchange: comic.task
Routing Key: status.changed
Payload:
{
  "messageId": "uuid",
  "taskId": 1,
  "newStatus": "DOWNLOADING",
  "progress": 45,
  "downloadMethod": "TORRENT",
  "speedBytesPerSec": 8388608,
  "etaSeconds": 15
}
```

Worker 在 DOWNLOADING、EXTRACTING、PARSING、CANCELLED 节点发送。FAILED 由 DLQ 监听器发送。
API 消费后 UPDATE import_task 状态 + 时间戳 + 速度数据，速度/ETA 直接写入供前端轮询。

#### ComicImported (Worker → API，只传引用不传 body)

```
Exchange: comic.import
Routing Key: task.completed
Payload:
{
  "messageId": "uuid",
  "taskId": 1,
  "comicId": 1001,
  "metadataFile": "/manga/metadata/1.json"
}

metadata.json 内容:
{
  "comic": { "title": "xxx", "author": "xxx", "category": "doujinshi",
    "sourceGalleryId": 12345, "sourceGalleryToken": "abc123", "tags": [...] },
  "pages": [
    {"pageNumber": 1, "imageName": "001.jpg", "width": 1200, "height": 1800, "fileSize": 1234567},
    ...
  ],
  "totalSize": 524288000
}
```

API 消费：
1. 幂等检查 (Redis SETNX)
2. 读取 metadata.json → UPDATE comic + INSERT chapter + BATCH INSERT page + tags
3. comic.status = READY（用户可立即阅读 HQ）
4. import_task.status = LQ_GENERATING, end_time=NOW(), duration_ms 自动计算
5. Publish LQGenerateTask (per chapter)

#### ComicImportedProcessed (API → Worker)

```
Exchange: comic.import
Routing Key: task.processed
Payload: { "messageId": "uuid", "taskId": 1 }

Worker 消费: 清理 /manga/metadata/{taskId}.json + temp/{taskId}/
```

#### LQGenerateTask (API → Worker，按章节拆分)

```
Exchange: comic.image
Routing Key: lq.generate
Payload: { "messageId": "uuid", "comicId": 1001, "chapterId": 1 }
```

#### LQGenerated (Worker → API，默认全成功，只传失败页)

```
Exchange: comic.image
Routing Key: lq.completed
Payload: {
  "messageId": "uuid",
  "comicId": 1001, "chapterId": 1,
  "totalPages": 200,
  "failedPages": [45, 87]
}
```

API 消费：
- 所有 page 默认 lq_status=READY（insert 时的初始值）
- BATCH UPDATE: 仅 `failedPages` 对应行 SET lq_status='FAILED'
- 有失败 → comic.lq_status = PARTIAL; 无失败 → READY
- 所有章节 LQ 完成 → import_task.status = SUCCESS

#### ComicDeleteRequested (API → Worker，只传 comicId)

```
Exchange: comic.delete
Routing Key: delete.requested
Payload: { "messageId": "uuid", "comicId": 1001 }
```

Worker 通过规则生成路径：`hq/{comicId}/`, `lq/{comicId}/`, `raw/{comicId}.zip`, `thumbs/{comicId}/`。

#### ComicDeleted (Worker → API)

```
Exchange: comic.delete
Routing Key: delete.completed
Payload: { "messageId": "uuid", "comicId": 1001 }
```

API 消费后 UPDATE comic.status = DELETED。定时任务每日凌晨物理删除 DELETED 记录。

### 4.3 消息幂等

API 侧所有消息消费前执行：`Redis SETNX mq:msg:{messageId} EX 86400`。已处理过的消息直接 ACK 跳过。

### 4.4 重试策略与死信队列

```
import.task.queue
       │
  ┌────┴────┐
  ▼         ▼
ACK(成功)  NACK(失败)
             │
             ▼
     retry: 30s delay ──→ import.task.queue (重试1)
             │
             ▼
     retry: 5min delay ──→ import.task.queue (重试2)
             │
             ▼ (3次失败)
         import.task.dlq
             │
         DLQ 监听器:
          UPDATE import_task.status=FAILED
          INSERT operation_log
```

指数退避重试：30 秒 → 5 分钟 → 死信队列。适用于网络超时等可恢复错误。

### 4.5 RabbitMQ 拓扑

```
Exchange (direct)       Queue                   DLX/DLQ              Consumer
────────────────        ─────                   ───────              ────────
comic.import            import.task.queue        retry:30s,5min       Worker
                        import.result.queue      import.result.dlq    API
                        import.processed.queue                        Worker

comic.task              task.status.queue                              API

comic.image             lq.generate.queue        retry:30s,5min       Worker
                        lq.result.queue                               API

comic.delete            delete.task.queue                              Worker
                        delete.result.queue                            API

comic.monitor (fanout)  monitor.queue                                  API/Dashboard
```

### 4.6 监控队列（Event Bus）

```
Exchange: comic.monitor (fanout)

事件:
  IMPORT_FAILED       { taskId, errorMessage }
  WORKER_EXCEPTION    { message, stackTrace }
  DISK_WARNING        { usagePercent }
```

业务流转走 `comic.import/image/delete`，告警与可观测性走 `comic.monitor`，业务事件与监控事件分离。

---

## 5. 前端路由与组件设计

### 5.1 路由设计

```
/comics                    ComicListPage        漫画仓库列表（首页）
/comics/:id                ComicDetailPage      漫画详情 + 章节列表
/comics/:id/read           ReaderPage           阅读器
/import                    ImportPage           导入任务管理
/history                   HistoryPage          阅读历史
/dashboard                 DashboardPage        数据统计
/operations                OperationLogPage     操作日志
```

ReaderPage Query:

```
/comics/1001/read?chapterId=1&page=56

Phase 2 扩展: &mode=scroll|single|double &direction=ltr|rtl &hq=true
```

### 5.2 页面组件树

#### ComicListPage（首页）

```
ComicListPage
├── SearchBar                   keyword + tag + status + sort
├── ComicGrid
│   └── ComicCard * N
│       ├── Cover
│       ├── Title
│       ├── TagsPreview         最多 3 个
│       ├── ContinueRead        "继续阅读 56/200 · 28%"  ← 百分比 API 返回
│       ├── ImportStatus        Badge
│       └── DeleteStatus        Badge
└── Pagination
```

#### ComicDetailPage

```
ComicDetailPage
├── ComicHeader                 封面大图 + 标题 + 作者
├── ComicMeta                   来源 · 页数 · 大小 · LQ 状态
├── TagsList                    标签列表
├── ComicActions
│   ├── Button("继续阅读 56/200 · 28%")
│   ├── Button("从头开始")
│   └── Button("删除漫画")
└── ChapterList
    └── ChapterCard * N         章节序号 + 标题 + 页数 + 封面缩略图
```

`lq_status=PARTIAL` 时显示：⚠ 部分页面预览图生成失败，阅读不受影响。

#### ReaderPage（滚动阅读 — Phase 1 仅此模式）

```
ReaderPage（复用 comics15 的 ReaderMediaItem 和 HQ/LQ 切换逻辑）
├── ReaderToolbar
│   ├── BackButton
│   ├── ComicTitle + ChapterTitle
│   ├── PageIndicator        56/200
│   └── SettingsButton
├── ReaderViewport            滚动容器
│   └── ReaderMediaItem * N  <img loading="lazy" />
│       ├── LQ 优先 → /comic/lq/{comicId}/{chapterNo}/{basename}.webp
│       └── 204 回退 HQ → /comic/hq/{comicId}/{chapterNo}/{imageName}
├── ReaderSettingsDrawer
│   └── Switch("HQ 模式")
├── ReaderPreloader            当前页 ±2 预加载
└── ProgressSync               每 5 页或 30 秒 PUT /api/history/{comicId}
```

**Phase 1 不做**: 单页/双页模式、阅读方向切换、虚拟滚动。但 ReaderStore 已预留 `visibleRange` 和 `virtualScrollEnabled` 字段。

#### ImportPage

```
ImportPage
├── ImportForm
│   ├── Input("粘贴 e-hentai gallery URL")
│   └── Button("开始导入")
├── ImportTaskList
│   └── ImportTaskCard * N
│       ├── SourceUrl
│       ├── StatusBadge         状态颜色集中定义 (STATUS_COLOR_MAP)
│       ├── MiniProgressBar
│       └── Button("详情")
├── ImportTaskDetailDrawer
│   ├── StatusTimeline
│   ├── DownloadMethod         "BT下载" | "HTTP分页下载" | "BT超时→HTTP"
│   ├── ProgressBar            PAGE 模式: "120/200页" | SIZE 模式: "512MB/1.2GB"
│   ├── SpeedInfo              3.2 MB/s (Worker 直接上报)
│   ├── TimeInfo               剩余 00:00:40 (Worker 直接上报)
│   ├── Button("取消")
│   └── Button("重试")
```

#### HistoryPage

```
HistoryPage
└── HistoryCard * N
    ├── 封面 + 标题 + "第1话 · 56/200 · 28%"
    ├── 更新时间
    └── Button("继续阅读")
```

#### DashboardPage

```
DashboardPage
├── StatCardGrid
│   ├── 漫画总数    120
│   ├── 总页数      24,567
│   ├── 标签数      3,200
│   ├── 今日导入    3
│   ├── 磁盘占用    65 GB
│   └── 导入成功率  97.3% (182/187)
├── ImportTrendChart          最近 7 天导入趋势（ECharts 或纯 CSS）
└── RecentImportList          最近导入 5 部漫画
```

#### OperationLogPage

```
OperationLogPage
├── FilterBar                 按 module/action/businessId/keyword 筛选
└── LogTable
    ├── 时间 / 模块 / 操作 / 业务 ID / 详情
```

### 5.3 Pinia Store

```
stores/
├── comic-store.ts           ComicQuery 接口预留 category/sourceType
├── reader-store.ts          pages, currentPage, visibleRange, virtualScrollEnabled, hqMode
├── import-store.ts          任务列表 + 轮询计时器
├── history-store.ts         阅读历史 + 进度同步节流
├── dashboard-store.ts       统计数据（30min 本地缓存）
├── tag-store.ts             标签列表
└── app-store.ts             theme / sidebarCollapsed / globalLoading / readerConfig
```

```typescript
// 状态颜色集中定义
const STATUS_COLOR_MAP: Record<string, string> = {
  PENDING: 'default',
  DOWNLOADING: 'processing',
  EXTRACTING: 'processing',
  PARSING: 'processing',
  LQ_GENERATING: 'processing',
  SUCCESS: 'success',
  FAILED: 'error',
  CANCELLED: 'warning'
}
```

### 5.4 状态持久化

| Store | 存储 | 说明 |
|-------|------|------|
| reader-store | localStorage | 阅读位置，跨会话恢复 |
| app-store | localStorage | 主题、HQ_MODE 偏好 |
| comic-store | sessionStorage | 浏览上下文，关标签页清 |
| import-store | sessionStorage | 任务列表，刷新保留 |
| dashboard | 不持久化 | 每次重新请求 |

---

## 6. 待定决策与风险

### 6.1 下载策略

**原则: HTTP 为主方案，Torrent 为加速方案，自动切换。**

```
Worker 收到 ImportTaskCreated
  ↓
1. 获取 gallery metadata + torrent 信息
  ↓
2. 如果有磁力链接?
  ├─ 是 → 并行启动 Torrent 探测（aria2c）
  │        ↓
  │     30 秒内发现 peers?
  │        ├─ 是 → Torrent 下载 (download_method=TORRENT)
  │        └─ 否 → HTTP 下载 (download_method=TORRENT_FALLBACK_HTTP)
  │
  └─ 否 → HTTP 下载 (download_method=HTTP)
```

**aria2c 配置**:

```
torrent:
  peer_detect_timeout: 30s        # 无 peers → 立即切 HTTP
  min_speed_threshold: 10KB/s     # 低于此速度
  speed_check_duration: 5min      # 持续 5 分钟 → 切 HTTP
  seed_time: 0                    # 下载完成不做种
```

**ProgressBar 双模式**:

```typescript
interface ImportProgress {
  mode: 'PAGE' | 'SIZE'   // HTTP: PAGE, Torrent: SIZE
  current: number
  total: number
}
```

**断点续传**: `current_page` 记录已下载页码，Worker 重启后从 `current_page + 1` 继续。`downloaded_bytes` 同时记录，HTTP 和 Torrent 两种模式通用。

### 6.2 压缩包解压抽象

```java
interface ArchiveExtractor {
    List<Path> extract(Path archive, Path destDir);
    boolean supports(Path file);
}
// Phase 1: ZipExtractor, CbzExtractor
// Phase 2: RarExtractor, CbrExtractor, SevenZipExtractor
```

### 6.3 aria2c 进程管理

通过 `ProcessBuilder` 调用（和 comics15 调用 Go 工具模式一致）：

```java
ProcessBuilder pb = new ProcessBuilder("aria2c", magnetUrl,
    "--seed-time=0", "--max-connection-per-server=16", "--split=8",
    "-d", downloadDir, "--stop-with-process=" + pid);
```

`--stop-with-process` 保证父进程退出时 aria2c 自动停止。

### 6.4 排除清单（Phase 1 不做）

| 排除项 | 计划 |
|-------|------|
| AI 搜索（自然语言） | Phase 2 |
| 多资源站（JMComic、哔哩哔哩） | Phase 2 |
| AI 自动标签/分类 | Phase 3 |
| 单页/双页阅读模式 | Phase 2 |
| 阅读方向（RTL） | Phase 2 |
| 用户系统 | 个人项目 |
| Elasticsearch | Phase 2 |
| 虚拟滚动 | Phase 2（ReaderStore 已预留） |
| rar/cbr/7z 解压 | Phase 2（接口已预留） |
| AVIF 支持 | Phase 2 |

### 6.5 风险矩阵

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| e-hentai 封 IP | 导入全部失败 | 3-5s 下载间隔 + 单线程 + 429 自动退避 |
| Torrent 无做种者 | 下载卡死 | 30s 探测超时 → 自动切 HTTP |
| Torrent 龟速 | 永远下不完 | 10KB/s 持续 5min → 自动切 HTTP |
| e-hentai 改 API | 解析失败 | 版本化解析器 + operation_log 保留原始响应 |
| HTTP 下载中断 | 任务失败 | `current_page` 断点续传 + `downloaded_bytes` |
| Worker 崩溃丢进度 | 残留 temp 文件 | 启动扫描清理 24h 前的 temp/ + 状态机可恢复 |
| RabbitMQ 宕机 | 通信中断 | Phase 1 MQ 同机部署 + Phase 2 集群 |
| MySQL 唯一索引冲突 | 重复导入被拦截 | 友好提示"该漫画已存在" |
| Nacos 不可用 | Gateway 无法发现服务 | Phase 1 单机 + Gateway 固定 upstream fallback |
| 大文件磁盘写满 | 导入失败 | Dashboard 磁盘监控 + 导入前空间检查 |
| aria2c 进程泄漏 | 资源占用 | `--stop-with-process` + Worker 启动进程清理 |

### 6.6 技术栈版本

| 组件 | 版本 |
|------|------|
| Spring Boot | 3.x |
| Spring Cloud Alibaba | 2023.x |
| Spring Cloud Gateway | 配套 |
| MyBatis Plus | 3.5.x |
| Vue | 3.5.x |
| Element Plus | 2.x |
| MySQL | 8.0 |
| Redis | 7.x |
| RabbitMQ | 3.13+ |
| Nacos | 2.3.x |
| Go (image-optimizer) | 1.21+ |
| Java | 21 |
| Docker Compose | 2.x |

### 6.7 部署清单

```
comic-atlas/
├── api-service/          Spring Boot (8010)
│   └── Dockerfile
├── worker-service/       Spring Boot (8020)
│   └── Dockerfile
├── gateway/              Spring Cloud Gateway (8000)
│   └── Dockerfile
├── frontend/             Vue3 SPA
│   └── Dockerfile
├── nginx/                Nginx 配置（前端 + 漫画静态文件）
├── docker-compose.yml    api + worker + gateway + nginx + mysql + redis + rabbitmq + nacos
└── tools/                复用 comics15 的 Go image-optimizer
```

### 6.8 编码规范

- Java: 常量 `UPPER_SNAKE_CASE`，类 `PascalCase`，方法和变量 `camelCase`
- Vue: Composition API + `<script setup lang="ts">`
- NIO Path/Files 优先于 java.io.File
- 禁止 `as any`、`@ts-ignore`、空 catch、`var`
- 日志使用 `log.info("...{}", value)`，禁止字符串拼接
- **中文注释 + 中文 commit message**

---

## 附录: 消息-状态对照表

| 消息 | 生产者 | 消费者 | import_task 状态变更 | comic 状态变更 |
|------|--------|--------|---------------------|---------------|
| ImportTaskCreated | API | Worker | PENDING | IMPORTING (预创建) |
| TaskStatusChanged(DOWNLOADING) | Worker | API | DOWNLOADING | — |
| TaskStatusChanged(EXTRACTING) | Worker | API | EXTRACTING | — |
| TaskStatusChanged(PARSING) | Worker | API | PARSING | — |
| ComicImported | Worker | API | LQ_GENERATING | READY ✅ |
| ComicImportedProcessed | API | Worker | — | — |
| LQGenerateTask | API | Worker | — | — |
| LQGenerated | Worker | API | SUCCESS | lq_status=READY/PARTIAL |
| TaskStatusChanged(FAILED) | DLQ | API | FAILED | ERROR |
| TaskStatusChanged(CANCELLED) | Worker | API | CANCELLED | — |
| ComicDeleteRequested | API | Worker | — | DELETING |
| ComicDeleted | Worker | API | — | DELETED |
