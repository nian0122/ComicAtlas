# 接口收敛与存储域模块化设计

**状态**: 历史归档

**日期**: 2026-08-06
**状态**: 待审阅
**范围**: 后端 API 模块化重构（前端不在本次范围）

---

## 1. 背景与动机

ComicAtlas 经 v1.0 管理控制台迭代后，API 层出现职责错位与功能重复。经 8 项专项调查（证据：`.omo/evidence/` 与源码对照），确认以下问题：

| # | 问题 | 证据 |
|---|------|------|
| P1 | `GET /api/comics/{id}/chapters/{cid}/pages` 为死端点，前端零调用，阅读已由 `GET /api/chapters/{id}` 承担 | `ComicController.java:73` |
| P2 | 刷新元数据双路径重复：`POST /api/admin/comics/{id}/refresh-metadata`（同步直扫、无任务记录）与 `METADATA_REFRESH` 任务管线（completed 业务为空实现）并存且行为不一致 | `AdminServiceImpl.java:254` vs `ManagementCommandResultHandler.java:180` |
| P3 | `ComicListVO` 混合阅读端与管理端字段；`lifecycle/activeTask/allowedOperations` 前端零消费；前端读 `comic.status` 但后端只返回 `lifecycle`（字段名不匹配，前端读到一律 undefined） | `ComicListVO.java:15-33` |
| P4 | 视频转码 HTTP 层仅支持漫画级，service 层有 `requestTranscodeForMedia` 但无章节/media 级端点 | `MediaOperationCommandService.java:261` |
| P5 | 存储操作端点错位归属：`importer` 包挂着 `LqController`/`HqDeleteController`，`admin` 包挂着 `refresh-metadata`/`transcode-videos` | 包结构 |
| P6 | 转码完成后 media 元信息不完整：硬编码 `container=mp4/videoCodec=h264/audioCodec=aac`，未更新 `duration/fileSize`，不刷新 metadata.json | `ManagementCommandResultHandler.java:243-260` |
| P7 | 转码后 metadata.json 过期：文件已替换为 .mp4，但 metadata.json 中仍是转码前信息 | `TranscodeCommandHandler.java:151-155` |

## 2. 目标与非目标

### 目标
1. 后端包按功能域重组，存储操作收敛到独立 `storage` 域，域间解耦（阿里 Java 规范）
2. 存储操作统一 URL 形态 `POST /api/storage/{operation}/{targetType}/{targetId}`
3. 补全转码后 media 元信息自动同步（DB + metadata.json）
4. 修复已知缺陷（死端点、字段不匹配、双路径重复）

### 非目标（明确排除）
- **前端改造**：本次不改任何前端代码，前端继续调用旧端点（后端保留 deprecated 兼容）
- **删除数据库不删除本地文件**：用户确认搁置
- **轻量导入模式**：现有导入流程正确，保持现状
- **media 级存储操作**（按单个 media 触发 LQ/转码/HQ 删除）：用户清单未要求，service 层已有能力，本次不新增端点
- **手动触发的 media 元信息刷新端点**（按漫画/章节手动刷新单个 media 元信息）：不新增——转码后自动同步（第 6 节）覆盖视频场景，图片元信息由漫画级 `refresh-metadata` 全量扫盘覆盖

## 3. 域包结构（模块化）

`api-service` 包按功能域重组：

```
com.comicatlas.api
├── reading/        阅读域（只读查询）
│   ├── controller/     ComicQueryController、ReaderController、HistoryController
│   ├── service/        ComicQueryService、ReaderService、HistoryService
│   └── dto/            ComicListVO（阅读字段）、ReaderDTO、CatalogNodeVO
├── tag/             标签域
│   ├── controller/     TagController
│   └── service/        TagService
├── category/        分类域
│   ├── controller/     CategoryController
│   └── service/        CategoryService
├── management/      管理域（CRUD + 任务 + 回收）
│   ├── comic/          ComicController（写操作）、CatalogManagement、ChapterManagement、MediaManagement
│   ├── task/           ManagementTaskController、BatchOperationController
│   ├── trash/          TrashLifecycleController
│   ├── operation/      OperationPolicyService、MediaOperationCommandService（内部协作）
│   └── event/          ManagementCommandResultHandler
├── storage/         存储操作域（本次新建/收敛）
│   ├── controller/     StorageOperationController（统一入口）、StorageStatsController
│   ├── service/        LqOperationService、TranscodeOperationService、HqDeleteOperationService、
│   │                   ExportOperationService、MetadataRefreshService、MediaMetadataSyncService
│   ├── event/          TranscodeCompletedHandler（增强）
│   └── dto/            OperationSubmitResult、RefreshMetadataResultVO
├── import/          导入域
│   ├── controller/     ImportController、DirectoryScanTaskController、RecoveryTaskController
│   ├── service/        ImportService、RecoveryTaskService
│   └── event/          ImportEventHandler、RecoveryEventHandler
├── admin/           运维域（收窄）
│   ├── controller/     AdminDlqController、SettingsController
│   └── service/        DlqService、SettingsService
├── common/          公共层：Result、BusinessException、状态机、枚举
└── config/          配置
```

### 解耦规则（阿里 Java 规范）
1. **分层**：Controller（薄层，只收参返结果）→ Service（业务）→ Mapper（数据），禁止跨层调用
2. **域间隔离**：`storage` 域不依赖 `import`/`admin` 包；域间协作通过 MQ 事件或公共接口，禁止直接 `new` 他域 Service
3. **统一返回** `Result<T>`；枚举用 Java enum（DB VARCHAR）；状态迁移走统一状态机（`ManagementStateMachine`）
4. **代码质量门禁**：新代码过 p3c（Checkstyle 已接入 `mvnw verify`），测试随行

### 类迁移映射（本次执行）
| 现有类 | 新归属 |
|--------|--------|
| `LqController`（importer） | `storage/controller/`（合并入 StorageOperationController） |
| `HqDeleteController`（importer） | `storage/controller/`（合并入 StorageOperationController） |
| `AdminController.refresh-metadata` | `storage/controller/`（转 MetadataRefreshService，旧端点 deprecated） |
| `AdminStorageController.transcode-videos` | `storage/controller/`（转 TranscodeOperationService） |
| `ExportController`（export 包） | `storage/controller/`（转 ExportOperationService） |
| `AdminService.getStorageStats` | `storage/controller/`（转 StorageStatsController） |
| `ComicController` 列表/详情（`comic` 包） | 拆分为 `reading`（查询）与 `management/comic`（写）两部分 |
| `TagController`/`CategoryController` | **保留原位**（`comic/controller`），本次不迁移——标签/分类读写一体、阅读端与管理端共用，无解耦收益 |

## 4. 存储操作统一 URL

所有存储优化操作收敛为 **`POST /api/storage/{operation}/{targetType}/{targetId}`**（`targetType = comics | chapters`）：

| 操作 | 新端点 | 原端点（deprecated 保留） |
|------|--------|--------------------------|
| 生成 LQ | `POST /api/storage/lq/comics/{id}`、`/chapters/{id}` | `/comics/{id}/lq`、`/chapters/{id}/lq` |
| 视频转码 | `POST /api/storage/transcode/comics/{id}`、`/chapters/{id}` | `/admin/storage/comics/{id}/transcode-videos` |
| 删除 HQ 保留 LQ | `POST /api/storage/delete-hq/comics/{id}`、`/chapters/{id}` | `/comics/{id}/delete-hq`、`/chapters/{id}/delete-hq` |
| 导出漫画 | `POST /api/storage/export/comics/{id}` | `/comics/{id}/export` |
| 导出任务查询 | `GET /api/storage/export/comics/{id}/tasks`、`GET /api/storage/export/tasks/{taskId}`、`GET .../download`、`POST .../open` | `/comics/{id}/exports`、`/export/{taskId}...` |
| 刷新 Metadata | `POST /api/storage/refresh-metadata/comics/{id}` | `/admin/comics/{id}/refresh-metadata` |
| 存储统计 | `GET /api/storage/stats` | `/admin/storage/stats` |

实现约束：
- 后端一个 `StorageOperationController` 薄层，路由到 `storage/service/` 下各领域 Service
- 各 Service 统一内部走 ManagementTask 任务管线（复用 `MediaOperationCommandService` 的建单+发命令机制），响应统一 `OperationSubmitResult`
- 新端点不新增数据库表、不改变现有 MQ 路由

## 5. 功能收敛清单

| # | 项 | 动作 | 验收 |
|---|----|------|------|
| F1 | 删除 `GET /api/comics/{id}/chapters/{cid}/pages` | 删除方法 + 测试 + api.md 清理 | 全仓无引用 |
| F2 | `ComicListVO` 字段修正 | 移除无消费者字段 `activeTask`/`allowedOperations`；**`lifecycle` 字段重命名为 `status`**（对齐前端契约 `comic.status`，前端零改动即修复字段不匹配） | 前端列表状态正常显示 |
| F3 | `refresh-metadata` 双路径收敛 | 核心逻辑收敛为 `storage/service/MetadataRefreshService`（CAS 锁 + 扫盘更新 DB + 发 `metadata.refresh.requested`）；补全 `METADATA_REFRESH` completed 业务（调用同一服务）；旧 `AdminController.refresh-metadata` 保留 deprecated 转发 | 两条入口行为一致 |
| F4 | 转码补章节级 | 新 URL 形态已含 `transcode/chapters/{id}` | 章节级转码可触发 |

## 6. 转码后 media 元信息自动同步

### 现状缺口
`applyTranscodeCompleted`（`ManagementCommandResultHandler.java:243-260`）硬编码 container/codec，未更新 duration/fileSize，不刷新 metadata.json。

### 目标机制（事件驱动）

```
Worker TranscodeCommandHandler.processPage：
  转码成功 → ffprobe 实测新文件（复用 MediaAnalyzer/ffprobe，获取 duration/container/
  videoCodec/audioCodec/fileSize）→ 随 completed 事件回传

API ManagementCommandResultHandler.applyTranscodeCompleted（增强）：
  ✅ 更新 media：transcodeStatus、container、videoCodec、audioCodec（实测值替代硬编码）
  ✅ 新增更新：duration、fileSize（转码后新文件实际值）、hqPath
  ✅ 发 MQ `metadata.refresh.requested` → Worker ExportCollector 重新导出该漫画 metadata.json
  （复用现有通道，见 worker `MetadataRefreshHandler`，无需新队列）
```

### 实现要点
- **事件扩展**：仿照 `MediaUploadCompletedEvent.MediaAnalysisResult` 先例，为转码 completed 事件携带可选 media 元数据（新增字段保持 Jackson 向后兼容，老消费方忽略 null）
- **Worker 端**：`TranscodeCommandHandler` 转码成功后对 `newHqFile` 执行 ffprobe 探测，填充元数据；失败时降级为 null（API 侧回退为当前硬编码值）
- **comic-common**：扩展 `ManagementCommandCompletedEvent`（或新增 `TranscodeCompletedDetail` 可选字段），更新事件契约测试
- **API 端编排**：`applyTranscodeCompleted` 增强后委托 `storage/service/MediaMetadataSyncService`——该服务职责单一：①用事件携带的实测值更新 media 元信息字段 ②发 `metadata.refresh.requested` MQ 触发 metadata.json 重导出。`MediaMetadataSyncService` 同时供转码 completed 与将来其他元信息变更场景复用（单一职责、可独立测试）

### 图片元信息
图片尺寸/大小变更仍由漫画级 `refresh-metadata`（`MetadataRefreshService`）全量扫盘覆盖，本机制只针对转码（视频）。

## 7. 兼容与迁移策略

| 阶段 | 动作 |
|------|------|
| 本次上线 | 新端点 `/api/storage/*` 启用；旧端点保留并标注 `@Deprecated`（前端继续可用）；死端点 `pages` 直接删除 |
| 兼容窗口 | 旧端点列入 `docs/api.md` §19.2 兼容窗口表，标注退役计划（等待前端迁移） |
| 后续（独立任务） | 前端 `services/storage.ts` 等改用新端点后，移除旧端点 |

## 8. 测试与验证

1. **单元测试**：各 StorageOperationService 建单/拒绝分支；`applyTranscodeCompleted` 元数据更新断言（duration/fileSize/实测 codec）
2. **Worker 测试**：`TranscodeCommandHandler` 转码后 ffprobe 回传（mock MediaAnalyzer）
3. **契约测试**：扩展 `ManagementCommandCompletedEvent` 后更新事件契约测试（`ManagementEventContractTest`）
4. **兼容测试**：旧端点 deprecated 转发行为回归
5. **门禁**：`mvnw clean verify` 全绿（Checkstyle 0 违规），阿里 p3c 标准

## 9. 风险与决策记录

| 决策 | 内容 | 理由 |
|------|------|------|
| D1 | 域模块化 + URL 统一（用户选定方案 2） | 按阿里规范彻底解耦，符合"模块化、解耦合"诉求 |
| D2 | 前端不在本次范围 | 用户明确"先不管前端"；旧端点 deprecated 保留保证前端可用 |
| D3 | `ComicListVO` 输出 `status`（序列化名）而非改前端 | 后端对齐前端契约，前端零改动即修复字段不匹配 |
| D4 | 转码后自动同步 media 元信息（用户选定） | 事件驱动闭环，metadata.json 自动刷新 |
| D5 | 轻量导入保持现状 | 用户确认"现有实现正确" |
| D6 | 删除 DB 不删文件搁置 | 用户确认"先不管" |
| D7 | 补转码章节级（不含 media 级） | 用户清单明确"按漫画/按章节"，未含 media 级 |

### 风险
- **包移动风险**：大量类迁移可能引入编译问题——分步迁移（先建 storage 域，再移类，最后删旧）
- **事件契约变更**：`ManagementCommandCompletedEvent` 加字段需保持 Jackson 向后兼容（nullable 字段），老消费方（若存在）不受影响
- **行为一致性**：refresh-metadata 双路径收敛后行为需一致，用集成测试锁定

## 10. 后续（不在本次范围）
- 前端 `services/storage.ts` 迁移到 `/api/storage/*`
- 前端服务层按域拆分（reading/management/storage/import/admin）
- 移除 deprecated 旧端点
