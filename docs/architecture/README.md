# ComicAtlas 当前架构总览

**适用版本**：2.1+
**最后更新**：2026-09-04
**文档状态**：现行架构基线

本文是 ComicAtlas 架构的统一入口，面向需要理解系统边界、数据流和扩展方式的开发者。接口、数据库字段和 MQ 路由的具体细节，分别以 [API 文档](../api.md)、[数据库 Schema](../database/schema.md) 和 [导入流水线](./02-import-pipeline.md) 为准。

## 1. 一句话理解

ComicAtlas 是一个本地部署的个人漫画仓库平台：前端通过 Gateway 访问阅读服务和管理服务，管理服务负责业务写入与任务编排，Worker 负责文件处理，RabbitMQ 连接耗时操作，MySQL 保存业务数据，Redis 提供缓存与幂等辅助，Nginx 提供漫画文件访问。

最重要的边界是：

- **管理服务写数据库，Worker 不写业务表。**
- **Worker 处理文件，管理服务不直接搬运漫画文件。**
- **阅读服务以读为主，唯一业务写操作是阅读进度。**
- **耗时文件操作统一异步化，通过 MQ 回传结果。**
- **所有导入最终进入 MANAGED 存储。**

## 2. 运行时拓扑

```text
                         ┌──────────────────────┐
                         │ Frontend              │
                         │ Vue 3 + Vite          │
                         └──────────┬───────────┘
                                    │ HTTP
                         ┌──────────▼───────────┐
                         │ Gateway               │
                         │ 路由 + Nacos 发现     │
                         └──────┬─────────┬─────┘
                                │         │
                 /api/**        │         │ /api/manage/**
                                ▼         ▼
                    ┌──────────────┐  ┌──────────────┐
                    │ Reading      │  │ API / Manage │
                    │ Service      │  │ Service      │
                    │ 8011         │  │ 8010         │
                    └──────┬───────┘  └──────┬───────┘
                           │                  │
                           │                  │ MQ 任务/结果
                           │                  ▼
                           │           ┌──────────────┐
                           │           │ Worker       │
                           │           │ 文件处理     │
                           │           └──────┬───────┘
                           │                  │
                           └──────────┬───────┘
                                      │
             ┌────────────────────────┼────────────────────────┐
             ▼                        ▼                        ▼
        ┌─────────┐              ┌─────────┐              ┌─────────┐
        │ MySQL   │              │ Redis   │              │ Nginx   │
        │ 业务数据 │              │ 缓存/幂等 │              │ 文件服务 │
        └─────────┘              └─────────┘              └────┬────┘
                                                               │
                                                               ▼
                                                        ${MANGA_ROOT}
```

### 2.1 模块职责

| 模块 | 主要职责 | 明确不负责 |
|------|----------|------------|
| `frontend` | 阅读端、导入页、任务中心、漫画管理、存储管理 | 不直接访问数据库和文件系统 |
| `gateway` | `/api/**` 与 `/api/manage/**` 路由、服务发现 | 不承载业务逻辑 |
| `reading-service` | 漫画列表、详情、目录、章节阅读、阅读历史 | 不消费 MQ，不执行管理写操作和 Flyway |
| `api-service` | 管理 API、业务写入、任务编排、MQ 结果消费、Flyway | 不直接搬运漫画文件 |
| `worker-service` | 解析、下载、解压、媒体分析、文件搬运、LQ/转码/回收处理 | 不写业务数据库表，不提供业务 HTTP API |
| `comic-shared` | DTO、Result、异常、枚举、实体、Mapper、存储抽象 | 不放跨服务事件实现 |
| `comic-common` | 事件 DTO、MQ 契约、元数据模型、通用工具 | 不承载服务内业务编排 |

### 2.2 数据所有权

| 数据或资源 | 负责方 | 说明 |
|------------|--------|------|
| `comic`、`catalog`、`chapter`、`page` 等业务表 | `api-service` | 管理服务是业务表唯一写入方；阅读历史由 `reading-service` 专门写入 |
| 漫画 HQ/LQ、封面、暂存和回收文件 | `worker-service` | 通过 `StorageService` 统一执行文件操作 |
| Redis 查询缓存 | `reading-service` 为主 | 管理侧负责相关缓存失效；缓存不可作为事实来源 |
| MQ 任务和结果消息 | API/Worker | API 发布任务，Worker 执行并回传结果，API 负责状态落库 |
| `metadata.json`、导入/恢复清单 | Worker 产出，API 消费 | 用于跨服务传递文件处理结果和结构化元数据 |

## 3. HTTP 路由边界

```text
Frontend
  └─ Gateway
      ├─ /api/manage/** ──> api-service:8010
      ├─ /api/**         ──> reading-service:8011
      └─ /files/**       ──> nginx ──> ${MANGA_ROOT}
```

- `/api/manage/**`：管理端所有写操作和管理查询，例如漫画编辑、导入、任务、回收站、存储操作和 DLQ 管理。
- `/api/**`：阅读端查询和阅读历史接口。
- `/files/{root}/{path}`：文件访问 URL，由 `FileUrlResolver` 根据数据库中的存储引用生成，禁止在业务代码中手拼。
- 管理前端当前任务中心路由为 `/manage/tasks`；导入入口为 `/manage/import`。

Gateway 的管理路由必须排在通用阅读路由之前，避免 `/api/manage/**` 被 `/api/**` 误匹配。

## 4. 导入架构

当前支持的来源类型为 `ZIP`、`CBZ`、`DIRECTORY`、`EHENTAI`。CBZ 使用 ZIP 容器格式，并可选携带根目录 `ComicInfo.xml`；EHENTAI 保留后端/Worker 能力，前端暂未提供直接入口。

```text
POST /api/manage/tasks/import
        │
        ▼
API 创建 comic(IMPORTING) + import_task(PENDING)
        │ 发送 comic.import.task.created
        ▼
Worker ImportTaskHandler
        ├─ ZIP / CBZ      -> ZipImportHandler -> 解压
        ├─ DIRECTORY      -> 直接处理
        └─ EHENTAI        -> 下载/解压
                         │
                         ▼
              DirectoryImportHandler
              ├─ DirectoryParser：文件树
              ├─ MetadataAssembler：目录/章节/媒体语义
              ├─ MediaAnalyzer：图片尺寸、视频 ffprobe 元数据
              ├─ 暂存 HQ 文件
              └─ 写 metadata.json 与清单
                         │ 发送 comic.import.task.completed
                         ▼
API ImportEventHandler
              ├─ 读取 metadata.json
              ├─ 写入 catalog/chapter/page
              └─ 按章节发送 finalize 请求
                         │
                         ▼
Worker ImportStorageFinalizeHandler
              └─ globalOrder 暂存目录 -> chapterId 正式目录
                         │ 发送 finalize.completed
                         ▼
API ImportPersistenceService
              └─ 全部章节 READY -> comic READY + task SUCCESS
```

导入使用两阶段文件最终化：

1. Worker 在数据库章节 ID 尚未生成前，将文件暂存到 `HQ/{comicId}/{globalOrder}`。
2. API 根据 `metadata.json` 插入章节，取得不可变 `chapterId`。
3. Worker 按章节逐一将暂存目录移动到 `HQ/{comicId}/{chapterId}`。
4. API 只有在全部章节最终化成功后，才把漫画和导入任务置为成功/可阅读。

因此，`task.completed` 只表示 Worker 阶段完成，不代表漫画已经可以阅读；最终状态必须以章节最终化结果为准。

## 5. 管理任务架构

LQ 生成、LQ 重建、HQ 删除、视频转码、元数据刷新、导出、回收和恢复等耗时操作，统一使用管理任务管线：

```text
管理 API
  -> management_task / management_task_item
  -> Outbox
  -> comic.management.command.requested
  -> Worker ManagementCommandDispatcher
  -> 具体 CommandHandler
  -> command.completed / failed / progress
  -> API Inbox + ManagementCommandResultHandler
  -> 更新任务项、业务状态和派生统计
```

关键约束：

- `management_task` 是任务编排和查询入口，任务项记录每个目标的独立状态。
- Outbox 保证数据库事务提交与消息发布之间可恢复；Inbox 保证结果消费幂等。
- Worker 的取消通过取消标记协作，不把取消/中断误报为普通失败。
- 任务状态、任务阶段和目标资源状态分别由对应枚举和状态机管理，不互相替代。
- 所有命令结果必须经过 API 的状态机和完成处理服务，不能由 Worker 直接修改业务状态。

## 6. 存储与生命周期

### 6.1 统一存储布局

所有导入均采用 `MANAGED` 策略，根目录由 `MANGA_ROOT` 配置，默认值为 `F:/manga`：

```text
${MANGA_ROOT}/
├─ hq/{comicId}/{chapterId}/      # 原始/高质量媒体
├─ lq/{comicId}/{chapterId}/      # 低质量图片
├─ thumbs/                        # 缩略图或封面
├─ metadata/                      # metadata.json、刷新快照
├─ staging/                       # 失败或处理中间产物
├─ trash/                         # 回收站文件
└─ export/{taskId}/               # 导出产物
```

数据库中的 `page.hq_root` 保存根 key（如 `HQ`），`page.hq_path` 保存相对路径。物理路径只能通过配置根目录和 `Path.resolve` 解析，并进行规范化边界校验。

### 6.2 文件与数据库生命周期

```text
STAGING -> READY -> TRASHED -> DELETED
              │         │
              ├─ LQ/HQ/转码等管理操作
              └─ RESTORING（恢复）
```

- 删除语义是进入回收站，不是直接永久删除。
- `purge` 只处理满足状态、保留期和二次确认条件的 `TRASHED` 数据。
- 回收保留期由 `trash.retention-days` 配置，当前默认值为 `0`，不是固定七天。
- 派生统计（HQ/LQ 大小、页数等）统一由 `ComicStatsService` 重算，不在多个业务入口分别维护。
- 文件 URL 统一由 `FileUrlResolver` 生成，Nginx 只负责静态文件映射和缓存。

## 7. MQ 事件边界

MQ 契约集中在 `comic-common` 的常量类和事件 DTO 中。主要消息族如下：

| 消息族 | 作用 | 典型消费者 |
|--------|------|------------|
| `comic.import` | 导入、导入最终化 | Worker 与 API |
| `comic.task` | 导入任务状态和取消 | Worker 与 API |
| `comic.management` | LQ/HQ/转码/刷新/回收等管理命令 | Worker 与 API |
| `comic.export` | 导出任务与产物结果 | Worker 与 API |
| `comic.recovery` | 存储扫描、恢复进度和结果 | Worker 与 API |
| `comic.scan` | 目录扫描预览结果 | Worker 与 API |
| `comic.image` | 视频元数据修复等图像/媒体事件 | Worker 与 API |

除明确没有 DLQ 的任务状态队列外，主队列均配置 DLX/DLQ。消息消费必须具备 ACK、重试、幂等和失败路由；已运行 Broker 中的历史废弃实体不由应用自动删除。

详细 exchange、routing key、queue 对照见 [API 文档 MQ 路由表](../api.md#184-mq-路由表)。

## 8. 阅读链路

```text
浏览器
  -> Gateway /api/**
  -> reading-service
  -> MySQL + Redis 组装列表/详情/目录/章节
  -> FileUrlResolver 生成 /files/{root}/{path}
  -> Nginx
  -> HQ 或 LQ 文件
```

阅读服务按 `chapter.global_order` 计算上一章/下一章，避免使用原始 `chapter_no` 排序。阅读进度写入 `reading_history`，其他业务写操作回到管理服务。

## 9. 前端架构

前端采用 Vue 3 Composition API、TypeScript、Vite、Element Plus 和 Pinia：

- 阅读域：首页、漫画库、详情、目录、阅读器、阅读历史。
- 管理域：漫画工作区、导入、任务中心、存储管理、回收站、DLQ 和设置。
- API 调用集中在 `frontend/src/services/`，状态集中在 `frontend/src/stores/`。
- 阅读器按媒体类型渲染图片和视频，文件 URL 由后端返回，不在前端拼接物理路径。
- 管理端按钮应以允许操作查询和任务状态为准，不能只根据页面展示状态自行推断权限。

当前前端目录、路由和 Store 详情见 [前端技术架构](../frontend/08-frontend-architecture.md)。

## 10. 部署与启动关系

```text
基础设施：MySQL + Redis + RabbitMQ + Nacos
                    │
                    ▼
Gateway + API Service + Reading Service + Worker Service + Nginx
```

- Gateway、API、Reading、Nginx 组成对外访问链路。
- Worker 必须能访问 MQ、`MANGA_ROOT` 和所需的外部处理工具（ffmpeg/ffprobe、图片优化器、下载工具）。
- API 与 Reading 访问 MySQL；Worker 仅允许按需要读取 MySQL，禁止 INSERT、UPDATE、DELETE、DDL 和事务写操作。
- 存储根迁移只需调整 `storage.roots.HQ.path` 等配置，不修改数据库中的相对路径。
- 生产发布树和开发辅助树边界以 [开发流程](../development-guide.md) 中的发布规则为准。

## 11. 扩展规则

新增功能时按以下顺序判断归属：

1. 需要对外 HTTP 协议：放入 API 或 Reading 对应 Controller，业务编排下沉到 Service。
2. 需要长时间文件、网络或外部进程：创建管理任务，通过 MQ 交给 Worker。
3. 需要改变业务事实：由 API 在事务中落库，并通过 Outbox 发布后续事件。
4. 需要新增跨服务数据：先定义 `comic-common` 事件/契约，再实现生产者和消费者。
5. 需要新增文件布局：通过 `StorageService`、`StorageRoot`、`StorageRef` 和 URL Resolver，不能自行拼接路径。
6. 需要新增状态：同步更新 Java 枚举、数据库迁移、事件 DTO、状态机、API 文档和测试。

禁止的 shortcut：

- Worker 直接写业务数据库。
- Controller 直接访问 Mapper 或执行文件 IO。
- 在事务内下载、解压、调用外部进程或搬运大文件。
- 在前端或 Controller 手写 `/files/` 物理路径。
- 为单个耗时操作重新创建一套独立 MQ 链路。

## 12. 相关文档与事实来源

| 主题 | 当前事实来源 |
|------|--------------|
| HTTP 接口、状态和 MQ 路由 | [docs/api.md](../api.md) |
| 用户操作和部署准备 | [docs/user-guide.md](../user-guide.md) |
| 导入详细流程 | [02-import-pipeline.md](./02-import-pipeline.md) |
| 存储抽象和目录布局 | [03-storage.md](./03-storage.md) |
| 媒体任务和生命周期 | [09-media-lifecycle-capabilities.md](./09-media-lifecycle-capabilities.md) |
| 数据库表与枚举 | [database/schema.md](../database/schema.md) |
| 前端目录、路由和状态 | [frontend/08-frontend-architecture.md](../frontend/08-frontend-architecture.md) |
| 跨服务模块边界 | [shared-module-boundaries.md](./shared-module-boundaries.md) |
| [后端代码分类](backend-package-organization.md) | 业务域、框架职责与文件归属 |
| [后端待解耦清单](backend-decoupling.md) | 源码标记、拆分约束与回归要求 |
| [后端三层架构检查](backend-layer-audit.md) | 接口层越界、持久化框架类型泄漏和处理约束 |
| 发布、回滚和故障处理 | [operations/management.md](../operations/management.md) |

0.2 时代的产品、导航、阅读、管理、领域、API、前端与迁移设计稿已统一移入 [历史架构归档](archive/v0.2/README.md)，用于追溯决策背景。临时实施计划不纳入当前架构入口。
