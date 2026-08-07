# export 包重构与 metadata v3 三处重复统一设计

**日期**: 2026-08-07
**状态**: 设计待审阅
**范围**: comic-common + worker-service（export 包 + 相关 handler）+ api-service（MetadataExporter）

## 1. 背景与动机

`worker-service/.../worker/export` 包（7 个类）存在 4 项设计问题（用户确认全部命中）：

| # | 问题 | 现状 |
|---|------|------|
| A | **职责错位**：导出核心业务堆在 MQ handler | `ExportTaskHandler.buildManifest`（约 150 行：章节分组/目录名去重/文件解析/跳过缺失）、`buildOutputFileName`、`classifyExportError` 全在 `event` 包的 handler 里；`export` 包反而只剩碎片工具 |
| B | **跨域耦合**：元数据刷新依赖导出组件 | `MetadataRefreshHandler` 注入 `ExportCollector` 生成 metadata.json——"刷新元数据"走"导出收集器" |
| C | **构建逻辑重复**：metadata v3 JSON 构建三处复制 | `ExportCollector.buildMetadataJson`（worker 导出）、`MetadataExporter.export`（api 后台）、`DirectoryImportHandler.buildMetadataMap`（导入）——三份相同结构 |
| D | **包内归属混乱** | `ExportFileResolver`（存储解析）与 `ComicTitleSanitizer`（通用工具）放 export 包，归属不当 |

## 2. 设计目标（阿里规范）

- **消除重复**（强制）：metadata v3 构建唯一化，跨模块共享放二方库（comic-common）。
- **职责单一**：导出编排进 `ExportService`；handler 只做 MQ 协议。
- **边界清晰**：刷新解耦导出；存储解析归 storage 域；通用工具归 common。
- 不改任何导出/刷新的**行为与产物格式**（v3 JSON、ZIP 布局不变）。

## 3. 目标架构

```
comic-common（新增 metadata 域，com.comicatlas.common.metadata）
├── MetadataV3            # 通用 v3 模型（record，无实体依赖，供各模块映射后共享构建）
└── MetadataJsonBuilder   # 唯一构建：MetadataV3 → v3 JSON 字符串

worker export 包（重构后）
├── ExportService          # 导出编排：collect→map→build→manifest→zip（自 handler 移入）
├── MetadataJsonExporter   # 元数据 JSON 生成：collect→map→build（导出+刷新共用）
├── MetadataModelMapper    # worker entity(Export*) → MetadataV3
├── ExportCollector        # 瘦身：仅数据收集（mapper 查询）
├── ZipBuilder / ExportManifest / ExportCollectResult / ExportFileNotFoundException  # 保留
├── ComicTitleSanitizer    # 移入 worker.common（通用工具归位）
└── ExportFileResolver     # 移入 file/storage 域（存储解析归位）

handler（变薄）
├── ExportTaskHandler      # 只剩 MQ 协议 + 事件发布，调 ExportService
└── MetadataRefreshHandler # 不再依赖 ExportCollector，改调 MetadataJsonExporter

api-service
└── MetadataExporter       # 映射层改用 MetadataV3 + MetadataJsonBuilder（消除第三份重复）
```

## 4. 设计细节

### 4.1 comic-common：MetadataV3 + MetadataJsonBuilder

**依赖**：comic-common/pom.xml 新增 `com.fasterxml.jackson.core:jackson-databind`（BOM 管理版本）。

```java
package com.comicatlas.common.metadata;

/** metadata v3 通用模型 — 与 ComicAtlas v3 格式一一对应，各模块映射实体后共享构建。 */
public record MetadataV3(
        String title, String author,
        List<Catalog> catalogs, List<Chapter> chapters) {

    public record Catalog(String title, int sortOrder, Integer parentIndex) {}

    public record Chapter(String title, String chapterNo, int sortOrder, int globalOrder,
                          Integer catalogIndex, List<MediaItem> mediaItems) {}

    public record MediaItem(String fileName, int pageNumber, String hqStatus, String lqStatus,
                            long fileSize, String mediaType, Integer width, Integer height,
                            BigDecimal duration, String container, String videoCodec, String audioCodec) {}
}
```

```java
package com.comicatlas.common.metadata;

/** 唯一构建：MetadataV3 → metadata.json v3 字符串（version=3 固定，pretty print）。 */
@Component
public class MetadataJsonBuilder {

    private final ObjectMapper objectMapper;   // 注入 Spring ObjectMapper

    public String build(MetadataV3 metadata) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 3);
        // comic / catalogs / chapters / mediaItems 按现有 v3 结构逐字段组装
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
}
```

**字段映射规则**（与现有 v3 格式逐字一致，禁止改动）：
- `comic`: title, author
- `catalog`: title, sortOrder, parentIndex
- `chapter`: title, chapterNo, sortOrder, globalOrder, catalogIndex, sourceDir(恒空串), mediaItems
- `mediaItem`: fileName, pageNumber, hqStatus, lqStatus, fileSize, mediaType, width, height, duration, container, videoCodec, audioCodec

### 4.2 worker export 包重构

**`ExportService`**（新，export 包）——从 `ExportTaskHandler` 移入并编排：
- 移入 `buildManifest(ExportCollectResult)`（原 150 行，原样搬迁：章节分组/标题清理/目录名去重/`exportFileResolver.resolve` + `resolveToPath`/跳过缺失文件）
- 移入 `buildOutputFileName(Long, String)`、`classifyExportError(Exception)`
- 新增 `ExportOutput export(Long comicId, Long taskId)`：
  ```
  collect = exportCollector.collect(comicId)
  metadataJson = metadataJsonExporter.exportJson(comicId)   // 独立调用（导出低频，二次 collect 查询可接受）
  manifest = buildManifest(collect)
  outputPath = exportRoot.resolve(buildOutputFileName(comicId, collect.comic().getTitle()))
  size = zipBuilder.build(manifest, outputPath)
  return new ExportOutput(taskId, comicId, outputPath.getFileName().toString(), size)
  ```
  > 决策：ExportService 直接调用 `metadataJsonExporter.exportJson(comicId)`（内部再 collect 一次）。导出为低频操作，二次查询代价可忽略，换取 `ExportService`/`MetadataJsonExporter` 各自独立、可单独测试。
- 依赖：`ExportCollector`、`ExportFileResolver`、`ZipBuilder`、`StorageProperties`、`MetadataJsonExporter`

**`MetadataJsonExporter`**（新）——元数据 JSON 生成（导出 + 刷新共用，解耦 B）：
```java
@Component
@RequiredArgsConstructor
public class MetadataJsonExporter {
    private final ExportCollector exportCollector;
    private final MetadataModelMapper modelMapper;
    private final MetadataJsonBuilder metadataJsonBuilder;

    public String exportJson(Long comicId) {
        return metadataJsonBuilder.build(modelMapper.toV3(exportCollector.collect(comicId)));
    }
}
```

**`MetadataModelMapper`**（新）——`ExportCollectResult → MetadataV3`（承载原 `buildMetadataJson` 的字段映射 + `findCatalogIndex`）：
```java
@Component
public class MetadataModelMapper {
    public MetadataV3 toV3(ExportCollectResult result) { ... }
}
```

**`ExportCollector` 瘦身**：删除 `buildMetadataJson`、`findCatalogIndex`、`ObjectMapper` 依赖，只留 `collect()` 查询。

**归位**：
- `ExportFileResolver` → `com.comicatlas.worker.file.storage.ExportFileResolver`（与 StorageRef/StorageRoot 同域；类名不变，import 同步更新）
- `ComicTitleSanitizer` → `com.comicatlas.worker.common.ComicTitleSanitizer`（静态工具归位；类名不变，import 同步更新）

### 4.3 handler 瘦身

**`ExportTaskHandler`**：
```java
@RabbitListener(queues = MqQueues.EXPORT_TASK)
public void handle(ExportTaskCreatedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    Long taskId = event.taskId();
    Long comicId = event.comicId();
    log.info("导出任务开始: taskId={}, comicId={}", taskId, comicId);
    mqConsumerSupport.consume(channel, tag, "导出任务: taskId=" + taskId,
            () -> exportAndPublish(event),
            e -> publishExportFailed(event, e),
            MqConsumerSupport.FailurePolicy.REJECT_TO_DLQ);
}

private void exportAndPublish(ExportTaskCreatedEvent event) throws Exception {
    publishStarted(event);                       // 发 TASK_STARTED
    ExportService.ExportOutput output = exportService.export(event.comicId(), event.taskId());
    publishCompleted(event, output);             // 发 TASK_COMPLETED
}

private void publishExportFailed(ExportTaskCreatedEvent event, Exception failure) {
    // 发 TASK_FAILED，errorCode 用 exportService.classifyExportError(failure)
}
```
- 删除：`buildManifest`、`buildOutputFileName`、`classifyExportError`、`ExportFileResolver`/`ComicTitleSanitizer`/`StorageProperties` 依赖
- 保留：事件发布（started/completed/failed）、`TIMESTAMP_FMT`（移入 ExportService）

**`MetadataRefreshHandler`**：
```java
@RabbitListener(queues = MqQueues.METADATA_REFRESH)
public void handle(MetadataRefreshEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    Long comicId = event.comicId();
    mqConsumerSupport.consume(channel, tag, "元数据刷新: comicId=" + comicId, () -> {
        log.info("收到 metadata 刷新请求: comicId={}", comicId);
        String metadataJson = metadataJsonExporter.exportJson(comicId);
        Path metadataDir = Path.of(mangaRoot, "metadata");
        Files.createDirectories(metadataDir);
        Path metadataFile = metadataDir.resolve(comicId + ".json");
        Files.writeString(metadataFile, metadataJson, StandardCharsets.UTF_8);
        log.info("metadata.json 写入完成: comicId={}, path={}, size={} bytes", comicId, metadataFile, Files.size(metadataFile));
    });
}
```
- 依赖从 `ExportCollector` 改为 `MetadataJsonExporter`

### 4.4 api 侧 MetadataExporter 迁移

- 保留：`export(Long comicId)` 的查询（Comic/Catalog/Chapter/Media/Tag mapper）
- 改：映射层构建 `MetadataV3`（api entity → MetadataV3 映射放 api 侧，如 `ApiMetadataModelMapper` 或 MetadataExporter 内私有方法），JSON 输出走 `MetadataJsonBuilder.build(...)`
- 删除：原 ObjectNode 组装逻辑（约 60 行）
- **保持不变**：`DirectoryImportHandler.buildMetadataMap`（导入写 metadata.json 使用 worker 内存模型 `ComicMetadata`，非导出链路；本次统一聚焦导出/刷新/后台三处的 v3 输出——若后续纳入，需再抽象 ComicMetadata→MetadataV3 映射，列为后续项）

### 4.5 测试策略

1. **`MetadataJsonBuilderTest`**（comic-common）：v3 结构（version=3/comic/catalogs/chapters/mediaItems）、字段映射、null 处理、pretty print
2. **`MetadataModelMapperTest`**（worker）：ExportCollectResult → MetadataV3 映射（含 findCatalogIndex、空列表、null 字段）
3. **`ExportServiceTest`**（worker）：编排（manifest 目录去重、缺失文件跳过、输出文件生成）
4. **回归适配**：`MetadataRefreshHandlerTest`（若有）、api `MetadataExporterTest`（断言 JSON 结构不变）
5. **全链路**：comic-common + worker + api 编译、worker 全量测试、api 相关测试

## 5. 风险与缓解

| 风险 | 缓解 |
|------|------|
| metadata JSON 结构漂移（三处调用方输出不一致） | `MetadataJsonBuilderTest` 固化 v3 结构；`MetadataExporterTest` 回归断言 |
| ExportService 移入后 handler 行为回归 | `ExportServiceTest` + 现有测试回归 |
| ExportFileResolver/ComicTitleSanitizer 移包遗漏引用 | 编译期兜底（import 报错即发现）+ 全量编译 |
| api MetadataExporter 输出变化 | api `MetadataExporterTest` 断言 JSON 字段（width/height/duration 等 null 语义）不变 |
| comic-common 新增 jackson-databind 依赖 | BOM 管理版本，无版本漂移 |

## 6. 排除项（YAGNI）

- **不纳入** `DirectoryImportHandler.buildMetadataMap` 的迁移（不同内存模型，后续项）。
- 不新增导出任务的取消/进度上报能力（非本次范围）。
- 不重命名现有类名（仅移动包/位置），避免无关 diff。
