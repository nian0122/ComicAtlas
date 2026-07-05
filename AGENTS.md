# PROJECT KNOWLEDGE BASE - ComicAtlas

**Updated:** 2026-07-03
**Branch:** main
**语言**: 始终使用中文对话、注释、提交信息。

## OVERVIEW
AI 驱动个人漫画仓库平台。统一接收 ZIP/目录来源的漫画，完成导入、管理和阅读。
Spring Boot 3 + Vue3 + RabbitMQ + MySQL + Redis。
**所有导入统一 MANAGED 存储**——文件搬入 `D:/manga/hq/{comicId}/{chapterId}/`。

## STRUCTURE
```
comic-atlas/
├── api-service/             # 漫画CRUD + 导入 + Catalog + Reader + LQ + MQ消费
├── worker-service/          # 文件处理 + MQ消费 + DirectoryParser + StorageService
├── gateway/                 # Spring Cloud Gateway: 路由 + Nacos发现
├── frontend/                # Vue3/Vite: 列表 + 详情(CatalogTree) + 阅读器 + 导入
├── docs/                    # api.md + superpowers/specs|plans
├── nginx.conf               # /files/{root}/{path} → alias /storage/{root}/
└── docker-compose.yml       # MySQL + Redis + RabbitMQ + Nacos + Nginx
```

## WHERE TO LOOK
| 任务 | 位置 | Notes |
|------|------|-------|
| 漫画列表/详情 | `api-service/.../controller/ComicController.java` | list/detail/delete |
| 目录树 | `api-service/.../controller/CatalogController.java` | GET `/comics/{id}/catalog` |
| 章节阅读 | `api-service/.../reader/controller/ReaderController.java` | GET `/chapters/{id}` 返回 pages+prev/next |
| Catalog Service | `api-service/.../service/CatalogService.java` | buildTree 组装 ViewModel |
| Reader Service | `api-service/.../reader/service/ReaderService.java` | 按 global_order 取 prev/next |
| 导入 API | `api-service/.../controller/ImportController.java` | POST sourceType+sourcePath |
| 导入 Service | `api-service/.../service/impl/ImportServiceImpl.java` | 预创建 comic+task → MQ |
| LQ 手动触发 | `api-service/.../controller/LqController.java` | POST /comics/{id}/lq |
| MQ 消费 | `api-service/.../event/ImportEventHandler.java` | 读 metadata.json → INSERT |
| Worker 入口 | `worker-service/.../event/ImportTaskHandler.java` | sourceType 路由到统一 handler |
| 目录解析 | `worker-service/.../parse/DirectoryParser.java` | 输出 DirectoryTree（纯树，无业务语义） |
| 元数据组装 | `worker-service/.../parse/MetadataAssembler.java` | DirectoryTree → ComicMetadata（注入 Catalog/Chapter） |
| 统一导入 | `worker-service/.../handler/DirectoryImportHandler.java` | handle() 解析→搬文件→写metadata |
| ZIP 导入 | `worker-service/.../handler/ZipImportHandler.java` | 解压→委托 DirectoryImportHandler |
| 存储服务 | `worker-service/.../storage/LocalStorageService.java` | store/resolve/exists/delete |
| 存储根 | `worker-service/.../storage/StorageRoot.java` | path + resolve() + exists() |
| 文件引用 | `worker-service/.../storage/StorageRef.java` | rootKey + relativePath |
| URL 解析 | `api-service/.../storage/FileUrlResolver.java` | Page → /files/{root}/{path} |
| 路径布局 | `api-service/.../storage/StorageLayout.java` | forPage(comicId, chapterId, imageName) |
| 元数据模型 | `worker-service/.../parse/ComicMetadata.java` | catalogs + chapters + pages |
| 导入上下文 | `worker-service/.../parse/ImportContext.java` | sourceType + sourcePath |
| 前端路由 | `frontend/src/router/index.ts` | 7 routes |
| Pinia Store | `frontend/src/stores/` | comic/reader/import/history/dashboard/tag/app |
| API 服务 | `frontend/src/services/api.ts` | comic/catalog/reader/import/lq/history |
| 类型定义 | `frontend/src/types/index.ts` | CatalogNode/ChapterRef/ReaderDTO 等 |

## IMPORT FLOW
```
POST /api/tasks/import { sourceType:"ZIP"|"DIRECTORY", sourcePath:"D:/..." }
  ↓
ImportServiceImpl: INSERT comic(IMPORTING) + import_task(PENDING) → MQ
  ↓
Worker ImportTaskHandler: sourceType 路由
  ├─ ZIP → ZipImportHandler → extract → DirectoryImportHandler.handle()
  └─ DIRECTORY → DirectoryImportHandler.handle()
  ↓
DirectoryImportHandler: DirectoryParser → MetadataAssembler → 搬文件到 HQ → metadata.json
  ↓
MQ task.completed
  ↓
API ImportEventHandler: 读 metadata.json → INSERT catalog+chapter+page, comic→READY
```

## STORAGE
所有漫画统一 MANAGED，文件搬入 `D:/manga/hq/{comicId}/{chapterId}/`。

**DB 存储**：
- `comic.storage_policy` = `MANAGED`
- `page.hq_root` = `HQ`，`page.hq_path` = `{comicId}/{chapterId}/001.jpg`

**迁移**：只改 `storage.roots.HQ.path` 配置，不改 DB。

## URL 规范
```
/files/{rootKey_lc}/{relativePath}
```
- `/files/hq/` → alias `D:/manga/hq/` (60d cache)
- `/files/lq/` → alias `D:/manga/lq/` (30d cache)
- `/files/thumbs/` → alias `D:/manga/thumbs/` (7d cache)

URL 统一由 `FileUrlResolver.resolve(page)` 生成，不手拼。

## RABBITMQ
| Exchange | RoutingKey | Queue | Consumer |
|----------|-----------|-------|----------|
| comic.import | task.created | import.task.queue | Worker ImportTaskHandler |
| comic.import | task.completed | import.result.queue | API ImportEventHandler |
| comic.task | status.changed | task.status.queue | API ImportEventHandler |
| comic.image | lq.generate | lq.generate.queue | Worker (手动触发) |

序列化: Jackson2JsonMessageConverter

## CONFIG / ENV
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MANGA_ROOT` | `D:/manga` | 存储根目录，Worker 写 / Nginx 读 |
| `PROXY_HOST` | `127.0.0.1` | HTTP 代理 |
| `PROXY_PORT` | `7897` | HTTP 代理端口 |
| `ARIA2C_PATH` | `worker-service/aria2-.../aria2c.exe` | aria2c 路径 |

## DB SCHEMA 要点
- `catalog` 表：comic_id, parent_id, title, sort_order（可选目录树）
- `chapter` 表：catalog_id(nullable), sort_order, global_order（全书阅读顺序）
- `chapter.chapter_no` = 原始编号，不参与排序。排序只用 `global_order`
- `page` 表：hq_root, hq_path（替代旧 image_name）
- `page.lq_status` = NOT_GENERATED（不自动生成 LQ）
- `import_task` 表：source_type, source_path（修复 retry 硬编码问题）

## CONVENTIONS
- Java: Lombok, NIO Path/Files, MyBatis Plus LambdaQueryWrapper
- Vue: Composition API + `<script setup lang="ts">` + Element Plus
- 枚举: Java `enum` + DB `VARCHAR`，禁止 MySQL `ENUM`
- 提交: 中文 commit message
- 包名: `com.comicatlas.api.importer`（非 `import`，关键字冲突）

## ANTI-PATTERNS
- 禁止 Worker 直接写 MySQL → 全部通过 MQ 事件回 API
- 禁止 LQ 自动生成 → 手动触发
- 禁止 URL 手拼 → 统一走 `FileUrlResolver`
- 禁止 `spring.cloud.nacos.config` 强制 import → 已 disabled
- 禁止 `page` 表存绝对路径 → 用 `hq_root` + `hq_path` 相对路径
