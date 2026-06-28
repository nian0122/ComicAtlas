# PROJECT KNOWLEDGE BASE - ComicAtlas

**Generated:** 2026-06-28
**Branch:** main
**语言**: 始终使用中文对话、注释、提交信息。

## OVERVIEW
AI 驱动个人漫画仓库平台。统一接收不同来源（ZIP/本地目录）的漫画，完成导入、管理和阅读。
Spring Boot 3 + Vue3 + RabbitMQ + MySQL + Redis。

## STRUCTURE
```
comic-atlas/
├── api-service/             # Spring Boot API: 漫画CRUD + 导入任务 + 阅读记录 + MQ消费
├── worker-service/          # Spring Boot Worker: 文件处理 + MQ消费 + DirectoryParser
├── gateway/                 # Spring Cloud Gateway: 路由 + Nacos发现
├── frontend/                # Vue3/Vite: 漫画列表 + 阅读器 + 导入管理 + Dashboard
├── docs/superpowers/        # 设计文档与实现计划
│   ├── specs/               # 设计规范
│   └── plans/               # 实现计划
├── nginx.conf               # Nginx: 前端 + API代理 + 漫画静态文件
└── docker-compose.yml       # MySQL + Redis + RabbitMQ + Nacos + Nginx
```

## WHERE TO LOOK
| 任务 | 位置 | Notes |
|------|------|-------|
| 漫画 CRUD API | `api-service/.../controller/ComicController.java` | list/detail/delete/chapterPages |
| 漫画 Service | `api-service/.../service/impl/ComicServiceImpl.java` | 动态构建 HQ/LQ URL |
| 导入 API | `api-service/.../controller/ImportController.java` | ZIP/REGISTER/EHENTAI |
| 导入 Service | `api-service/.../service/impl/ImportServiceImpl.java` | sourceType 路由 + 去重 |
| MQ 消费 ComicImported | `api-service/.../event/ImportEventHandler.java` | 读 metadata.json → insert DB |
| Worker 主入口 | `worker-service/.../event/ImportTaskHandler.java` | sourceType 路由 |
| 目录解析 | `worker-service/.../parse/DirectoryParser.java` | findComicRoot + hq/lqStatus |
| 双模式导入 | `worker-service/.../handler/DirectoryImportHandler.java` | importManaged / importExternal |
| ZIP 解压导入 | `worker-service/.../handler/ZipImportHandler.java` | 解压→委托 DirectoryImportHandler |
| 元数据模型 | `worker-service/.../parse/ComicMetadata.java` | record: title+chapters+pages |
| 路径构建 | `worker-service/.../common/FilePathBuilder.java` | hq/lq/thumb 路径规则 |
| 前端路由 | `frontend/src/router/index.ts` | 7 routes (lazy loaded) |
| Pinia Store | `frontend/src/stores/` | comic/reader/import/history/dashboard/tag/app |
| API 服务 | `frontend/src/services/api.ts` | Axios 封装 |
| 类型定义 | `frontend/src/types/index.ts` | 13 interfaces + STATUS_COLOR_MAP |

## IMPORT FLOW
```
POST /api/tasks/import { sourceType, sourcePath }
  ↓
ImportServiceImpl: 预创建 comic + import_task → 发 MQ
  ↓
Worker ImportTaskHandler: sourceType 路由
  ├─ ZIP → ZipImportHandler → extract → importManaged → 搬 hq/ → metadata.json
  └─ REGISTER → importExternal → 不动文件 → metadata.json
  ↓
API ImportEventHandler: 读 metadata.json → INSERT comic + chapter + page
```

## STORAGE
| storage_type | 含义 | 文件位置 |
|-------------|------|---------|
| MANAGED | ComicAtlas 管理 | `D:/manga/hq/{comicId}/{chapterNo}/` |
| EXTERNAL | 外部引用 | `F:/games/comics/h_photograph/...` |

配置: `worker.storage-roots.LOCAL=F:/games/comics`

## RABBITMQ
| Exchange | RoutingKey | Queue | Consumer |
|----------|-----------|-------|----------|
| comic.import | task.created | import.task.queue | Worker ImportTaskHandler |
| comic.import | task.completed | import.result.queue | API ImportEventHandler |
| comic.task | status.changed | task.status.queue | API ImportEventHandler |

序列化: Jackson2JsonMessageConverter（JSON，非 Java 序列化）

## CONFIG / ENV
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MANGA_ROOT` | `D:/manga` | 漫画存储根目录 |
| `PROXY_HOST` | `127.0.0.1` | HTTP 代理 |
| `PROXY_PORT` | `7897` | HTTP 代理端口 |
| `ARIA2C_PATH` | `worker-service/aria2-.../aria2c.exe` | aria2c 路径 |
| `LOCAL_ROOT` | `F:/games/comics` | 外部漫画库根目录 |

## CONVENTIONS
- Java: Lombok, NIO Path/Files, MyBatis Plus LambdaQueryWrapper
- Vue: Composition API + `<script setup lang="ts">` + Element Plus
- 禁止: `as any`, `@ts-ignore`, 空 catch, `var`
- 日志: `log.info("...{}", value)`, 禁止字符串拼接
- 提交: 中文 commit message
- 包名: `com.comicatlas.api.importer`（非 `import`，Java 关键字冲突）

## ANTI-PATTERNS
- 禁止 Worker 直接写 MySQL → 全部通过 MQ 事件回 API
- 禁止在 page 表存绝对路径 → 动态拼接 URL
- 禁止 LQ 自动生成 → 手动触发
- 禁止 `spring.cloud.nacos.config` 强制 import → 已 disabled
