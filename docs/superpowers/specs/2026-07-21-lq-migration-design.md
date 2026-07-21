# LQ 迁移导入设计

**日期**: 2026-07-21
**状态**: 待实现
**类型**: 临时迁移功能（一次性使用）

---

## 背景

部分漫画在旧系统中以 LQ（低质量/优化后）形式存储，对应的 HQ（高质量）文件大小为 0（空白占位），实际内容仅在 LQ 目录下存在。需将这些漫画导入 ComicAtlas 新系统。

**示例目录结构**:
```
F:\games\comics\h_photograph\写真\陆萱萱\   ← HQ 文件存在但大小为 0
F:\games\comics\l_photograph\写真\陆萱萱\   ← LQ 文件存在，内容完整（已优化图片）
```

**关键约束**：LQ 文件是**已优化的图片**，导入后不需要系统再执行 LQ 压缩。

---

## 约束条件（已确认）

1. **HQ 文件严格大小为 0**：判断条件明确，无需模糊阈值。
2. **目录命名规则统一**：所有目录遵循 `h_*` / `l_*` 前缀规则。
3. **临时功能**：仅提供后端 API，不修改前端批量导入 UI。
4. **不修改核心导入代码**：`DirectoryImportHandler`、`ImportEventHandler` 等通用导入链路不得改动。
5. **LQ 已优化**：不需要触发 LQ 生成，导入后系统不应再对这些漫画执行 LQ 压缩。

---

## 系统 HQ/LQ 状态支持度分析

### 前端 `ProgressiveImage`

前端阅读器已内置 fallback 逻辑：
- `HQ_ONLY` 模式：`currentSrc = hq ?? lq ?? undefined`（HQ 缺失时尝试 LQ）
- `LQ_ONLY` / `AUTO` 模式：LQ 不可用时降级 HQ

### 后端 `ImportEventHandler`

```java
page.setHqRoot("HQ");        // 硬编码
page.setHqStatus("READY");   // 硬编码
page.setLqStatus("NOT_GENERATED"); // 硬编码，lqRoot/lqPath 从不设置
```

**关键发现**：导入链路**硬编码 HQ 字段**，导入后的 page **永远不可能出现 `hqRoot=null`**。这意味着：
- 前端 fallback 逻辑在标准导入流程下**永远不会被触发**
- 要让导入后 `hqRoot=null` 且 `lqRoot="LQ"`，必须修改 `ImportEventHandler`，突破"不修改核心代码"约束

### 三种状态支持度

| 状态 | 系统支持 | 说明 |
|------|---------|------|
| **HQ 不存在，LQ 存在** | 前端支持，导入链路不支持 | 前端有 fallback，但 `ImportEventHandler` 硬编码 HQ |
| **HQ 存在，LQ 存在** | 导入后 HQ 有，LQ 无 | LQ 需后续手动触发生成（基于 HQ 重新压缩） |
| **HQ 不存在，LQ 不存在** | 前端显示 error | 导入链路应避免 |

---

## 方案选择

采用 **前置合并 + 标准导入** 方案：

- **不修改核心导入链路**：新增独立的 `LqMigrationHandler`，在调用 `DirectoryImportHandler` 前完成 LQ 回填，生成临时合并目录。
- `DirectoryImportHandler` 看到的已经是完整目录，无需任何感知 LQ 回填逻辑。
- **零改动通用阅读链路**：`FileUrlResolver`、`ReaderService`、`ImportEventHandler` 均无需修改。

**为什么不利用前端 fallback（HQ 缺失时 fallback 到 LQ）**：

理论上最优雅的方案是让 `LqMigrationHandler` 把 LQ 文件搬运到**系统 LQ 目录**，并修改 `ImportEventHandler` 写入 `lqRoot="LQ"`、`hqRoot=null`。这样前端会自动 fallback，数据模型也最诚实。

但这需要修改 `ImportEventHandler`（目前硬编码 `hqRoot="HQ"`，且从不设置 `lqRoot`），**突破"不修改核心代码"的约束**。临时功能不应侵入核心 DB 写入逻辑，避免引入回归风险。

**前置合并方案的本质**：
利用临时目录做一层"欺骗"——把 LQ 文件伪装成 HQ 文件喂给标准导入流程。导入完成后系统认为这些是正常 HQ，阅读器无需任何 fallback 即可正常显示。这是临时功能在"零改动核心链路"约束下的务实妥协。

**为什么不修改 DirectoryImportHandler**：
- `DirectoryImportHandler` 是 ZIP、DIRECTORY、REGISTER 三种 sourceType 的共同处理核心，修改它会影响所有导入场景。
- 临时功能不应侵入核心链路，避免引入回归风险。

---

## 架构设计

### 数据流

```
POST /api/tasks/import
  { sourceType: "MIGRATE_LQ", sourcePath: "F:/games/comics/h_photograph/写真/陆萱萱" }
    ↓
ImportServiceImpl: case "MIGRATE_LQ"
  验证 sourcePath 存在 → INSERT comic(IMPORTING) + import_task(PENDING)
    ↓
MQ: ImportTaskCreated
    ↓
Worker ImportTaskHandler: 路由到 LqMigrationHandler
    ↓
LqMigrationHandler:
  1. 推断 LQ 路径 (h_* → l_*)
  2. 创建临时目录 mangaRoot/temp/{taskId}/migrate/
  3. 递归复制：HQ 非空文件直接复制；HQ 大小 == 0 的文件从 LQ 复制回填
  4. 视频文件（LQ 不存在）→ 跳过
  5. 调用 DirectoryImportHandler.handle(sourcePath=临时目录, sourceType=DIRECTORY)
  6. DirectoryImportHandler 正常解析 → 搬运 → 写 metadata
  7. 清理临时目录
    ↓
MQ: task.completed
    ↓
API ImportEventHandler: 零改动
  读 metadata → INSERT catalog/chapter/page → comic→READY
```

### 改动点清单

| 模块 | 改动 | 说明 |
|------|------|------|
| `ImportServiceImpl` | 增加 `case "MIGRATE_LQ"` | 验证 sourcePath，创建 comic + task |
| `ImportTaskHandler` | 增加路由分支 | `case "MIGRATE_LQ" -> lqMigrationHandler.migrate(...)` |
| **新增 `LqMigrationHandler`** | **核心新增** | 推断 LQ 路径、创建临时合并目录、回填空文件、调用 DirectoryImportHandler |
| `DirectoryImportHandler` | **零改动** | 处理的是已合并的临时目录，完全无感知 |
| `ImportContext` | **零改动** | 临时目录以标准 DIRECTORY 方式导入 |
| `ImportEventHandler` | **零改动** | metadata 格式兼容现有逻辑 |
| `FileUrlResolver` | **零改动** | 阅读器无感知 |
| `前端` | **零改动** | 临时功能，只提供后端 API |

---

## 核心逻辑：LQ 迁移 Handler

### 1. 推断 LQ 路径

```java
// HQ: F:\games\comics\h_photograph\写真\陆萱萱
// LQ: F:\games\comics\l_photograph\写真\陆萱萱

private Path inferLqPath(Path hqPath) {
    String hqStr = hqPath.toString();
    // 将路径中第一个 h_ 前缀段替换为 l_（不区分大小写）
    String lqStr = hqStr.replaceFirst("(?i)\\h_([^\\]+)", "\\\\l_$1");
    Path lqPath = Path.of(lqStr);
    if (!Files.exists(lqPath)) {
        throw new RuntimeException("LQ 路径不存在: " + lqPath);
    }
    if (!Files.isDirectory(lqPath)) {
        throw new RuntimeException("LQ 路径不是目录: " + lqPath);
    }
    return lqPath;
}
```

### 2. 创建临时合并目录

```java
public void migrate(Long taskId, Long comicId, Path hqPath, Path mangaRoot) throws Exception {
    Path lqPath = inferLqPath(hqPath);
    Path tempDir = mangaRoot.resolve("temp").resolve(taskId.toString()).resolve("migrate");
    Files.createDirectories(tempDir);

    try {
        mergeDirectories(hqPath, lqPath, tempDir);

        // 以标准 DIRECTORY 方式导入临时目录
        ImportContext ctx = new ImportContext("DIRECTORY", tempDir, false, false);
        directoryHandler.handle(ctx, taskId, comicId, mangaRoot);
    } finally {
        cleanupTemp(tempDir);
    }
}
```

### 3. 递归合并逻辑

```java
private void mergeDirectories(Path hqDir, Path lqDir, Path targetDir) throws IOException {
    try (var stream = Files.walk(hqDir)) {
        for (Path hqFile : stream.toList()) {
            Path relative = hqDir.relativize(hqFile);
            Path targetFile = targetDir.resolve(relative);

            if (Files.isDirectory(hqFile)) {
                Files.createDirectories(targetFile);
                continue;
            }

            // 文件处理
            long hqSize = Files.size(hqFile);
            if (hqSize > 0) {
                // HQ 有内容，直接复制
                Files.copy(hqFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // HQ 为空，尝试从 LQ 回填
                Path lqFile = lqDir.resolve(relative);
                if (Files.exists(lqFile) && Files.size(lqFile) > 0) {
                    Files.copy(lqFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    log.info("LQ 回填: {} -> {}", lqFile, targetFile);
                } else {
                    log.warn("无法回填，跳过: {}", relative);
                }
            }
        }
    }
}
```

**说明**：
- `Files.walk(hqDir)` 遍历 HQ 目录完整结构。
- 子目录在 HQ 中一定存在（即使是空文件也会有目录结构），所以按 HQ 结构复制。
- LQ 中不存在的文件（如视频）会被跳过。
- 临时目录中的文件大小是实际大小（来自 LQ），`MediaAnalyzer` 和 `DirectoryImportHandler` 完全正常处理。

### 4. 临时目录清理

```java
private void cleanupTemp(Path tempDir) {
    if (!Files.exists(tempDir)) return;
    try (var stream = Files.walk(tempDir)) {
        stream.sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    } catch (Exception e) {
        log.warn("临时目录清理失败: {}", tempDir, e);
    }
}
```

---

## 错误处理

| 场景 | 行为 |
|------|------|
| HQ 文件大小 == 0，但 LQ 文件不存在 | 记录 warning，跳过该文件，不进入临时目录 |
| 目录结构不匹配（HQ 和 LQ 子目录不一致） | 以 HQ 结构为准，LQ 缺失的文件跳过 |
| LQ 路径推断失败（命名不符合 h_* 规则） | Worker 层报错，task 标记 FAILED，记录错误原因 |
| 视频文件 | HQ 为空但 LQ 无视频 → 正常跳过（符合预期） |
| 临时目录创建失败 | 抛出异常，task 标记 FAILED |

---

## 数据模型影响

- `page.hq_root` = "HQ"
- `page.hq_path` = 系统标准路径 `{comicId}/{chapterId}/{filename}`
- `page.lq_root` = null（初始）
- `page.lq_path` = null（初始）
- `page.hq_status` = "READY"
- `page.lq_status` = "NOT_GENERATED"
- `page.fileSize` = 实际搬运大小（来自 LQ，已优化图片）

**阅读器行为**：阅读器请求 HQ URL，系统返回实际内容（已优化的 LQ 图片，存储在系统 HQ 目录）。这是临时迁移功能接受的设计妥协——这些漫画没有真 HQ，但系统不需要感知这一点。

**LQ 压缩**：`lq_status` = `NOT_GENERATED`，系统不会自动触发 LQ 压缩。手动触发 LQ 生成时，会将已优化图片再次压缩（无意义但无害），用户应避免对这些漫画触发 LQ。

---

## 测试策略

### 单元测试（LqMigrationHandler）
1. **LQ 路径推断**：验证 `h_photograph` → `l_photograph`，大小写不敏感。
2. **mergeDirectories**：
   - HQ 非空文件 → 直接复制到临时目录。
   - HQ 大小 == 0 → 从 LQ 复制到临时目录。
   - LQ 不存在 → 跳过，不进入临时目录。
3. **临时目录清理**：验证 finally 块正确清理。

### 冒烟测试
1. 准备临时目录 `test_h/` 和 `test_l/`，`test_h` 中放大小为 0 的图片，`test_l` 中放正常图片。
2. 调用 API 导入 `MIGRATE_LQ`。
3. 验证系统 HQ 目录下文件大小正确（来自 LQ）。
4. 验证 DB 中 `page.fileSize > 0`。
5. 验证阅读器能正常加载图片。

### 边界情况
1. **混合场景**：某目录下部分 HQ 文件非空，部分为空 → 非空直接复制，空文件 LQ 回填。
2. **空章节**：某目录下所有文件都为空且 LQ 缺失 → 该章节在临时目录中无文件，`DirectoryImportHandler` 按空章节处理（跳过或报错，取决于现有逻辑）。
3. **嵌套目录**：深层子目录结构 → `Files.walk` 递归正确处理。

---

## 后续清理

此功能标记为 **临时**。当所有旧系统漫画迁移完成后：
- **移除 `LqMigrationHandler`** 及其路由
- **移除 `ImportServiceImpl` 中的 `case "MIGRATE_LQ"`**
- **保留已导入漫画数据**：导入的漫画数据（catalog/chapter/page/文件）不受影响，正常可用。

---

## 自审：HQ + LQ 同时存在的场景

旧系统中部分漫画的 HQ 和 LQ **同时存在**（HQ 有内容，LQ 也有已优化的内容）。当前方案的 `mergeDirectories` 逻辑对这类漫画的处理：

- **HQ 非空文件** → 直接复制 HQ 到临时目录 → `DirectoryImportHandler` 搬运到系统 HQ（正确）
- **HQ 为空文件** → 从 LQ 回填到临时目录 → `DirectoryImportHandler` 搬运到系统 HQ（正确）

**结论：导入过程本身已覆盖混合场景，无需修改 `mergeDirectories` 逻辑。**

### 但存在 LQ 丢失问题

`DirectoryImportHandler` 只搬运到系统 HQ 目录，`ImportEventHandler` 设置 `lq_root=null, lq_path=null, lq_status=NOT_GENERATED`。这意味着：

| 漫画类型 | 导入后状态 | 影响 |
|----------|-----------|------|
| HQ 为空 | HQ 目录有内容（实际是 LQ），LQ 目录无内容 | 阅读器加载 HQ URL 正常显示；`lqUrl=null` 不影响（没有更高质量版本）。但用户若手动触发 LQ 生成，会对已优化图片二次压缩（无意义但无害）。 |
| HQ + LQ 同时存在 | 系统 HQ 有真 HQ，系统 LQ 无内容 | 阅读器加载 `lqUrl` 返回 null，前端可能回退 HQ（正常）。但旧系统优化的 LQ 丢失了，用户失去了"LQ 模式"的选择。 |

### 自审决策

**保持当前设计，明确 MIGRATE_LQ 的定位**：

1. **MIGRATE_LQ 只解决"HQ 为空 → LQ 回填"的核心问题。**
2. **HQ + LQ 同时存在的漫画，建议走正常 `DIRECTORY` sourceType 导入。**
   - 系统搬运 HQ 到系统 HQ 目录。
   - LQ 在导入后由用户手动触发 `/comics/{id}/lq` 生成，系统会基于 HQ 重新压缩生成 LQ。
   - 虽然新 LQ 和旧系统 LQ 可能质量不同，但这是不修改核心导入链路的代价。
3. **HQ 为空的漫画，导入后应避免手动触发 LQ 生成。** 系统会将已优化的 LQ 图片再次压缩（无意义）。

**替代方案（如果需要保留旧 LQ）**：
可为 HQ + LQ 同时存在的漫画，导入后用独立脚本将旧系统 LQ 搬运到系统 LQ 目录并更新 DB 的 `lq_root`/`lq_path`/`lq_status`。这是一个**独立的后置工具**，不修改任何导入链路。超出本次临时功能的范围，如需实现另开任务。

---

## 设计评审记录

| 问题 | 决策 |
|------|------|
| 是否修改 FileUrlResolver fallback 逻辑？ | **否**。临时功能不应改动通用链路。 |
| 是否修改 DirectoryImportHandler？ | **否**。新增前置 Handler 完成合并，核心链路零改动。 |
| 是否修改 ImportEventHandler？ | **否**。metadata 格式兼容，DB 写入逻辑不变。 |
| 是否生成 LQ 副本？ | **否**。LQ 内容直接存入系统 HQ 目录（HQ 为空场景），或走正常 DIRECTORY 导入后系统生成（HQ+LQ 同时存在场景）。 |
| 空文件阈值？ | **严格等于 0**。用户确认 HQ 文件大小严格为 0。 |
| 是否支持批量？ | **是**。复用现有 `createBatchImportTasks`，sourceType 传 "MIGRATE_LQ"。 |
| 前端是否增加 UI？ | **否**。临时功能，只提供后端 API，手动调用。 |
| HQ+LQ 同时存在的漫画如何处理？ | **走正常 DIRECTORY 导入**。MIGRATE_LQ 只处理 HQ 为空的漫画。 |
| LQ 已优化，导入后是否会被再压缩？ | **不会自动触发**。但手动触发 LQ 生成会对已优化图片二次压缩（HQ 为空的漫画应避免）。 |
