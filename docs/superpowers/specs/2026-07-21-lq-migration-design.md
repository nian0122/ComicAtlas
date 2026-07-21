# 旧系统存储结构导入新系统设计

**日期**: 2026-07-21
**状态**: 待实现
**类型**: 临时迁移功能（一次性使用）

---

## 背景

旧系统的漫画仓库采用 HQ（高质量）+ LQ（低质量/已优化）双目录存储结构。将旧系统漫画导入 ComicAtlas 新系统时，需要处理三种存储状态：

| 状态 | 说明 | 导入方式 |
|------|------|---------|
| **只存在 HQ** | HQ 有内容，无对应 LQ | `sourceType: "DIRECTORY"` |
| **HQ + LQ 同时存在** | HQ 有内容，LQ 也有已优化内容 | `sourceType: "DIRECTORY"` |
| **不存在 HQ，只存在 LQ** | HQ 文件大小为 0（空白占位），实际内容仅在 LQ | `sourceType: "MIGRATE_LQ"`（本文档核心） |

**示例目录结构**:
```
F:\games\comics\h_photograph\写真\陆萱萱\   ← HQ 文件存在但大小为 0
F:\games\comics\l_photograph\写真\陆萱萱\   ← LQ 文件存在，内容完整（已优化图片）
```

**本文档范围**：只解决第三种状态（**不存在 HQ，只存在 LQ**）的导入问题。前两种状态直接复用现有 `DIRECTORY` sourceType。

---

## 约束条件（已确认）

1. **HQ 文件严格大小为 0**：判断条件明确。
2. **目录命名规则统一**：遵循 `h_*` / `l_*` 前缀规则。
3. **临时功能**：仅提供后端 API，不修改前端 UI。
4. **不修改核心导入代码**：`DirectoryImportHandler` 不得改动。
5. **LQ 不能丢弃**：旧系统 LQ 必须搬运到系统 LQ 存储，DB 标记 `lqStatus = READY`。
6. **数据模型诚实**：HQ 为空时 `hqRoot = null`，LQ 就绪时 `lqRoot = "LQ"`，**禁止伪装**。
7. **LQ 已优化**：不需要系统再压缩。

---

## 方案选择

### 为什么之前的"前置合并伪装"方案被推翻

旧方案把 LQ 文件复制到临时目录，让 `DirectoryImportHandler` 以标准流程搬运到系统 HQ，DB 中记录为正常 HQ。这违反了约束 5（**LQ 必须保留到 LQ 目录**）和约束 6（**禁止伪装**）。

### 新方案：直接搬运 LQ + 修改 ImportEventHandler

| 模块 | 策略 |
|------|------|
| `DirectoryImportHandler` | **零改动**（核心导入代码不修改） |
| `LqMigrationHandler`（新增） | 解析 LQ 目录、搬运到**系统 LQ 目录**、生成 metadata |
| `ImportEventHandler` | **修改**（根据 sourceType 写入诚实的 DB 记录） |

**核心原则**：`DirectoryImportHandler` 负责"解析 + 搬运到 HQ + 生成 metadata"，而 LQ 导入需要"解析 + 搬运到 LQ + 生成 metadata"。两者只有"目标存储根"不同。因此不修改 `DirectoryImportHandler`，新增一个独立的 `LqMigrationHandler` 来执行 LQ 搬运，并生成适配的 metadata。

---

## 架构设计

### 数据流

```
POST /api/tasks/import
  { sourceType: "MIGRATE_LQ", sourcePath: "F:/games/comics/h_photograph/写真/陆萱萱" }
    ↓
ImportServiceImpl: 并入 DIRECTORY case
  验证 sourcePath 存在 → INSERT comic(IMPORTING) + import_task(PENDING, sourceType="MIGRATE_LQ")
    ↓
MQ: ImportTaskCreated(taskId, comicId, sourceType="MIGRATE_LQ", sourcePath)
    ↓
Worker ImportTaskHandler: case "MIGRATE_LQ" → LqMigrationHandler.handle()
    ↓
LqMigrationHandler:
  1. 推断 LQ 路径 (h_* → l_*)
  2. 用 DirectoryParser + MetadataAssembler 扫描 LQ 目录
  3. 遍历文件 → storageService.store(lqFile, "LQ", relativePath) → 系统 LQ 目录
  4. 生成 metadata.json（version=3, sourceType="MIGRATE_LQ", hqStatus="EMPTY", lqStatus="READY"）
  5. 封面：从系统 LQ 复制到 thumbs 目录
     ↓
MQ: task.completed
    ↓
API ImportEventHandler:
  读取 metadata，获取 sourceType="MIGRATE_LQ"
  遍历 chapters → insertChapter():
    if (MIGRATE_LQ):
      page.hq_root = null, hq_path = null, hq_status = "EMPTY"
      page.lq_root = "LQ", lq_path = relativePath, lq_status = "READY"
      page.fileSize = metadata.fileSize（LQ 实际大小）
    else:
      保持现有逻辑（HQ 搬运，LQ 未生成）
  comic.fileSize = totalSize（LQ 总大小）
  comic.hqSize = 0（HQ 为空）
  comic.status = READY
```

### 改动点清单

| 模块 | 改动 | 说明 |
|------|------|------|
| `ImportServiceImpl` | 扩展 case | `case "ZIP", "REGISTER", "DIRECTORY", "MIGRATE_LQ"` 共用同一逻辑 |
| `ImportTaskHandler` | 增加路由 | `case "MIGRATE_LQ" -> lqMigrationHandler.handle(...)` |
| **新增 `LqMigrationHandler`** | 核心新增 | 推断 LQ 路径、扫描 LQ 目录、搬运到系统 LQ、生成 metadata、封面 |
| `DirectoryImportHandler` | **零改动** | 核心导入代码不修改 |
| `ImportEventHandler` | **修改** | `insertChapter()` 增加 sourceType 条件分支，写入诚实 HQ/LQ 记录 |
| `HqStatus` 枚举 | **扩展** | 增加 `EMPTY` 状态（HQ 存在但大小为 0） |
| `FileUrlResolver` | **零改动** | 已有 `resolve()` 和 `resolveLq()`，hqRoot=null 时返回 null |
| `前端` | **零改动** | `ProgressiveImage` 已有 fallback：HQ 缺失时自动加载 LQ |

---

## 核心逻辑

### 1. LqMigrationHandler（Worker 层，新增）

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class LqMigrationHandler {

    private final DirectoryParser parser;
    private final MetadataAssembler assembler;
    private final LocalStorageService storageService;
    private final ObjectMapper objectMapper;
    private final CancelHandler cancelHandler;

    public void handle(Long taskId, Long comicId, Path hqPath, Path mangaRoot) throws Exception {
        Path lqPath = inferLqPath(hqPath);
        Path importRoot = lqPath; // 扫描 LQ 目录

        // 1. 解析目录结构（复用现有组件）
        DirectoryTree tree = parser.parse(importRoot);
        ComicMetadata metadata = assembler.assemble(tree, 
            new ImportContext("MIGRATE_LQ", importRoot, false, false));

        if (cancelHandler.isCancelled(taskId)) {
            throw new RuntimeException("Task cancelled: " + taskId);
        }

        // 2. 搬运到系统 LQ 目录
        for (var ch : metadata.chapters()) {
            for (var page : ch.pages()) {
                Path src = importRoot.resolve(ch.sourceDir()).resolve(page.fileName());
                if (!Files.exists(src)) src = importRoot.resolve(page.fileName());
                if (Files.exists(src) && Files.size(src) > 0) {
                    String relativePath = comicId + "/" + ch.globalOrder() + "/" + page.fileName();
                    storageService.store(src, "LQ", relativePath);
                }
            }
        }

        // 3. 封面：从系统 LQ 复制到 thumbs
        var firstCh = metadata.chapters().get(0);
        var firstImage = firstCh.pages().stream()
            .filter(p -> !"VIDEO".equals(p.mediaType()))
            .findFirst()
            .orElse(null);
        if (firstImage != null) {
            Path firstImg = storageService.resolve(new StorageRef("LQ",
                comicId + "/" + firstCh.globalOrder() + "/" + firstImage.fileName()));
            if (Files.exists(firstImg)) {
                Path thumbsDir = mangaRoot.resolve("thumbs").resolve(String.valueOf(comicId));
                Files.createDirectories(thumbsDir);
                Files.copy(firstImg, thumbsDir.resolve("cover.webp"), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // 4. 写 metadata（包含 sourceType 标记）
        writeMetadata(metadata, taskId, mangaRoot);
    }

    private Path inferLqPath(Path hqPath) {
        String hqStr = hqPath.toString();
        String lqStr = hqStr.replaceFirst("(?i)([\\/])h_([^\\/]+)", "$1l_$2");
        if (lqStr.equals(hqStr)) {
            throw new RuntimeException("LQ 路径推断失败: 路径 '" + hqStr + "' 中未找到 h_ 前缀");
        }
        Path lqPath = Path.of(lqStr);
        if (!Files.exists(lqPath) || !Files.isDirectory(lqPath)) {
            throw new RuntimeException("LQ 路径无效: " + lqPath);
        }
        return lqPath;
    }

    private void writeMetadata(ComicMetadata metadata, Long taskId, Path mangaRoot) throws Exception {
        Path metaPath = mangaRoot.resolve("metadata").resolve(taskId + ".json");
        Files.createDirectories(metaPath.getParent());

        Map<String, Object> fullMeta = new LinkedHashMap<>();
        fullMeta.put("version", 3);
        fullMeta.put("sourceType", "MIGRATE_LQ");
        // ... comic / catalogs / chapters 结构与 DirectoryImportHandler 一致
        // 但 mediaItems 中 hqStatus = "EMPTY", lqStatus = "READY"

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), fullMeta);
    }
}
```

### 2. ImportEventHandler 修改（API 层）

```java
// 在 persistComicImported 方法中读取 sourceType
String sourceType = metadata.containsKey("sourceType") 
    ? (String) metadata.get("sourceType") 
    : "DIRECTORY";
boolean isMigrateLq = "MIGRATE_LQ".equals(sourceType);

// 修改 insertChapter 方法签名，增加 isMigrateLq 参数
private ChapterResult insertChapter(Map<String, Object> chData, Long comicId,
                                     Map<Integer, Long> catalogIdMap, int version,
                                     boolean isMigrateLq) {
    // ... 章节创建逻辑不变 ...

    for (Map<String, Object> md : itemList) {
        Media page = new Media();
        page.setChapterId(chapter.getId());
        page.setPageNumber(((Number) md.get("pageNumber")).intValue());

        String relativePath = comicId + "/" + chapter.getGlobalOrder() + "/" + md.get(nameKey);

        if (isMigrateLq) {
            // HQ 为空
            page.setHqRoot(null);
            page.setHqPath(null);
            page.setHqStatus(md.get("hqStatus") != null ? (String) md.get("hqStatus") : "EMPTY");
            // LQ 就绪
            page.setLqRoot("LQ");
            page.setLqPath((String) md.getOrDefault("lqPath", relativePath));
            page.setLqStatus("READY");
        } else {
            // 正常导入（现有逻辑）
            page.setHqRoot("HQ");
            page.setHqPath((String) md.getOrDefault("hqPath", relativePath));
            page.setHqStatus(md.get("hqStatus") != null ? (String) md.get("hqStatus") : "READY");
            page.setLqStatus("NOT_GENERATED");
        }

        if (md.get("fileSize") != null) page.setFileSize(((Number) md.get("fileSize")).longValue());
        // ... 其余字段不变 ...
    }
}

// 修改 comic 总大小计算
if (totalSize > 0) {
    comic.setFileSize(totalSize);
    if (isMigrateLq) {
        comic.setHqSize(0L); // HQ 为空
    } else {
        comic.setHqSize(totalSize);
    }
}
```

### 3. HqStatus 枚举扩展

```java
public enum HqStatus { READY, MISSING, EMPTY, FAILED }
```

`EMPTY` 表示：HQ 文件存在但大小为 0（空白占位），实际内容在 LQ。

---

## 数据模型影响

### page 表

| 字段 | MIGRATE_LQ 导入后值 | 说明 |
|------|-------------------|------|
| `hq_root` | `null` | HQ 为空 |
| `hq_path` | `null` | HQ 为空 |
| `hq_status` | `"EMPTY"` | 新枚举值 |
| `lq_root` | `"LQ"` | LQ 已搬运到系统 LQ |
| `lq_path` | `{comicId}/{chapterId}/{filename}` | 系统标准路径 |
| `lq_status` | `"READY"` | 已可用 |
| `fileSize` | LQ 实际大小 | > 0 |

### comic 表

| 字段 | MIGRATE_LQ 导入后值 | 说明 |
|------|-------------------|------|
| `fileSize` | LQ 总大小 | 漫画实际内容大小 |
| `hqSize` | `0` | 无 HQ 内容 |
| `status` | `"READY"` | 可阅读 |

### 阅读器行为

`ReaderService` 返回 `MediaItemDTO`：
- `hqUrl` = `FileUrlResolver.resolve(p)` → `hqRoot=null` → `null`
- `lqUrl` = `FileUrlResolver.resolveLq(p)` → `/files/lq/{comicId}/{chapterId}/{filename}`
- `lqStatus` = `"READY"`

`ProgressiveImage.vue`（前端）：
```typescript
AUTO 模式: if (lqAvailable && props.lq) { currentSrc = props.lq }
```
→ 自动加载 LQ URL，**完全兼容，无需任何修改**。

---

## 错误处理

| 场景 | 行为 |
|------|------|
| LQ 文件不存在 | Worker 报错，task 标记 FAILED |
| HQ 文件大小 > 0（用户误传）| LqMigrationHandler 不检测 HQ 大小，直接扫描 LQ 目录搬运。如果 HQ 非空，说明漫画走错了 sourceType，属于用户操作错误。 |
| 目录结构解析失败 | 复用 DirectoryParser 现有错误处理 |
| 封面生成失败 | 记录 warning，不阻塞导入 |

---

## 测试策略

### 单元测试（LqMigrationHandler）
1. **LQ 路径推断**：`h_photograph` → `l_photograph`，大小写不敏感，无 `h_` 前缀时拒绝。
2. **搬运到 LQ**：验证 `storageService.store(src, "LQ", ...)` 被正确调用。
3. **metadata 生成**：验证 `sourceType="MIGRATE_LQ"`, `hqStatus="EMPTY"`, `lqStatus="READY"`。

### 冒烟测试
1. 准备 `test_l/` 目录放正常图片。
2. 调用 API 导入 `MIGRATE_LQ`。
3. 验证系统 LQ 目录下文件存在且大小正确。
4. 验证 DB：`page.hq_root=null`, `page.lq_root="LQ"`, `page.lq_status="READY"`。
5. 验证阅读器返回的 `lqUrl` 可正常加载。

### 边界情况
1. **视频文件**：旧系统 LQ 无视频 → 目录解析时跳过（正常行为）。
2. **嵌套目录**：深层子目录 → `Files.walk` 递归处理。
3. **路径分隔符**：`/` 和 `\` 混合 → `[\\/]` 正则兼容。

---

## 后续清理

此功能标记为 **临时**。当"不存在 HQ，只存在 LQ"状态的漫画全部迁移完成后：
- **移除 `LqMigrationHandler`**
- **移除 `ImportTaskHandler` 中的 `case "MIGRATE_LQ"`**
- **保留 `ImportEventHandler` 的 sourceType 分支**（无维护成本，不影响其他 sourceType）
- **保留 `HqStatus.EMPTY`**（可作为通用状态复用）
- **保留已导入漫画数据**：导入的漫画正常可用，阅读器通过 LQ URL 加载。

---

## 设计评审记录

| 问题 | 决策 |
|------|------|
| 是否修改 DirectoryImportHandler？ | **否**。核心导入代码不修改，新增 LqMigrationHandler 独立处理。 |
| 是否修改 ImportEventHandler？ | **是**。必须修改才能诚实写入 HQ=null / LQ="LQ" 的 DB 记录。 |
| LQ 是否丢弃？ | **否**。搬运到系统 LQ 目录，DB 标记 READY。 |
| HQ 是否伪装？ | **否**。hqRoot=null，hqStatus=EMPTY，诚实表达 HQ 为空。 |
| 空文件阈值？ | **不检测**。MIGRATE_LQ 只处理 HQ 为空的漫画，用户保证传入正确。 |
| 是否支持批量？ | **是**。复用现有 `createBatchImportTasks`，sourceType 传 "MIGRATE_LQ"。 |
| 前端是否增加 UI？ | **否**。临时功能，只提供后端 API。 |
| LQ 已优化，导入后是否会被再压缩？ | **不会**。lqStatus=READY，手动触发 LQ 生成应检测到 READY 并跳过。 |
