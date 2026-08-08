# export 包重构与 metadata v3 统一实施计划

**状态**: 历史归档

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 `worker/export` 包（核心编排进 `ExportService`、刷新解耦、包内归位），并将三处 metadata v3 JSON 构建统一到 comic-common 的 `MetadataJsonBuilder`。

**Architecture:** comic-common 新增 `com.comicatlas.common.metadata` 域（`MetadataV3` 通用模型 + `MetadataJsonBuilder` 唯一构建）；worker export 包引入 `ExportService`（编排）/`MetadataJsonExporter`（导出+刷新共用）/`MetadataModelMapper`（entity→V3 映射），`ExportCollector` 瘦身为纯查询，`ExportFileResolver`/`ComicTitleSanitizer` 归位；api 侧 `MetadataExporter` 输出改走共享 builder。

**Tech Stack:** Java 21, Spring Boot 3.3.0, Jackson (jackson-databind), Lombok, Mockito

**Design spec:** `docs/superpowers/specs/2026-08-07-export-package-refactor-design.md`

## Global Constraints

- **产物格式不变**：metadata v3 JSON 结构与 ZIP 布局与现状逐字一致（禁止新增/删除/重命名字段）。
- `MetadataJsonBuilder` 唯一构建逻辑：comic 的 `category`/`tags`、media 的 `width`/`height`/`duration`/`container`/`videoCodec`/`audioCodec` 为**可选字段**——null 时**不输出**该 key；`sourceDir` 恒输出 `""`；`version` 恒 `3`。
- 各调用方通过**映射层**（worker `MetadataModelMapper` / api 内私有映射）适配自身 entity，映射差异（如 api 过滤无效文件名）留在映射层。
- 类名不变，仅移动包（`ExportFileResolver`→`file.storage`、`ComicTitleSanitizer`→`worker.common`），import 同步更新。
- 中断语义、MQ 消费沿用现有 `MqConsumerSupport`（前序计划产物）。
- 日志占位符 `{}`，失败日志保留异常对象。
- 每个 Task 结束运行对应测试并提交，中文提交信息"动作 + 内容"。

---

### Task 1: comic-common `MetadataV3` + `MetadataJsonBuilder` + 单测

**Files:**
- Modify: `comic-common/pom.xml`
- Create: `comic-common/src/main/java/com/comicatlas/common/metadata/MetadataV3.java`
- Create: `comic-common/src/main/java/com/comicatlas/common/metadata/MetadataJsonBuilder.java`
- Test: `comic-common/src/test/java/com/comicatlas/common/metadata/MetadataJsonBuilderTest.java`

**Interfaces:**
- Produces: `MetadataV3`（record 模型）、`MetadataJsonBuilder`（Spring bean）——下游 Task 2/4 依赖。
  - `MetadataV3(Comic comic, List<Catalog> catalogs, List<Chapter> chapters)`
  - `MetadataV3.Comic(String title, String author, String category, List<String> tags)`
  - `MetadataV3.Catalog(String title, int sortOrder, Integer parentIndex)`
  - `MetadataV3.Chapter(String title, String chapterNo, int sortOrder, int globalOrder, Integer catalogIndex, List<MediaItem> mediaItems)`
  - `MetadataV3.MediaItem(String fileName, int pageNumber, String hqStatus, String lqStatus, long fileSize, String mediaType, Integer width, Integer height, BigDecimal duration, String container, String videoCodec, String audioCodec)`
  - `String MetadataJsonBuilder.build(MetadataV3 metadata)`

- [ ] **Step 1: comic-common/pom.xml 加 jackson-databind**

在 `<dependencies>` 内（jackson-annotations 之后）添加：

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

（版本由 spring-boot-starter-parent 3.3.0 BOM 管理）

- [ ] **Step 2: 写失败测试 `MetadataJsonBuilderTest`**

```java
package com.comicatlas.common.metadata;

import com.comicatlas.common.metadata.MetadataV3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataJsonBuilderTest {

    private ObjectMapper objectMapper;
    private MetadataJsonBuilder builder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        builder = new MetadataJsonBuilder(objectMapper);
    }

    @Test
    void build_outputsV3Structure() throws Exception {
        MetadataV3 v3 = new MetadataV3(
                new Comic("标题", "作者", null, null),
                List.of(new Catalog("目录1", 0, null)),
                List.of(new Chapter("章节1", "1", 0, 1, null,
                        List.of(new MediaItem("001.jpg", 1, "READY", "NOT_GENERATED",
                                1000L, "IMAGE", 800, 600, null, null, null, null)))));
        String json = builder.build(v3);
        JsonNode root = objectMapper.readTree(json);
        assertEquals(3, root.path("version").asInt());
        assertEquals("标题", root.path("comic").path("title").asText());
        assertFalse(root.path("comic").has("category"), "category 为 null 时不应输出");
        assertFalse(root.path("comic").has("tags"), "tags 为 null 时不应输出");
        assertEquals(1, root.path("catalogs").size());
        assertEquals(1, root.path("chapters").size());
        JsonNode media = root.path("chapters").get(0).path("mediaItems").get(0);
        assertEquals("001.jpg", media.path("fileName").asText());
        assertEquals("", root.path("chapters").get(0).path("sourceDir").asText());
        assertFalse(media.has("duration"), "duration 为 null 时不应输出");
    }

    @Test
    void build_outputsOptionalFieldsWhenPresent() throws Exception {
        MetadataV3 v3 = new MetadataV3(
                new Comic("标题", "作者", "同人", List.of("tag1", "tag2")),
                List.of(),
                List.of(new Chapter("章节1", "1", 0, 1, 0,
                        List.of(new MediaItem("v1.mp4", 1, "READY", "NOT_GENERATED",
                                2000L, "VIDEO", 1920, 1080,
                                new BigDecimal("12.5"), "mp4", "h264", "aac")))));
        JsonNode root = objectMapper.readTree(builder.build(v3));
        assertEquals("同人", root.path("comic").path("category").asText());
        assertEquals(2, root.path("comic").path("tags").size());
        JsonNode media = root.path("chapters").get(0).path("mediaItems").get(0);
        assertEquals("12.5", media.path("duration").asText());
        assertEquals("h264", media.path("videoCodec").asText());
        assertEquals(0, root.path("chapters").get(0).path("catalogIndex").asInt());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `.\mvnw -pl comic-common test -Dtest=MetadataJsonBuilderTest`
Expected: 编译失败（MetadataV3/MetadataJsonBuilder 不存在）

- [ ] **Step 4: 实现 `MetadataV3` 与 `MetadataJsonBuilder`**

```java
package com.comicatlas.common.metadata;

import java.math.BigDecimal;
import java.util.List;

/** metadata v3 通用模型 — 与 ComicAtlas v3 格式一一对应，各模块映射实体后共享构建。 */
public record MetadataV3(
        Comic comic,
        List<Catalog> catalogs,
        List<Chapter> chapters) {

    /** category/tags 为可选（api 侧输出，worker 侧为 null）。 */
    public record Comic(String title, String author, String category, List<String> tags) {}

    public record Catalog(String title, int sortOrder, Integer parentIndex) {}

    public record Chapter(String title, String chapterNo, int sortOrder, int globalOrder,
                          Integer catalogIndex, List<MediaItem> mediaItems) {}

    /** width/height/duration/container/videoCodec/audioCodec 可选（null 时不输出）。 */
    public record MediaItem(String fileName, int pageNumber, String hqStatus, String lqStatus,
                            long fileSize, String mediaType, Integer width, Integer height,
                            BigDecimal duration, String container, String videoCodec, String audioCodec) {}
}
```

```java
package com.comicatlas.common.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 唯一构建：MetadataV3 → metadata.json v3 字符串（version=3 固定，pretty print）。 */
public class MetadataJsonBuilder {

    private final ObjectMapper objectMapper;

    public MetadataJsonBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(MetadataV3 metadata) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 3);

        ObjectNode comicNode = root.putObject("comic");
        MetadataV3.Comic comic = metadata.comic();
        comicNode.put("title", comic.title() != null ? comic.title() : "");
        comicNode.put("author", comic.author() != null ? comic.author() : "");
        if (comic.category() != null) {
            comicNode.put("category", comic.category());
        }
        if (comic.tags() != null) {
            ArrayNode tagsArray = comicNode.putArray("tags");
            comic.tags().forEach(tagsArray::add);
        }

        ArrayNode catalogsArray = root.putArray("catalogs");
        for (MetadataV3.Catalog catalog : metadata.catalogs()) {
            ObjectNode catNode = catalogsArray.addObject();
            catNode.put("title", catalog.title());
            catNode.put("sortOrder", catalog.sortOrder());
            if (catalog.parentIndex() != null) {
                catNode.put("parentIndex", catalog.parentIndex());
            }
        }

        ArrayNode chaptersArray = root.putArray("chapters");
        for (MetadataV3.Chapter chapter : metadata.chapters()) {
            ObjectNode chNode = chaptersArray.addObject();
            chNode.put("title", chapter.title() != null ? chapter.title() : "");
            chNode.put("chapterNo", chapter.chapterNo() != null ? chapter.chapterNo() : "");
            chNode.put("sortOrder", chapter.sortOrder());
            chNode.put("globalOrder", chapter.globalOrder());
            if (chapter.catalogIndex() != null) {
                chNode.put("catalogIndex", chapter.catalogIndex());
            }
            chNode.put("sourceDir", "");
            ArrayNode mediaArray = chNode.putArray("mediaItems");
            for (MetadataV3.MediaItem media : chapter.mediaItems()) {
                ObjectNode mNode = mediaArray.addObject();
                mNode.put("fileName", media.fileName());
                mNode.put("pageNumber", media.pageNumber());
                mNode.put("hqStatus", media.hqStatus() != null ? media.hqStatus() : "READY");
                mNode.put("lqStatus", media.lqStatus() != null ? media.lqStatus() : "NOT_GENERATED");
                mNode.put("fileSize", media.fileSize());
                mNode.put("mediaType", media.mediaType() != null ? media.mediaType() : "IMAGE");
                if (media.width() != null) { mNode.put("width", media.width()); }
                if (media.height() != null) { mNode.put("height", media.height()); }
                if (media.duration() != null) { mNode.put("duration", media.duration()); }
                if (media.container() != null) { mNode.put("container", media.container()); }
                if (media.videoCodec() != null) { mNode.put("videoCodec", media.videoCodec()); }
                if (media.audioCodec() != null) { mNode.put("audioCodec", media.audioCodec()); }
            }
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\mvnw -pl comic-common test -Dtest=MetadataJsonBuilderTest`
Expected: 2 tests, 0 failures, BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add comic-common/pom.xml comic-common/src/main/java/com/comicatlas/common/metadata/ comic-common/src/test/java/com/comicatlas/common/metadata/
git commit -m "新增 MetadataV3 通用模型与 MetadataJsonBuilder：metadata v3 JSON 构建唯一化"
```

---

### Task 2: worker export 包核心重构（Mapper + Exporter + Collector 瘦身 + ExportService）

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/export/MetadataModelMapper.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/export/MetadataJsonExporter.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/export/ExportService.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/export/ExportCollector.java`（删 buildMetadataJson/findCatalogIndex/ObjectMapper）
- Test: `worker-service/src/test/java/com/comicatlas/worker/export/MetadataModelMapperTest.java`
- Test: `worker-service/src/test/java/com/comicatlas/worker/export/ExportServiceTest.java`

**Interfaces:**
- Consumes: `MetadataV3`/`MetadataJsonBuilder`（Task 1）；现有 `ExportCollector`/`ExportFileResolver`/`ZipBuilder`/`StorageProperties`/`ExportManifest`/`ExportCollectResult`
- Produces:
  - `MetadataV3 MetadataModelMapper.toV3(ExportCollectResult result)`
  - `String MetadataJsonExporter.exportJson(Long comicId)`
  - `ExportService.ExportOutput export(Long comicId, Long taskId)`（record：taskId, comicId, fileName, size）
  - `String ExportService.classifyExportError(Exception e)`（public，供 handler 发失败事件用）

- [ ] **Step 1: 写失败测试 `MetadataModelMapperTest`**

```java
package com.comicatlas.worker.export;

import com.comicatlas.common.metadata.MetadataV3;
import com.comicatlas.worker.entity.ExportCatalog;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataModelMapperTest {

    private final MetadataModelMapper mapper = new MetadataModelMapper();

    private ExportMedia media(Long id, Long chapterId, String hqPath, String mediaType, Integer pageNumber) {
        ExportMedia m = new ExportMedia();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setHqPath(hqPath);
        m.setMediaType(mediaType);
        m.setPageNumber(pageNumber);
        m.setHqStatus("READY");
        m.setLqStatus("NOT_GENERATED");
        m.setFileSize(100L);
        return m;
    }

    @Test
    void toV3_mapsFieldsWithCatalogIndex() {
        ExportComic comic = new ExportComic();
        comic.setId(1L);
        comic.setTitle("标题");
        comic.setAuthor("作者");

        ExportCatalog cat1 = new ExportCatalog();
        cat1.setId(10L);
        cat1.setComicId(1L);
        cat1.setTitle("目录1");
        cat1.setSortOrder(0);
        cat1.setParentId(null);

        ExportChapter ch = new ExportChapter();
        ch.setId(20L);
        ch.setComicId(1L);
        ch.setCatalogId(10L);
        ch.setTitle("章节1");
        ch.setChapterNo("1");
        ch.setSortOrder(0);
        ch.setGlobalOrder(1);

        ExportMedia m = media(100L, 20L, "1/20/001.jpg", "IMAGE", 1);

        ExportCollectResult result = new ExportCollectResult(comic, List.of(ch), List.of(cat1), List.of(m), null);
        MetadataV3 v3 = mapper.toV3(result);

        assertEquals("标题", v3.comic().title());
        assertNull(v3.comic().category());
        assertEquals(1, v3.catalogs().size());
        assertEquals("目录1", v3.catalogs().get(0).title());
        assertEquals(0, v3.chapters().get(0).catalogIndex().intValue(), "catalogIndex 应映射为 catalogs 列表索引");
        assertEquals(1, v3.chapters().get(0).mediaItems().size());
        assertEquals("001.jpg", v3.chapters().get(0).mediaItems().get(0).fileName());
        assertEquals("IMAGE", v3.chapters().get(0).mediaItems().get(0).mediaType());
    }

    @Test
    void toV3_handlesEmptyAndNull() {
        ExportComic comic = new ExportComic();
        comic.setId(1L);
        comic.setTitle(null);
        comic.setAuthor(null);
        ExportCollectResult result = new ExportCollectResult(comic, List.of(), List.of(), List.of(), null);
        MetadataV3 v3 = mapper.toV3(result);
        assertEquals("", v3.comic().title());
        assertTrue(v3.chapters().isEmpty());
        assertTrue(v3.catalogs().isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw -pl worker-service -am test -Dtest=MetadataModelMapperTest -DfailIfNoTests=false`
Expected: 编译失败（MetadataModelMapper 不存在）

- [ ] **Step 3: 实现 `MetadataModelMapper`**

```java
package com.comicatlas.worker.export;

import com.comicatlas.common.metadata.MetadataV3;
import com.comicatlas.worker.entity.ExportCatalog;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** worker entity(Export*) → MetadataV3 通用模型映射。 */
@Component
public class MetadataModelMapper {

    public MetadataV3 toV3(ExportCollectResult result) {
        ExportComic comic = result.comic();
        MetadataV3.Comic comicInfo = new MetadataV3.Comic(
                comic.getTitle() != null ? comic.getTitle() : "",
                comic.getAuthor() != null ? comic.getAuthor() : "",
                null, null);

        List<MetadataV3.Catalog> catalogs = new ArrayList<>();
        for (int i = 0; i < result.catalogs().size(); i++) {
            ExportCatalog cat = result.catalogs().get(i);
            catalogs.add(new MetadataV3.Catalog(
                    cat.getTitle() != null ? cat.getTitle() : "",
                    cat.getSortOrder() != null ? cat.getSortOrder() : i,
                    cat.getParentId() != null ? findCatalogIndex(result.catalogs(), cat.getParentId()) : null));
        }

        Map<Long, List<ExportMedia>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(ExportMedia::getChapterId));

        List<MetadataV3.Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < result.chapters().size(); i++) {
            ExportChapter chapter = result.chapters().get(i);
            List<MetadataV3.MediaItem> mediaItems = new ArrayList<>();
            for (ExportMedia media : mediaByChapter.getOrDefault(chapter.getId(), List.of())) {
                mediaItems.add(new MetadataV3.MediaItem(
                        extractFileName(media.getHqPath()),
                        media.getPageNumber() != null ? media.getPageNumber() : 0,
                        media.getHqStatus() != null ? media.getHqStatus() : "READY",
                        media.getLqStatus() != null ? media.getLqStatus() : "NOT_GENERATED",
                        media.getFileSize() != null ? media.getFileSize() : 0L,
                        media.getMediaType() != null ? media.getMediaType() : "IMAGE",
                        media.getWidth(), media.getHeight(), media.getDuration(), media.getContainer(),
                        media.getVideoCodec(), media.getAudioCodec()));
            }
            chapters.add(new MetadataV3.Chapter(
                    chapter.getTitle() != null ? chapter.getTitle() : "",
                    chapter.getChapterNo() != null ? chapter.getChapterNo() : "",
                    chapter.getSortOrder() != null ? chapter.getSortOrder() : i,
                    chapter.getGlobalOrder() != null ? chapter.getGlobalOrder() : i,
                    chapter.getCatalogId() != null ? findCatalogIndex(result.catalogs(), chapter.getCatalogId()) : null,
                    mediaItems));
        }
        return new MetadataV3(comicInfo, catalogs, chapters);
    }

    private static String extractFileName(String hqPath) {
        if (hqPath == null || !hqPath.contains("/")) {
            return "";
        }
        return hqPath.substring(hqPath.lastIndexOf('/') + 1);
    }

    private static Integer findCatalogIndex(List<ExportCatalog> catalogs, Long catalogId) {
        for (int i = 0; i < catalogs.size(); i++) {
            if (catalogs.get(i).getId().equals(catalogId)) {
                return i;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\mvnw -pl worker-service -am test -Dtest=MetadataModelMapperTest -DfailIfNoTests=false`
Expected: 2 tests, 0 failures

- [ ] **Step 5: 实现 `MetadataJsonExporter`**

```java
package com.comicatlas.worker.export;

import com.comicatlas.common.metadata.MetadataJsonBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 元数据 JSON 生成：collect → map → build（导出与元数据刷新共用）。 */
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

- [ ] **Step 6: `ExportCollector` 瘦身**（删除 `buildMetadataJson`、`findCatalogIndex`、`ObjectMapper` 字段与 import；只留 `collect()` 查询）

- [ ] **Step 7: 实现 `ExportService`**（承接 `ExportTaskHandler` 的 buildManifest/buildOutputFileName/classifyExportError + 编排）

```java
package com.comicatlas.worker.export;

import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.export.ExportFileResolver;
import com.comicatlas.worker.export.ComicTitleSanitizer;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRef;
import com.comicatlas.worker.file.storage.StorageRoot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 导出编排：收集 → 构建清单 → 打包 ZIP。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ExportCollector exportCollector;
    private final ExportFileResolver exportFileResolver;
    private final ZipBuilder zipBuilder;
    private final MetadataJsonExporter metadataJsonExporter;
    private final StorageProperties storageProperties;

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public record ExportOutput(Long taskId, Long comicId, String fileName, long size) {}

    public ExportOutput export(Long comicId, Long taskId) {
        ExportCollectResult result = exportCollector.collect(comicId);
        ExportManifest manifest = buildManifest(result);

        StorageRoot exportRoot = storageProperties.getRoots().get("EXPORT");
        if (exportRoot == null || !exportRoot.exists()) {
            throw new IllegalStateException("EXPORT 存储根未配置或路径不存在");
        }

        String outputFileName = buildOutputFileName(comicId, result.comic().getTitle());
        Path outputPath = exportRoot.resolve(outputFileName);
        long outputSize = zipBuilder.build(manifest, outputPath);
        return new ExportOutput(taskId, comicId, outputPath.getFileName().toString(), outputSize);
    }

    /** 供 handler 发失败事件使用。 */
    public String classifyExportError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (msg.contains("ZIP") || msg.contains("zip")) { return "ZIP_ERROR"; }
        if (msg.contains("collect") || msg.contains("Collect")) { return "COLLECT_ERROR"; }
        if (msg.contains("manifest") || msg.contains("Manifest")) { return "MANIFEST_ERROR"; }
        if (msg.contains("STORAGE") || msg.contains("storage") || msg.contains("EXPORT")) { return "STORAGE_ERROR"; }
        return "EXPORT_ERROR";
    }

    private ExportManifest buildManifest(ExportCollectResult result) {
        String rootDirName = ComicTitleSanitizer.sanitize(result.comic().getTitle());

        List<ExportManifest.Entry> entries = new ArrayList<>();
        Map<Long, List<ExportMedia>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(ExportMedia::getChapterId));

        Map<Long, String> chapterTitles = result.chapters().stream()
                .collect(Collectors.toMap(ExportChapter::getId, chapter ->
                        chapter.getTitle() != null && !chapter.getTitle().isBlank()
                                ? ComicTitleSanitizer.sanitize(chapter.getTitle())
                                : "chapter_" + chapter.getId()));

        Set<String> usedPaths = new HashSet<>();
        for (ExportChapter chapter : result.chapters()) {
            String chapterDir = chapterTitles.getOrDefault(chapter.getId(), "chapter_" + chapter.getId());
            String uniqueDir = chapterDir;
            int counter = 1;
            while (usedPaths.contains(uniqueDir)) {
                uniqueDir = chapterDir + "(" + counter + ")";
                counter++;
            }
            usedPaths.add(uniqueDir);

            List<ExportMedia> chapterMedia = mediaByChapter.getOrDefault(chapter.getId(), List.of());
            List<ExportMedia> sortedMedia = chapterMedia.stream()
                    .sorted(Comparator.comparing(ExportMedia::getPageNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (ExportMedia media : sortedMedia) {
                try {
                    StorageRef ref = exportFileResolver.resolve(media);
                    Path sourceFile = exportFileResolver.resolveToPath(ref);
                    if (!Files.exists(sourceFile)) {
                        log.warn("导出跳过缺失文件: comicId={}, mediaId={}, path={}",
                                result.comic().getId(), media.getId(), sourceFile);
                        continue;
                    }
                    String fileName = Path.of(ref.relativePath()).getFileName().toString();
                    String targetPath = uniqueDir + "/" + fileName;
                    entries.add(new ExportManifest.Entry(targetPath, sourceFile));
                } catch (ExportFileNotFoundException e) {
                    log.warn("导出跳过无可用文件: comicId={}, mediaId={}", result.comic().getId(), media.getId());
                }
            }
        }
        return new ExportManifest(rootDirName, metadataJsonExporter.exportJson(result.comic().getId()), entries);
    }

    private String buildOutputFileName(Long comicId, String title) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String safeTitle = ComicTitleSanitizer.sanitize(title);
        return safeTitle + "_" + comicId + "_" + timestamp + ".zip";
    }
}
```

> 注：`ExportService` 内部 `buildManifest` 直接调用 `metadataJsonExporter.exportJson(comicId)`（独立二次 collect，见 spec §4.2 决策）。

- [ ] **Step 8: 写失败测试 `ExportServiceTest`**

```java
package com.comicatlas.worker.export;

import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRef;
import com.comicatlas.worker.file.storage.StorageRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class ExportServiceTest {

    @TempDir
    Path tempDir;

    private ExportCollector exportCollector;
    private ExportFileResolver exportFileResolver;
    private ZipBuilder zipBuilder;
    private MetadataJsonExporter metadataJsonExporter;
    private StorageProperties storageProperties;
    private ExportService service;

    @BeforeEach
    void setUp() throws IOException {
        exportCollector = mock(ExportCollector.class);
        exportFileResolver = mock(ExportFileResolver.class);
        zipBuilder = mock(ZipBuilder.class);
        metadataJsonExporter = mock(MetadataJsonExporter.class);

        storageProperties = new StorageProperties();
        StorageRoot exportRoot = new StorageRoot();
        exportRoot.setPath(tempDir.resolve("export"));
        Files.createDirectories(exportRoot.getPath());
        storageProperties.setRoots(Map.of("EXPORT", exportRoot));

        service = new ExportService(exportCollector, exportFileResolver, zipBuilder,
                metadataJsonExporter, storageProperties);
    }

    private ExportComic comic(Long id, String title) {
        ExportComic c = new ExportComic();
        c.setId(id);
        c.setTitle(title);
        return c;
    }

    private ExportChapter chapter(Long id, String title, int globalOrder) {
        ExportChapter ch = new ExportChapter();
        ch.setId(id);
        ch.setTitle(title);
        ch.setGlobalOrder(globalOrder);
        return ch;
    }

    private ExportMedia media(Long id, Long chapterId, String hqPath, Integer pageNumber) {
        ExportMedia m = new ExportMedia();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setHqPath(hqPath);
        m.setMediaType("IMAGE");
        m.setPageNumber(pageNumber);
        return m;
    }

    @Test
    void export_buildsManifestAndZip() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        ExportMedia m2 = media(2L, 10L, "1/10/002.jpg", 2);
        ExportCollectResult result = new ExportCollectResult(
                comic(1L, "测试标题"),
                List.of(chapter(10L, "第一章", 1)),
                List.of(), List.of(m1, m2), null);

        when(exportCollector.collect(1L)).thenReturn(result);
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(m2)).thenReturn(new StorageRef("HQ", "1/10/002.jpg"));
        Path src1 = tempDir.resolve("hq/1/10/001.jpg");
        Path src2 = tempDir.resolve("hq/1/10/002.jpg");
        Files.createDirectories(src1.getParent());
        Files.writeString(src1, "a");
        Files.writeString(src2, "b");
        when(exportFileResolver.resolveToPath(any(StorageRef.class))).thenAnswer(inv ->
                tempDir.resolve(inv.<StorageRef>getArgument(0).relativePath()));
        when(zipBuilder.build(any(), any())).thenReturn(1234L);

        ExportService.ExportOutput output = service.export(1L, 99L);

        assertEquals(99L, output.taskId());
        assertEquals(1234L, output.size());
        assertTrue(output.fileName().startsWith("测试标题_1_"), "输出文件名应含清理后标题+comicId");
        assertTrue(output.fileName().endsWith(".zip"));
        ArgumentCaptor<ExportManifest> manifestCaptor = ArgumentCaptor.forClass(ExportManifest.class);
        verify(zipBuilder).build(manifestCaptor.capture(), any());
        assertEquals(2, manifestCaptor.getValue().entries().size());
        assertEquals("第一章/001.jpg", manifestCaptor.getValue().entries().get(0).targetPath());
    }

    @Test
    void export_skipsMissingFilesAndDeduplicatesChapterDirs() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        ExportMedia m2 = media(2L, 11L, "1/11/001.jpg", 1);
        ExportCollectResult result = new ExportCollectResult(
                comic(1L, "标题"),
                List.of(chapter(10L, "同名章", 1), chapter(11L, "同名章", 2)),
                List.of(), List.of(m1, m2), null);

        when(exportCollector.collect(1L)).thenReturn(result);
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        // m1 源文件缺失（跳过）；m2 源存在
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(m2)).thenReturn(new StorageRef("HQ", "1/11/001.jpg"));
        Path src2 = tempDir.resolve("hq/1/11/001.jpg");
        Files.createDirectories(src2.getParent());
        Files.writeString(src2, "b");
        when(exportFileResolver.resolveToPath(any(StorageRef.class))).thenAnswer(inv ->
                tempDir.resolve(inv.<StorageRef>getArgument(0).relativePath()));
        when(zipBuilder.build(any(), any())).thenReturn(10L);

        service.export(1L, 99L);

        ArgumentCaptor<ExportManifest> manifestCaptor = ArgumentCaptor.forClass(ExportManifest.class);
        verify(zipBuilder).build(manifestCaptor.capture(), any());
        assertEquals(1, manifestCaptor.getValue().entries().size(), "缺失文件应被跳过");
        assertEquals("同名章(1)/001.jpg", manifestCaptor.getValue().entries().get(0).targetPath(),
                "同名章节目录应去重为 同名章(1)");
    }

    @Test
    void classifyExportError_knownTypes() {
        assertEquals("ZIP_ERROR", service.classifyExportError(new RuntimeException("zip 损坏")));
        assertEquals("STORAGE_ERROR", service.classifyExportError(new RuntimeException("EXPORT 根目录")));
        assertEquals("EXPORT_ERROR", service.classifyExportError(new RuntimeException("其他")));
    }
}
```

> 注：`ExportServiceTest` 用 Mockito mock 依赖验证 `export()` 编排（manifest 构建/去重/跳过缺失/输出文件）；`MetadataModelMapperTest` 覆盖 entity→V3 映射。

- [ ] **Step 9: 运行测试确认通过**

Run: `.\mvnw -pl worker-service -am test -Dtest=MetadataModelMapperTest,ExportServiceTest -DfailIfNoTests=false`
Expected: 全部通过，BUILD SUCCESS

- [ ] **Step 10: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/export/ worker-service/src/test/java/com/comicatlas/worker/export/
git commit -m "重构 worker export 包：新增 ExportService/MetadataJsonExporter/MetadataModelMapper，ExportCollector 瘦身为纯查询"
```

---

### Task 3: 归位（ExportFileResolver / ComicTitleSanitizer 移包）+ handler 瘦身

**Files:**
- Move: `worker-service/src/main/java/com/comicatlas/worker/export/ExportFileResolver.java` → `.../worker/file/storage/ExportFileResolver.java`（package 改 `com.comicatlas.worker.file.storage`，类名不变）
- Move: `worker-service/src/main/java/com/comicatlas/worker/export/ComicTitleSanitizer.java` → `.../worker/common/ComicTitleSanitizer.java`（package 改 `com.comicatlas.worker.common`，类名不变）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/ExportTaskHandler.java`（瘦身：删 buildManifest/buildOutputFileName/classifyExportError，改调 ExportService）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/MetadataRefreshHandler.java`（改调 MetadataJsonExporter）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/MetadataRefreshCommandHandler.java`（改调 MetadataJsonExporter——review 发现其同样依赖 collect().metadataJson() 会 NPE）

**Interfaces:**
- Consumes: `ExportService`（Task 2）、`MetadataJsonExporter`（Task 2）
- Produces: 无新接口

- [ ] **Step 1: 移动 `ExportFileResolver` 与 `ComicTitleSanitizer`**

- `ExportFileResolver.java`：移动到 `worker-service/src/main/java/com/comicatlas/worker/file/storage/`，package 声明改为 `com.comicatlas.worker.file.storage`，import 无变化（StorageProperties/StorageRef/StorageRoot 同包）。
- `ComicTitleSanitizer.java`：移动到 `worker-service/src/main/java/com/comicatlas/worker/common/`，package 声明改为 `com.comicatlas.worker.common`。
- **同步更新**：`ExportService.java` 中 `import com.comicatlas.worker.export.ComicTitleSanitizer` 改为 `import com.comicatlas.worker.common.ComicTitleSanitizer`（Task 2 时该类仍在 export 包）。

- [ ] **Step 2: `ExportTaskHandler` 瘦身**

替换 `ExportTaskHandler.java` 为（保留 MQ 协议 + 事件发布，业务委托 ExportService）：

```java
package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.export.ExportService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 导出任务 MQ 消费者 — 只负责协议与事件发布，业务编排委托 ExportService。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportTaskHandler {

    private final RabbitTemplate rabbitTemplate;
    private final ExportService exportService;
    private final MqConsumerSupport mqConsumerSupport;

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
        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_STARTED,
                new ExportTaskStartedEvent(UUID.randomUUID(), Instant.now(),
                        event.taskId(), event.comicId()));
        log.info("已发布 ExportTaskStartedEvent: taskId={}", event.taskId());

        ExportService.ExportOutput output = exportService.export(event.comicId(), event.taskId());

        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_COMPLETED,
                new ExportTaskCompletedEvent(UUID.randomUUID(), Instant.now(),
                        event.taskId(), event.comicId(), "EXPORT",
                        output.fileName(), output.size()));
        log.info("已发布 ExportTaskCompletedEvent: taskId={}, size={}", event.taskId(), output.size());
    }

    private void publishExportFailed(ExportTaskCreatedEvent event, Exception failure) {
        String errorCode = exportService.classifyExportError(failure);
        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_FAILED,
                new ExportTaskFailedEvent(UUID.randomUUID(), Instant.now(),
                        event.taskId(), event.comicId(), errorCode, failure.getMessage()));
    }
}
```

- [ ] **Step 3: `MetadataRefreshHandler` 改调 `MetadataJsonExporter`**

替换 `MetadataRefreshHandler.java` 为：

```java
package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.export.MetadataJsonExporter;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataRefreshHandler {

    private final MetadataJsonExporter metadataJsonExporter;
    @Value("${worker.manga-root}")
    private String mangaRoot;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.METADATA_REFRESH)
    public void handle(MetadataRefreshEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        mqConsumerSupport.consume(channel, tag, "元数据刷新: comicId=" + comicId, () -> {
            log.info("收到 metadata 刷新请求: comicId={}", comicId);
            String metadataJson = metadataJsonExporter.exportJson(comicId);
            Path metadataDir = Path.of(mangaRoot, "metadata");
            Files.createDirectories(metadataDir);
            Path metadataFile = metadataDir.resolve(comicId + ".json");
            Files.writeString(metadataFile, metadataJson, StandardCharsets.UTF_8);
            long fileSize = Files.size(metadataFile);
            log.info("metadata.json 写入完成: comicId={}, path={}, size={} bytes",
                    comicId, metadataFile, fileSize);
        });
    }
}
```

- [ ] **Step 4: `MetadataRefreshCommandHandler` 改调 `MetadataJsonExporter`**

替换 `MetadataRefreshCommandHandler.refresh()` 方法为（依赖从 `ExportCollector` 改为 `MetadataJsonExporter`，消除 `collect().metadataJson()` 恒 null 的 NPE）：

```java
public void refresh(ManagementCommandRequestedEvent cmd) {
    Long comicId = cmd.targetId();
    try {
        publisher.progress(cmd, 10, "开始刷新元数据");
        String metadataJson = metadataJsonExporter.exportJson(comicId);

        Path metadataDir = config.getMetadataDir() != null
                ? Path.of(config.getMetadataDir())
                : Path.of(config.getMangaRoot(), "metadata");
        Files.createDirectories(metadataDir);
        Path metadataFile = metadataDir.resolve(comicId + ".json");
        Files.writeString(metadataFile, metadataJson, StandardCharsets.UTF_8);

        publisher.progress(cmd, 100, "元数据刷新完成");
        publisher.completed(cmd);
        log.info("元数据刷新命令完成: comicId={}, size={} bytes", comicId, Files.size(metadataFile));
    } catch (Exception e) {
        log.error("元数据刷新命令失败: comicId={}", comicId, e);
        publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }
}
```

字段改为 `private final MetadataJsonExporter metadataJsonExporter;`（删除 `ExportCollector` 字段与 import），import 更新。

- [ ] **Step 5: 编译 + 全量 worker 测试**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: BUILD SUCCESS，全部通过（基线 48）

- [ ] **Step 6: 提交**

```bash
git add -A worker-service/src/main/java/com/comicatlas/worker/
git commit -m "导出组件归位与 handler 瘦身：ExportFileResolver 移 storage 域、ComicTitleSanitizer 移 common，ExportTaskHandler/MetadataRefreshHandler/MetadataRefreshCommandHandler 委托 ExportService/MetadataJsonExporter"
```

---

### Task 4: api 侧 `MetadataExporter` 迁移到共享 MetadataJsonBuilder

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/admin/service/MetadataExporter.java`
- Test: `api-service/src/test/java/com/comicatlas/api/admin/service/MetadataExporterTest.java`（回归适配）

**Interfaces:**
- Consumes: `MetadataV3`/`MetadataJsonBuilder`（Task 1）
- Produces: 无（行为不变：仍写 `{METADATA}/comicId.json`，返回 Path）

- [ ] **Step 1: 重构 `MetadataExporter.export(Long comicId)`**

保留：comic/catalogs/chapters/media 的 mapper 查询 + `catalogIdToIndex` + `tagNames` 收集 + 无效文件名过滤（`fileName.isEmpty() || "null".equals(fileName)` 时 continue）+ 写文件逻辑。

替换 JSON 组装：构建 `MetadataV3` 后调用 `metadataJsonBuilder.build(...)`，再写文件：

```java
// 组装 MetadataV3
MetadataV3.Comic comicInfo = new MetadataV3.Comic(
        comic.getTitle() != null ? comic.getTitle() : "",
        comic.getAuthor() != null ? comic.getAuthor() : "",
        comic.getCategory() != null ? comic.getCategory() : "",
        tagNames);

List<MetadataV3.Catalog> catalogList = new ArrayList<>();
for (Catalog cat : catalogs) {
    catalogList.add(new MetadataV3.Catalog(
            cat.getTitle(),
            cat.getSortOrder() != null ? cat.getSortOrder() : 0,
            cat.getParentId() != null ? catalogIdToIndex.get(cat.getParentId()) : null));
}

List<MetadataV3.Chapter> chapterList = new ArrayList<>();
for (Chapter chapter : chapters) {
    List<Media> mediaItems = mediaMapper.selectList(
            new LambdaQueryWrapper<Media>()
                    .eq(Media::getChapterId, chapter.getId())
                    .orderByAsc(Media::getPageNumber));

    List<MetadataV3.MediaItem> mediaItemList = new ArrayList<>();
    for (Media media : mediaItems) {
        String hqPath = media.getHqPath();
        String fileName = "";
        if (hqPath != null && hqPath.contains("/")) {
            fileName = hqPath.substring(hqPath.lastIndexOf('/') + 1);
        }
        if (fileName.isEmpty() || "null".equals(fileName)) {
            continue;
        }
        mediaItemList.add(new MetadataV3.MediaItem(
                fileName,
                media.getPageNumber() != null ? media.getPageNumber() : 0,
                media.getHqStatus() != null ? media.getHqStatus().name() : "READY",
                media.getLqStatus() != null ? media.getLqStatus().name() : "NOT_GENERATED",
                media.getFileSize() != null ? media.getFileSize() : 0,
                media.getMediaType() != null ? media.getMediaType() : "IMAGE",
                media.getWidth(), media.getHeight(), media.getDuration(),
                media.getContainer(), media.getVideoCodec(), media.getAudioCodec()));
    }
    chapterList.add(new MetadataV3.Chapter(
            chapter.getTitle(),
            chapter.getChapterNo() != null ? chapter.getChapterNo() : "",
            chapter.getSortOrder() != null ? chapter.getSortOrder() : 0,
            chapter.getGlobalOrder() != null ? chapter.getGlobalOrder() : 0,
            chapter.getCatalogId() != null ? catalogIdToIndex.get(chapter.getCatalogId()) : null,
            mediaItemList));
}

MetadataV3 v3 = new MetadataV3(comicInfo, catalogList, chapterList);
String json = metadataJsonBuilder.build(v3);

// 写入 METADATA 存储根
Path metaPath = storageProperties.root("METADATA").resolve(comicId + ".json");
Files.createDirectories(metaPath.getParent());
Files.writeString(metaPath, json, StandardCharsets.UTF_8);
log.info("Metadata exported: comicId={}, path={}", comicId, metaPath);
return metaPath;
```

要点：
- 注入 `MetadataJsonBuilder metadataJsonBuilder`（api 侧需注册 bean——见 Step 2）
- 删除原 `ObjectMapper` 的 JSON 组装（`root.put("version", 3)` 等约 60 行）；`objectMapper` 字段若仅用于组装则删除，否则保留
- **必须保留**：api 特有行为（comic 含 `category`/`tags`、media 无效文件名过滤、catalogIndex 用 `catalogIdToIndex`）
- **产物格式不变**：builder 对 category/tags 非 null 时输出（api 传入即输出），worker 传入 null 不输出——两侧 JSON 均与现状一致

- [ ] **Step 2: api 侧注册 `MetadataJsonBuilder` bean**

`MqConsumerSupportConfig` 模式同款：新建 `api-service/src/main/java/com/comicatlas/api/config/MetadataJsonBuilderConfig.java`：

```java
package com.comicatlas.api.config;

import com.comicatlas.common.metadata.MetadataJsonBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MetadataJsonBuilder 注册（位于 comic-common，不在 API 默认扫描范围）。
 */
@Configuration
public class MetadataJsonBuilderConfig {

    @Bean
    public MetadataJsonBuilder metadataJsonBuilder(ObjectMapper objectMapper) {
        return new MetadataJsonBuilder(objectMapper);
    }
}
```

- [ ] **Step 3: 编译 + 回归测试**

Run: `.\mvnw -pl api-service -am test "-Dtest=MetadataExporterTest" "-Dmaven.compiler.testExcludes=**/MediaUploadManagementIT.java" -DfailIfNoTests=false "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: MetadataExporterTest 全部通过（JSON 结构断言不变）

- [ ] **Step 4: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/admin/service/MetadataExporter.java api-service/src/main/java/com/comicatlas/api/config/MetadataJsonBuilderConfig.java api-service/src/test/java/com/comicatlas/api/admin/service/MetadataExporterTest.java
git commit -m "api MetadataExporter 迁移共享 MetadataJsonBuilder：消除 metadata v3 第三处重复"
```

---

### Task 5: 全链路验证与收尾

- [ ] **Step 1: 全量编译**

Run: `.\mvnw -pl api-service,worker-service -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: 全量测试**

- worker: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false` — 全部通过
- api（排除既有 MediaUploadManagementIT 编译问题）: `.\mvnw -pl api-service -am test "-Dmaven.compiler.testExcludes=**/MediaUploadManagementIT.java" -DfailIfNoTests=false` — 全部通过

- [ ] **Step 3: 确认无重复构建残留**

在 comic-common/worker/api 的 main 下 grep `put\("version", 3\)|"version", 3`——Expected: 仅 `MetadataJsonBuilder.java` 命中（`DirectoryImportHandler` 的导入路径 metadata 构建属另一内存模型，按 spec §6 排除项不纳入，若有残留先确认归属再定）

- [ ] **Step 4: 提交收尾（若有遗漏）**

```bash
git add -A
git status
git commit -m "export 包重构与 metadata v3 统一收尾"
```
