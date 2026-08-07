# worker-service 模块化重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 worker-service 拆分为 `command`/`importer`/`media`/`storage` 顶层业务包，解决 `event` 包职责混杂与 `file` 包过大问题。

**Architecture:** 纯包移动 + import 同步（零逻辑变更）：`event` 拆出 8 命令执行器 → `command`；`file/storage` 9 类 → `storage`；导入域 8 类（handler 2 + manifest 2 + parse 4）→ `importer`；跨域 `MediaAnalyzer`/`ComicMetadata` → `media`。

**Tech Stack:** Java 21, Spring Boot 3.3.0, Maven

**Design spec:** `docs/superpowers/specs/2026-08-07-worker-service-modularization-design.md`

## Global Constraints

- **纯包移动**：类名、方法、字段、注解、逻辑一律不变；只改 `package` 声明行与引用方 `import` 行。
- 移动用 `git mv`（保留历史）。
- 每个 Task 结束：编译（`.\mvnw -pl worker-service -am compile -DskipTests`）必须 BUILD SUCCESS，再跑全量测试，最后提交（中文"动作 + 内容"）。
- 提交前 `git status` 确认只含本 Task 文件；禁止夹带无关修改。
- 每 Task 结束后 grep 确认无旧包引用残留（import 路径已全量更新）。

---

### Task 1: `event` → `command`（8 个命令执行器）

**Files:**
- Move（8）：`worker-service/src/main/java/com/comicatlas/worker/event/` 下
  - `TranscodeCommandHandler.java`、`TrashCommandHandler.java`、`RestoreCommandHandler.java`、`PurgeCommandHandler.java`、`HqDeleteCommandHandler.java`、`LqCommandHandler.java`、`MediaUploadCommandHandler.java`、`MetadataRefreshCommandHandler.java`
  - 目标目录：`worker-service/src/main/java/com/comicatlas/worker/command/`（新建）
  - package 声明：`com.comicatlas.worker.event` → `com.comicatlas.worker.command`
- Modify：`worker-service/src/main/java/com/comicatlas/worker/event/ManagementCommandDispatcher.java`（注入 8 个 command handler → import 改 `com.comicatlas.worker.command.*`）
- Modify：8 个 command handler 内部若有 `import com.comicatlas.worker.event.ManagementCommandPublisher` → 保留（publisher 在 event 包，方向 command → event 合理）
- Modify：8 个 command handler 内部若有同包互引（如 TrashCommandHandler ↔ RestoreCommandHandler）→ import 改 `com.comicatlas.worker.command.*`

**Interfaces:**
- Consumes: `ManagementCommandPublisher`（event 包）、`file.storage`/`file.parse`/`image`/`export` 依赖（Task 2-4 会改这些包的引用，本 Task 内保持 `file.storage` 等旧路径——后续 Task 更新）
- Produces: 无（类名不变）

- [ ] **Step 1: git mv 8 个类到 command/ + 改 package 声明**

```bash
# 例（8 个类逐一执行）
git mv worker-service/src/main/java/com/comicatlas/worker/event/TranscodeCommandHandler.java worker-service/src/main/java/com/comicatlas/worker/command/TranscodeCommandHandler.java
# 每个文件 package 声明改为：package com.comicatlas.worker.command;
```

- [ ] **Step 2: 更新 ManagementCommandDispatcher import**（8 个类引用）
- [ ] **Step 3: 检查/更新 command 内部与测试引用**

Run: `Select-String -Path "worker-service\src" -Pattern "worker\.event\.(Transcode|Trash|Restore|Purge|HqDeleteCommand|LqCommand|MediaUploadCommand|MetadataRefreshCommand)Handler"` — 更新所有命中（除 Dispatcher 注入外，含测试目录 `src/test`）

- [ ] **Step 4: 编译 + 全量测试**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: BUILD SUCCESS，全部通过（基线 53）

- [ ] **Step 5: 提交**

```bash
git add -A worker-service/src/main worker-service/src/test
git commit -m "拆分 event 包：8 个命令执行器移至独立 command 包，event 只保留 MQ 消费者/分发器/发布器"
```

---

### Task 2: `file/storage` → 顶层 `storage`（9 类）

**Files:**
- Move（9）：`worker-service/src/main/java/com/comicatlas/worker/file/storage/` 下全部 9 个 .java → `worker-service/src/main/java/com/comicatlas/worker/storage/`（新建）
  - `StorageProperties`、`StorageRef`、`StorageRoot`、`StorageService`、`TransferService`、`TransferMode`、`SafeMoveStrategy`、`PathTraversalException`、`ExportFileResolver`
  - package 声明：`com.comicatlas.worker.file.storage` → `com.comicatlas.worker.storage`
  - `StorageProperties`/`StorageRef`/`StorageRoot`/`StorageService`/`TransferMode`/`SafeMoveStrategy`/`PathTraversalException`/`TransferService` 同包互引 → 删除同包 import；`ExportFileResolver` 的 `import com.comicatlas.worker.export.ExportFileNotFoundException` 保留（跨包）
- Modify（引用方 import）：`file.storage` → `storage`
  - `file/trash/TrashManifestStore.java`
  - `export/ExportService.java`
  - `event/`：`LqGenerateHandler`、`DeleteHandler`、`HqDeleteHandler`
  - `command/`（Task 1 已移）：`LqCommandHandler`、`HqDeleteCommandHandler`、`TrashCommandHandler`、`RestoreCommandHandler`、`MediaUploadCommandHandler`
  - `importer/`（Task 3 将移，本 Task 时仍在 file/handler）：`file/handler/DirectoryImportHandler.java`

- [ ] **Step 1: git mv 9 类 + 改 package 声明 + 清理同包 import**
- [ ] **Step 2: 更新全部引用方 import**

Run: `Select-String -Path "worker-service\src" -Pattern "worker\.file\.storage"` — 更新所有命中（含测试）

- [ ] **Step 3: 编译 + 全量测试**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 4: 提交**

```bash
git add -A worker-service/src/main worker-service/src/test
git commit -m "提升 file/storage 为顶层 storage 包：存储域独立，与 api storage 对齐"
```

---

### Task 3: 导入域 → `importer`（8 类）

**Files:**
- Move（8）：
  - `file/handler/DirectoryImportHandler.java`、`file/handler/ZipImportHandler.java` → `importer/`
  - `file/manifest/ImportManifest.java`、`file/manifest/ImportManifestManager.java` → `importer/`
  - `file/parse/DirectoryParser.java`、`file/parse/DirectoryTree.java`、`file/parse/ImportContext.java`、`file/parse/MetadataAssembler.java` → `importer/`
  - package 声明：→ `com.comicatlas.worker.importer`
  - 同包互引 import 清理（importer 内部类互相引用 → 删除同包 import）
- Modify（引用方 import）：
  - `event/ImportTaskHandler.java`：`file.handler.DirectoryImportHandler`/`file.handler.ZipImportHandler`/`file.parse.ImportContext` → `importer.*`
  - `event/VideoMetadataFixHandler.java`：`file.parse.ComicMetadata`/`file.parse.MediaAnalyzer` → Task 4 改（本 Task 保持 file.parse 或先改？——顺序：本 Task 后 file.parse 剩 MediaAnalyzer/ComicMetadata；VideoMetadataFixHandler 的 import 在 Task 4 更新）
  - `command/`（Task 1 已移）：`TranscodeCommandHandler`/`MediaUploadCommandHandler` 的 `file.parse.ComicMetadata`/`MediaAnalyzer` → Task 4 改
  - `file/transcode/VideoNormalizer.java`：不引用 importer（被 DirectoryImportHandler 调，同向依赖）——无 import 变更

**Interfaces:**
- 注：本 Task 后 `file/parse` 仅剩 `MediaAnalyzer`/`ComicMetadata`（Task 4 处理），`file/` 剩 download/extract/transcode/trash。

- [ ] **Step 1: git mv 8 类 + 改 package 声明 + 清理同包 import**
- [ ] **Step 2: 更新引用方 import（ImportTaskHandler 等）**

Run: `Select-String -Path "worker-service\src" -Pattern "worker\.file\.(handler|manifest)\.|worker\.file\.parse\.(DirectoryParser|DirectoryTree|ImportContext|MetadataAssembler)"` — 更新所有命中（含测试）

- [ ] **Step 3: 编译 + 全量测试**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 4: 提交**

```bash
git add -A worker-service/src/main worker-service/src/test
git commit -m "拆分 file 导入域为顶层 importer 包：导入编排/清单/解析归拢，对齐 api importer"
```

---

### Task 4: `MediaAnalyzer`/`ComicMetadata` → `media`（2 类）

**Files:**
- Move（2）：`file/parse/MediaAnalyzer.java`、`file/parse/ComicMetadata.java` → `worker-service/src/main/java/com/comicatlas/worker/media/`（新建）
  - package 声明：`com.comicatlas.worker.file.parse` → `com.comicatlas.worker.media`
  - 注：`ComicMetadata` 含嵌套 `MediaInfo` record（MediaAnalyzer 返回类型），同包移动后内部引用不变
- Modify（引用方 import）：`file.parse.MediaAnalyzer`/`file.parse.ComicMetadata` → `media.*`
  - `importer/MetadataAssembler.java`（analyze 调用）
  - `command/TranscodeCommandHandler.java`、`command/MediaUploadCommandHandler.java`
  - `event/VideoMetadataFixHandler.java`

- [ ] **Step 1: git mv 2 类 + 改 package 声明**
- [ ] **Step 2: 更新引用方 import**

Run: `Select-String -Path "worker-service\src" -Pattern "worker\.file\.parse"` — 更新所有命中（本 Task 后应 0 残留）

- [ ] **Step 3: 编译 + 全量测试**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 4: 提交**

```bash
git add -A worker-service/src/main worker-service/src/test
git commit -m "拆分跨域媒体元数据为 media 包：MediaAnalyzer/ComicMetadata 独立，消除 parse 包残留"
```

---

### Task 5: AGENTS.md 同步 + 全链路验证

- [ ] **Step 1: 更新 AGENTS.md**

- `WHERE TO LOOK` 表修正：
  - 存储服务：`worker-service/.../file/storage/LocalStorageService.java` → `worker-service/.../storage/StorageService.java`
  - 存储根/文件引用/路径布局：`file/storage/StorageRoot.java`、`StorageRef.java`、`TransferService.java` → `storage/`
  - 删除 `common/FilePathBuilder.java` 条目（已删）
  - 导入相关：`file/handler/DirectoryImportHandler.java`、`file/parse/*` → `importer/`
  - 媒体分析：`file/parse/MediaAnalyzer.java` → `media/MediaAnalyzer.java`
  - 命令执行器：补充 `worker/command/`（TranscodeCommandHandler 等 8 个）
  - EHENTAI 导入：`file/EhentaiDownloadService.java` → `file/download/EhentaiDownloadService.java`
- `STRUCTURE` 部分补充新顶层包（command/importer/media/storage）

- [ ] **Step 2: 全量编译 + 全量测试**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: BUILD SUCCESS，全部通过（基线 53）

- [ ] **Step 3: 残留引用确认**

Run: `Select-String -Path "worker-service\src" -Pattern "worker\.file\.storage|worker\.file\.handler|worker\.file\.manifest|worker\.file\.parse|worker\.event\.(Transcode|Trash|Restore|Purge|HqDeleteCommand|LqCommand|MediaUploadCommand|MetadataRefreshCommand)Handler"`
Expected: 0 命中（worker-service/src 全部更新）

- [ ] **Step 4: 提交**

```bash
git add AGENTS.md
git commit -m "同步 AGENTS.md：反映 worker command/importer/media/storage 拆分后的目录结构"
```
