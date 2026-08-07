# worker-service 模块化重构设计

**日期**: 2026-08-07
**状态**: 设计待审阅
**范围**: worker-service 包结构重组（纯包移动 + import 同步，零逻辑变更）

## 1. 背景与动机

对 `worker-service` 整体目录设计评估发现 3 个结构性弱点：

| # | 问题 | 现状 |
|---|------|------|
| A | **`event` 包职责混杂** | 22 类混 3 种角色：12 个 MQ 消费者（协议入口）+ 8 个命令执行器（被 `ManagementCommandDispatcher` 路由的**业务执行逻辑**，26 处 import 业务域）+ 2 个事件发布器 |
| B | **`file` 包过大** | 30 类塞 5 个互不相同的业务域：导入（handler/manifest/parse）、下载（download）、存储（storage）、视频转换（transcode）、回收站（trash） |
| C | **AGENTS.md 知识库过时** | 写 `file/storage/LocalStorageService`（实际 `StorageService`）、`common/FilePathBuilder`（已删）、未反映 entity/mapper/config 与命令执行器角色 |

## 2. 目标结构（按阿里规范：包名小写、按业务边界分包、避免 Java 关键字冲突）

```
worker/
├── config/       # 不变（6）
├── process/      # 不变（ExternalProcessRunner）
├── image/        # 不变（ImageOptimizer）
├── export/       # 不变（8）
├── entity/ + mapper/  # 不变（12，Export* 历史命名非本次范围）
├── common/       # 不变（ComicTitleSanitizer）
├── event/        # 14 类：12 MQ 消费者 + ManagementCommandDispatcher + 2 发布器
├── command/      # 新：8 个命令执行器（从 event 拆出）
├── importer/     # 新：导入域（handler 2 + manifest 2 + parse 导入 4）
├── media/        # 新：MediaAnalyzer + ComicMetadata（跨域媒体元数据）
├── storage/      # 新：file/storage 9 类提升
└── file/         # 剩 4 子包 11 类：download(7)/extract(2)/transcode(1)/trash(1)
```

## 3. 拆分决策

| 决策 | 依据 |
|------|------|
| 导入域 → `importer` | `import` 是 Java 关键字；对齐 api 的 `com.comicatlas.api.importer`（AGENTS.md 已注明关键字冲突） |
| 命令执行器 → `command` | 8 类均为 `XxxCommandHandler`，包名与类名呼应，语义最清晰 |
| `MediaAnalyzer`/`ComicMetadata` → 独立 `media` | 被 importer（MetadataAssembler）+ command（Transcode/MediaUpload）+ event（VideoMetadataFix）三方依赖——独立"媒体元数据"层，避免 command→importer 跨域依赖 |
| `file/storage` → 顶层 `storage` | 与 api 的 storage 域对齐；存储是 11 处引用的基础域，不该埋在 file 下 |

## 4. 移动明细（27 类，纯包移动 + import 同步）

### 4.1 `event` → `command`（8 类）

- `TranscodeCommandHandler`、`TrashCommandHandler`、`RestoreCommandHandler`、`PurgeCommandHandler`、`HqDeleteCommandHandler`、`LqCommandHandler`、`MediaUploadCommandHandler`、`MetadataRefreshCommandHandler`
- package：`com.comicatlas.worker.event` → `com.comicatlas.worker.command`
- 更新：`event.ManagementCommandDispatcher`（注入 8 个）、`ManagementCommandPublisher`（发布器，command 执行器调用）——import 更新
- `event` 剩：12 MQ 消费者 + `ManagementCommandDispatcher` + `ManagementCommandPublisher`/`TaskStatusPublisher`

### 4.2 `file/storage` → `storage`（9 类）

- `StorageProperties`、`StorageRef`、`StorageRoot`、`StorageService`、`TransferService`、`TransferMode`、`SafeMoveStrategy`、`PathTraversalException`、`ExportFileResolver`
- package：`com.comicatlas.worker.file.storage` → `com.comicatlas.worker.storage`
- 更新 11 个引用方 import：`file/trash/TrashManifestStore`、`export/ExportService`、`event` 8 个（LqGenerate/Delete/HqDelete/HqDeleteCommand/TrashCommand/LqCommand/MediaUploadCommand/RestoreCommand）、`importer/DirectoryImportHandler`

### 4.3 导入域 → `importer`（8 类）

- `DirectoryImportHandler`、`ZipImportHandler`（原 file/handler）
- `ImportManifest`、`ImportManifestManager`（原 file/manifest）
- `DirectoryParser`、`DirectoryTree`、`ImportContext`、`MetadataAssembler`（原 file/parse）
- package：→ `com.comicatlas.worker.importer`
- 更新引用方：`event.ImportTaskHandler`（handler 2 + ImportContext）、`importer` 内部（DirectoryImportHandler → manifest/parse）、`file/transcode`（VideoNormalizer 被 DirectoryImportHandler 调，同向依赖不变）

### 4.4 `MediaAnalyzer` + `ComicMetadata` → `media`（2 类）

- package：`com.comicatlas.worker.file.parse` → `com.comicatlas.worker.media`
- 更新引用方：`importer.MetadataAssembler`（analyze）、`command.TranscodeCommandHandler`/`MediaUploadCommandHandler`、`event.VideoMetadataFixHandler`

### 4.5 `file` 剩余（11 类，保持）

- `download/`（7）：EhentaiDownloadService、DownloadContext、DownloadStrategy、GalleryMetadata、HttpDownloader、ArchiveDownloader、TorrentDownloader
- `extract/`（2）：ArchiveExtractor、ZipExtractor
- `transcode/`（1）：VideoNormalizer
- `trash/`（1）：TrashManifestStore

> 校验：storage 9 + importer 8 + media 2 + file 剩余 11 = 30 ✓；event 22 − command 8 = 14 ✓。

## 5. 影响面与验证

- 移动 **27 个类**（event 拆 8 → command；file 拆 19 → storage 9 / importer 8 / media 2）；更新约 **25 个文件** import
- **零逻辑变更**（纯包移动），编译期兜底（import 错误即暴露）
- 验证：
  1. `.\mvnw -pl worker-service -am test -DfailIfNoTests=false` 全绿（基线 53）
  2. grep 无旧包引用残留：`worker\.file\.storage`、`worker\.file\.(handler|manifest)`、`worker\.file\.parse\.(DirectoryParser|DirectoryTree|ImportContext|MetadataAssembler)`、`worker\.event\.(Transcode|Trash|Restore|Purge|HqDeleteCommand|LqCommand|MediaUploadCommand|MetadataRefreshCommand)Handler`
- **同步 AGENTS.md**：修正 `LocalStorageService`→`StorageService`、删 `FilePathBuilder` 条目、补充 command/importer/media/storage 包、修正 WHERE TO LOOK 中的包路径

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 命令执行器移动遗漏引用 | grep 逐类核对；编译期兜底 |
| MediaAnalyzer 跨 3 域依赖 | 独立 `media` 包承载，依赖方向 media ← importer/command/event 单向 |
| AGENTS.md 同步遗漏 | WHERE TO LOOK 表逐条对照新结构 |
| 测试引用旧包 | grep 测试目录 + 全量测试 |

## 7. 排除项（YAGNI）

- 不重命名类（仅移动包）；不改 `entity`/`mapper` 的 `Export*` 前缀（历史命名，独立范围）。
- 不拆 `file` 剩余 4 子包（download/extract/transcode/trash 语义域清晰）。
- 不做任何业务逻辑重构（MQ 消费者/命令执行器的内部实现不动）。
