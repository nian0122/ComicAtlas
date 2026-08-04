package com.comicatlas.api.comic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.admin.recovery.RecoveryEngine;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.dto.CatalogNode;
import com.comicatlas.api.comic.dto.ChapterRef;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.comic.service.CatalogService;
import com.comicatlas.api.comic.service.ChapterManagementService;
import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.event.ImportEventHandler;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.event.ManagementCommandResultHandler;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.reader.dto.ReaderDTO;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.api.reader.service.HistoryService;
import com.comicatlas.api.reader.service.ReaderService;
import com.comicatlas.api.upload.MediaManagementService;
import com.comicatlas.common.enums.TaskType;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阅读生命周期兼容集成测试（TDD）。
 *
 * <p>统一「导入、阅读、目录、历史、缓存与软删除」兼容语义：
 * <ul>
 *   <li>阅读 list/detail/catalog/reader 仅暴露 READY 且非 trash 实体；管理端（status 过滤）可看 DRAFT/IMPORT_FAILED/RECOVERY_REQUIRED</li>
 *   <li>回收保留 reading_history，恢复原 ID 后继续有效；永久清理才级联历史</li>
 *   <li>章节重排/媒体回收后 prev/next 与页码边界正确</li>
 *   <li>Import/Recovery 写入 READY 生命周期并使用真实 StorageRef；Catalog 缓存在 CRUD/回收/重排/媒体结果落库后失效</li>
 *   <li>旧 globalOrder 路径继续读取，不重写 DB path 为推测值</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("阅读生命周期兼容集成测试")
class ReadingLifecycleCompatibilityIT {

    private static boolean dockerAvailable;

    static {
        dockerAvailable = checkDockerAvailable();
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_atlas_lifecycle_test")
                .withUsername("test")
                .withPassword("test")
            : null;

    @Container
    static GenericContainer<?> redis = dockerAvailable
            ? new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)
            : null;

    /** 测试专用 MANGA_ROOT，导入/恢复的 metadata 与 HQ 文件都写在此目录 */
    private static final Path MANGA_ROOT = createTempMangaRoot();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("MANGA_ROOT", () -> MANGA_ROOT.toString());
        registry.add("comic.cache.catalog-ttl", () -> Duration.ofMinutes(30));
        if (dockerAvailable && mysql != null && mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        }
        if (dockerAvailable && redis != null && redis.isRunning()) {
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private ComicMapper comicMapper;
    @Autowired private CatalogMapper catalogMapper;
    @Autowired private ChapterMapper chapterMapper;
    @Autowired private MediaMapper mediaMapper;
    @Autowired private ReadingHistoryMapper readingHistoryMapper;
    @Autowired private ImportTaskMapper importTaskMapper;
    @Autowired private ManagementTaskMapper managementTaskMapper;
    @Autowired private ManagementTaskItemMapper managementTaskItemMapper;

    @Autowired private ComicService comicService;
    @Autowired private CatalogService catalogService;
    @Autowired private ReaderService readerService;
    @Autowired private HistoryService historyService;
    @Autowired private ChapterManagementService chapterManagementService;
    @Autowired private MediaManagementService mediaManagementService;

    @Autowired private ImportEventHandler importEventHandler;
    @Autowired private ManagementCommandResultHandler managementCommandResultHandler;
    @Autowired private RecoveryEngine recoveryEngine;
    @Autowired private CatalogCacheInvalidator catalogCacheInvalidator;
    @Autowired private CacheManager cacheManager;

    @AfterEach
    void tearDown() {
        if (comicMapper == null) return;
        if (managementTaskItemMapper != null) managementTaskItemMapper.delete(new LambdaQueryWrapper<>());
        if (managementTaskMapper != null) managementTaskMapper.delete(new LambdaQueryWrapper<>());
        if (importTaskMapper != null) importTaskMapper.delete(new LambdaQueryWrapper<>());
        if (mediaMapper != null) mediaMapper.delete(new LambdaQueryWrapper<>());
        if (chapterMapper != null) chapterMapper.delete(new LambdaQueryWrapper<>());
        if (catalogMapper != null) catalogMapper.delete(new LambdaQueryWrapper<>());
        if (readingHistoryMapper != null) readingHistoryMapper.delete(new LambdaQueryWrapper<>());
        if (comicMapper != null) comicMapper.delete(new LambdaQueryWrapper<>());
        // 清掉 catalog 缓存，避免跨测试残留
        try {
            if (cacheManager != null) {
                Cache cache = cacheManager.getCache("comicCatalog");
                if (cache != null) cache.clear();
            }
        } catch (RuntimeException ignored) {
            // Redis 缓存清理失败不影响后续用例
        }
    }

    private static boolean checkDockerAvailable() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isBlank()) {
            return true;
        }
        try {
            new ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
                .waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path createTempMangaRoot() {
        try {
            Path root = Files.createTempDirectory("manga-lifecycle-test");
            Files.createDirectories(root.resolve("metadata"));
            Files.createDirectories(root.resolve("hq"));
            Files.createDirectories(root.resolve("lq"));
            Files.createDirectories(root.resolve("thumbs"));
            Files.createDirectories(root.resolve("temp"));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escapedPath(String name) {
        return MANGA_ROOT.resolve(name).toString().replace("\\", "\\\\");
    }

    // ======================== 辅助方法 ========================

    private Long insertComic(String title, String status) {
        Comic c = new Comic();
        c.setTitle(title);
        c.setStatus(status);
        c.setStoragePolicy("MANAGED");
        c.setTotalPages(0);
        comicMapper.insert(c);
        return c.getId();
    }

    private Long insertChapter(Long comicId, int globalOrder, String chapterNo, String status) {
        Chapter ch = new Chapter();
        ch.setComicId(comicId);
        ch.setTitle("章节-" + chapterNo);
        ch.setChapterNo(chapterNo);
        ch.setGlobalOrder(globalOrder);
        ch.setSortOrder(globalOrder);
        ch.setStatus(status);
        ch.setPageCount(0);
        chapterMapper.insert(ch);
        return ch.getId();
    }

    private Long insertMedia(Long chapterId, int pageNumber, String hqPath, String status) {
        Media m = new Media();
        m.setChapterId(chapterId);
        m.setPageNumber(pageNumber);
        m.setHqRoot("HQ");
        m.setHqPath(hqPath);
        m.setHqStatus("READY");
        m.setLqStatus("NOT_GENERATED");
        m.setStatus(status);
        m.setMediaType("IMAGE");
        m.setFileSize(100L);
        mediaMapper.insert(m);
        return m.getId();
    }

    /** 创建一个 READY 漫画 + 1 个 READY 章节 + 2 张 READY 页 */
    private long[] createReadyComicWithChapterAndMedia() {
        Long comicId = insertComic("可读漫画-" + System.nanoTime(), "READY");
        Long chapterId = insertChapter(comicId, 1, "1", "READY");
        insertMedia(chapterId, 1, comicId + "/" + chapterId + "/001.jpg", "READY");
        insertMedia(chapterId, 2, comicId + "/" + chapterId + "/002.jpg", "READY");
        return new long[]{comicId, chapterId};
    }

    private void upsertHistory(Long comicId, Long chapterId, int pageNumber) {
        com.comicatlas.api.reader.dto.HistoryUpdateRequest req =
                new com.comicatlas.api.reader.dto.HistoryUpdateRequest();
        req.setChapterId(chapterId);
        req.setPageNumber(pageNumber);
        historyService.upsertHistory(comicId, req);
    }

    private ReaderDTO readChapter(Long chapterId) {
        return readerService.getChapter(chapterId);
    }

    private int apiCode(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url))
                .andReturn().getResponse().getContentAsString()).path("code").asInt();
    }

    // ======================== 1. 阅读可见性 ========================

    @Nested
    @DisplayName("阅读查询仅暴露 READY 且非 trash 实体")
    class ReadingVisibilityTests {

        @Test
        @DisplayName("DRAFT/IMPORTING/IMPORT_FAILED/RECOVERY_REQUIRED/DELETING/TRASHED/RESTORING/PURGING/DELETED 不可读；READY 可读")
        void nonReadyLifecycles_hiddenFromCatalogAndReader() throws Exception {
            List<String> nonReadable = List.of(
                "DRAFT", "IMPORTING", "IMPORT_FAILED", "RECOVERY_REQUIRED",
                "DELETING", "TRASHED", "RESTORING", "PURGING", "DELETED");
            for (String status : nonReadable) {
                Long comicId = insertComic("HIDE-" + status, status);
                Long chapterId = insertChapter(comicId, 1, "1", "READY");
                insertMedia(chapterId, 1, comicId + "/1/001.jpg", "READY");

                // 删除期 reader / catalog 必须 404
                assertThat(apiCode("/api/chapters/" + chapterId))
                        .as("reader 对 %s 漫画应 404", status).isEqualTo(404);
                assertThat(apiCode("/api/comics/" + comicId + "/catalog"))
                        .as("catalog 对 %s 漫画应 404", status).isEqualTo(404);
            }

            // READY 漫画可读
            long[] ids = createReadyComicWithChapterAndMedia();
            assertThat(apiCode("/api/comics/" + ids[0] + "/catalog")).isEqualTo(200);
            assertThat(apiCode("/api/chapters/" + ids[1])).isEqualTo(200);
        }

        @Test
        @DisplayName("阅读列表仅返回 READY；TRASHED/DELETED 等不泄漏")
        void readingList_onlyReady() throws Exception {
            for (String status : List.of("DRAFT", "IMPORT_FAILED", "TRASHED", "DELETED", "RECOVERY_REQUIRED")) {
                insertComic("LIST-LC-" + status, status);
            }
            insertComic("LIST-LC-READY", "READY");

            mockMvc.perform(get("/api/comics")
                            .param("keyword", "LIST-LC")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records.length()").value(1))
                    .andExpect(jsonPath("$.data.records[0].title").value("LIST-LC-READY"))
                    .andExpect(jsonPath("$.data.records[0].lifecycle").value("READY"));
        }

        @Test
        @DisplayName("管理端 status 过滤可看到 DRAFT/IMPORT_FAILED/RECOVERY_REQUIRED（可管理不可阅读）")
        void managementStatusFilter_exposesNonReady() throws Exception {
            insertComic("MGMT-DRAFT", "DRAFT");
            insertComic("MGMT-IMPORT_FAILED", "IMPORT_FAILED");
            insertComic("MGMT-RECOVERY_REQUIRED", "RECOVERY_REQUIRED");

            for (String status : List.of("DRAFT", "IMPORT_FAILED", "RECOVERY_REQUIRED")) {
                mockMvc.perform(get("/api/comics")
                                .param("keyword", "MGMT-" + status)
                                .param("status", status)
                                .param("size", "20"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.data.records.length()").value(1))
                        .andExpect(jsonPath("$.data.records[0].lifecycle").value(status));
            }

            // 管理态漫画详情仍可返回（lifecycle 等管理元数据），仅阅读入口被拦
            Comic draft = comicMapper.selectOne(new LambdaQueryWrapper<Comic>().eq(Comic::getTitle, "MGMT-DRAFT"));
            mockMvc.perform(get("/api/comics/{id}", draft.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.lifecycle").value("DRAFT"));
        }

        @Test
        @DisplayName("TRASHED 章节从 catalog/reader 隐藏；READY 章节不受影响")
        void trashedChapter_hiddenFromCatalogAndReader() throws Exception {
            Long comicId = insertComic("TRASH-CH-COMIC", "READY");
            Long chReady = insertChapter(comicId, 1, "1", "READY");
            Long chTrashed = insertChapter(comicId, 2, "2", "TRASHED");
            insertMedia(chReady, 1, comicId + "/1/001.jpg", "READY");
            insertMedia(chTrashed, 1, comicId + "/2/001.jpg", "READY");

            // TRASHED 章节 reader 404
            assertThat(apiCode("/api/chapters/" + chTrashed)).isEqualTo(404);
            // READY 章节 reader 200
            assertThat(apiCode("/api/chapters/" + chReady)).isEqualTo(200);

            // catalog 只含 READY 章节
            List<CatalogNode> tree = catalogService.buildTree(comicId);
            List<ChapterRef> refs = tree.stream()
                    .flatMap(n -> n.getChapters().stream())
                    .toList();
            assertThat(refs).extracting(ChapterRef::getId).doesNotContain(chTrashed);
            assertThat(refs).extracting(ChapterRef::getId).contains(chReady);

            // 重排后 prev/next 跳过 TRASHED 章节
            Long ch3 = insertChapter(comicId, 3, "3", "READY");
            ReaderDTO dto = readerService.getChapter(ch3);
            assertThat(dto.getPrevChapterId()).isEqualTo(chReady);
        }
    }

    // ======================== 2. 回收 / 恢复 / 永久清理 ========================

    @Nested
    @DisplayName("回收保留阅读历史，恢复续读，永久清理级联历史")
    class TrashRestoreHistoryTests {

        @Test
        @DisplayName("回收→TRASHED 后 reader/catalog 404，历史保留；恢复原 ID 后原位置续读")
        void recycle_restore_keepsReadingHistory_resumeValid() throws Exception {
            long[] ids = createReadyComicWithChapterAndMedia();
            Long comicId = ids[0];
            Long chapterId = ids[1];
            upsertHistory(comicId, chapterId, 2);

            // 预热 catalog 缓存
            catalogService.buildTree(comicId);
            assertThat(catalogCache().get(comicId)).isNotNull();

            // —— 回收：DELETE /api/comics/{id} → DELETING → Worker 完成 → TRASHED
            ManagementTaskResponse task = comicService.deleteComic(comicId, null);
            ManagementTaskItem item = managementTaskItemMapper.selectOne(
                    new LambdaQueryWrapper<ManagementTaskItem>()
                            .eq(ManagementTaskItem::getTaskId, task.getId()));
            assertThat(item).isNotNull();

            managementCommandResultHandler.handleResult(
                    new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                            task.getId(), item.getId(), 1, "COMIC_DELETE", "COMIC", comicId),
                    null, 0L);

            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo("TRASHED");
            // 阅读入口 404
            assertThat(apiCode("/api/comics/" + comicId + "/catalog")).isEqualTo(404);
            assertThat(apiCode("/api/chapters/" + chapterId)).isEqualTo(404);
            // catalog 缓存已失效（不命中陈旧 READY 树）
            assertThat(catalogCache().get(comicId)).isNull();
            // 历史保留
            assertThat(readingHistoryMapper.selectCount(
                    new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comicId))).isEqualTo(1);

            // —— 恢复：原 ID 回到 READY，续读原历史
            Comic restored = comicMapper.selectById(comicId);
            restored.setStatus("READY");
            restored.setDeletedAt(null);
            comicMapper.updateById(restored);
            catalogCacheInvalidator.evict(comicId);

            assertThat(apiCode("/api/comics/" + comicId + "/catalog")).isEqualTo(200);
            ReaderDTO dto = readChapter(chapterId);
            assertThat(dto.getChapterId()).isEqualTo(chapterId);
            assertThat(dto.getPages()).hasSize(2);

            var history = historyService.getHistory(comicId);
            assertThat(history).isNotNull();
            assertThat(history.getChapterId()).isEqualTo(chapterId);
            assertThat(history.getPageNumber()).isEqualTo(2);
            assertThat(history.getComicId()).isEqualTo(comicId);
        }

        @Test
        @DisplayName("永久清理删除 comic 行后阅读历史级联消失")
        void purge_cascadesReadingHistory() throws Exception {
            long[] ids = createReadyComicWithChapterAndMedia();
            Long comicId = ids[0];
            Long chapterId = ids[1];
            upsertHistory(comicId, chapterId, 1);
            assertThat(readingHistoryMapper.selectCount(
                    new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comicId))).isEqualTo(1);

            // 模拟永久清理：先删叶子避免 MySQL 双级联，再删 comic 行 → history CASCADE
            mediaMapper.delete(new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId));
            chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
            catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));
            comicMapper.deleteById(comicId);

            assertThat(comicMapper.selectById(comicId)).isNull();
            assertThat(readingHistoryMapper.selectCount(
                    new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comicId))).isZero();
        }
    }

    // ======================== 3. 排序 / 媒体删除后的页码边界 ========================

    @Nested
    @DisplayName("排序与媒体删除后页码边界正确")
    class ReorderAndPageBoundaryTests {

        @Test
        @DisplayName("章节重排后 reader prev/next 跟随新 global_order，catalog 缓存失效")
        void chapterReorder_fixesPrevNext_evictsCatalogCache() throws Exception {
            Long comicId = insertComic("REORDER-COMIC", "READY");
            Long ch1 = insertChapter(comicId, 1, "1", "READY");
            Long ch2 = insertChapter(comicId, 2, "2", "READY");
            Long ch3 = insertChapter(comicId, 3, "3", "READY");
            insertMedia(ch1, 1, comicId + "/1/001.jpg", "READY");
            insertMedia(ch2, 1, comicId + "/2/001.jpg", "READY");
            insertMedia(ch3, 1, comicId + "/3/001.jpg", "READY");

            // 预热缓存
            catalogService.buildTree(comicId);
            assertThat(catalogCache().get(comicId)).isNotNull();

            // ch3 移到第 1 位
            chapterManagementService.reorderChapter(comicId, ch3, 1);

            // 缓存已失效
            assertThat(catalogCache().get(comicId)).isNull();
            // catalog 重建反映新顺序
            List<ChapterRef> refs = catalogService.buildTree(comicId).get(0).getChapters();
            assertThat(refs).extracting(ChapterRef::getId).containsExactly(ch3, ch1, ch2);
            // reader prev/next 跟随新 global_order
            ReaderDTO dto = readerService.getChapter(ch1);
            assertThat(dto.getPrevChapterId()).isEqualTo(ch3);
            assertThat(dto.getNextChapterId()).isEqualTo(ch2);
            ReaderDTO first = readerService.getChapter(ch3);
            assertThat(first.getPrevChapterId()).isNull();
            assertThat(first.getNextChapterId()).isEqualTo(ch1);
        }

        @Test
        @DisplayName("媒体回收完成：页码边界修正（chapter.page_count/comic.total_pages 只计 READY），reader 隐藏 TRASHED，缓存失效")
        void mediaTrash_fixesPageBoundary_evictsCatalogCache() throws Exception {
            Long comicId = insertComic("MEDIA-TRASH-COMIC", "READY");
            Long chapterId = insertChapter(comicId, 1, "1", "READY");
            Long m1 = insertMedia(chapterId, 1, comicId + "/1/001.jpg", "READY");
            Long m2 = insertMedia(chapterId, 2, comicId + "/1/002.jpg", "READY");
            Long m3 = insertMedia(chapterId, 3, comicId + "/1/003.jpg", "READY");

            catalogService.buildTree(comicId);
            assertThat(catalogCache().get(comicId)).isNotNull();

            // 真实媒体回收管线：MEDIA_TRASH 命令 + 完成结果
            OperationSubmitResultSafe result = trashMediaViaPipeline(m2);
            assertThat(result.itemCount()).isGreaterThan(0);

            ManagementTaskItem item = managementTaskItemMapper.selectOne(
                    new LambdaQueryWrapper<ManagementTaskItem>()
                            .eq(ManagementTaskItem::getTaskId, result.taskId()));
            managementCommandResultHandler.handleResult(
                    new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                            result.taskId(), item.getId(), 1, "MEDIA_TRASH", "MEDIA", m2),
                    null, 0L);

            // 媒体进入 TRASHED，HQ 引用指向 TRASH（不再暴露）
            Media trashed = mediaMapper.selectById(m2);
            assertThat(trashed.getStatus()).isEqualTo("TRASHED");
            assertThat(trashed.getHqStatus()).isEqualTo("DELETED");
            // 页码边界修正：只计 READY 页
            assertThat(chapterMapper.selectById(chapterId).getPageCount()).isEqualTo(2);
            assertThat(comicMapper.selectById(comicId).getTotalPages()).isEqualTo(2);
            // reader 隐藏被回收页
            ReaderDTO dto = readChapter(chapterId);
            assertThat(dto.getPages()).hasSize(2);
            assertThat(dto.getPages()).extracting(com.comicatlas.api.reader.dto.ReaderDTO.MediaItemDTO::getId)
                    .doesNotContain(m2);
            // 缓存失效 + 重建后 pageCount 正确
            assertThat(catalogCache().get(comicId)).isNull();
            List<ChapterRef> refs = catalogService.buildTree(comicId).get(0).getChapters();
            assertThat(refs.get(0).getPageCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("章节内媒体重排：页码连续 1..N，reader 顺序一致")
        void mediaReorder_renumbersPages() {
            Long comicId = insertComic("MEDIA-REORDER-COMIC", "READY");
            Long chapterId = insertChapter(comicId, 1, "1", "READY");
            Long m1 = insertMedia(chapterId, 1, comicId + "/1/001.jpg", "READY");
            Long m2 = insertMedia(chapterId, 2, comicId + "/1/002.jpg", "READY");
            Long m3 = insertMedia(chapterId, 3, comicId + "/1/003.jpg", "READY");

            com.comicatlas.api.upload.dto.MediaReorderRequest req =
                    new com.comicatlas.api.upload.dto.MediaReorderRequest();
            req.setMediaIds(List.of(m3, m1, m2));
            mediaManagementService.reorder(chapterId, req);

            ReaderDTO dto = readChapter(chapterId);
            assertThat(dto.getPages()).hasSize(3);
            assertThat(dto.getPages()).extracting(com.comicatlas.api.reader.dto.ReaderDTO.MediaItemDTO::getPageNumber)
                    .containsExactly(1, 2, 3);
            assertThat(dto.getPages()).extracting(com.comicatlas.api.reader.dto.ReaderDTO.MediaItemDTO::getId)
                    .containsExactly(m3, m1, m2);
        }
    }

    // ======================== 4. Import / Recovery 生命周期 ========================

    @Nested
    @DisplayName("Import/Recovery 写入 READY 生命周期并使用真实 StorageRef")
    class ImportRecoveryLifecycleTests {

        @Test
        @DisplayName("导入完成：comic → READY，页面落真实 StorageRef（旧目录迁移到 chapterId），reader URL 命中真实文件")
        void importCompleted_writesReady_withRealStorageRef() throws Exception {
            long[] ids = createImportTask("import-lc");
            long taskId = ids[0];
            long comicId = ids[1];

            // 旧布局文件：hq/{comicId}/0/001.jpg，metadata 带 hqPath 触发目录迁移
            Path legacyDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId)).resolve("0");
            Files.createDirectories(legacyDir);
            Files.writeString(legacyDir.resolve("001.jpg"), "fake-jpeg");

            writeMetadataV3(taskId, comicId, "导入兼容漫画", "导入作者");

            importEventHandler.handleComicImported(
                    new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, null),
                    null, 0L);

            Comic comic = comicMapper.selectById(comicId);
            assertThat(comic.getStatus()).isEqualTo("READY");
            assertThat(comic.getTitle()).isEqualTo("导入兼容漫画");

            List<Chapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
            assertThat(chapters).hasSize(1);
            Long chapterId = chapters.get(0).getId();

            List<Media> pages = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId));
            assertThat(pages).hasSize(1);
            Media page = pages.get(0);
            // 旧 globalOrder 目录已迁移为 chapterId 目录（真实 StorageRef）
            assertThat(page.getHqPath()).isEqualTo(comicId + "/" + chapterId + "/001.jpg");
            assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(page.getHqPath()))).isTrue();

            // reader URL 命中真实文件
            ReaderDTO dto = readChapter(chapterId);
            assertThat(dto.getComicId()).isEqualTo(comicId);
            assertThat(dto.getPages()).hasSize(1);
            assertThat(dto.getPages().get(0).getHqUrl())
                    .isEqualTo("/files/hq/" + comicId + "/" + chapterId + "/001.jpg");
        }

        @Test
        @DisplayName("恢复（RecoveryEngine）：placeholder/缺失 → READY，页面用旧 globalOrder StorageRef 重建")
        void recovery_rebuildsReady_withRealStorageRef() throws Exception {
            long comicId = 8800001L;
            Path hqDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId)).resolve("3");
            Files.createDirectories(hqDir);
            Files.writeString(hqDir.resolve("001.jpg"), "fake-jpeg-1");
            Files.writeString(hqDir.resolve("002.jpg"), "fake-jpeg-2");
            writeRecoveryMetadata(comicId);

            var progress = recoveryEngine.processComicDir(comicId, 0);
            assertThat(progress.recoveredComics()).isEqualTo(1);

            Comic comic = comicMapper.selectById(comicId);
            assertThat(comic.getStatus()).isEqualTo("READY");
            assertThat(comic.getTitle()).isEqualTo("恢复兼容漫画");

            List<Chapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
            assertThat(chapters).hasSize(1);
            Chapter chapter = chapters.get(0);
            assertThat(chapter.getPageCount()).isEqualTo(2);

            List<Media> pages = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapter.getId()));
            assertThat(pages).hasSize(2);
            // 恢复保留旧 globalOrder 布局的真实 StorageRef
            assertThat(pages).extracting(Media::getHqPath)
                    .containsExactlyInAnyOrder(comicId + "/3/001.jpg", comicId + "/3/002.jpg");

            ReaderDTO dto = readChapter(chapter.getId());
            assertThat(dto.getPages()).hasSize(2);
            assertThat(dto.getPages()).extracting(com.comicatlas.api.reader.dto.ReaderDTO.MediaItemDTO::getHqUrl)
                    .contains("/files/hq/" + comicId + "/3/001.jpg", "/files/hq/" + comicId + "/3/002.jpg");
        }
    }

    // ======================== 5. 旧 globalOrder 路径 ========================

    @Nested
    @DisplayName("旧 globalOrder 路径继续读取，不重写 DB path")
    class LegacyGlobalOrderPathTests {

        @Test
        @DisplayName("DB 中旧布局 hq_path 原样解析，不重写为推测值")
        void legacyGlobalOrderPath_readsWithoutRewrite() throws Exception {
            Long comicId = insertComic("LEGACY-COMIC", "READY");
            // 旧布局：目录按 globalOrder 而非 chapterId
            int globalOrder = 7;
            Long chapterId = insertChapter(comicId, globalOrder, "1", "READY");
            String legacyPath = comicId + "/" + globalOrder + "/001.jpg";
            Long mediaId = insertMedia(chapterId, 1, legacyPath, "READY");
            Path file = MANGA_ROOT.resolve("hq").resolve(legacyPath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "fake-jpeg");

            ReaderDTO dto = readChapter(chapterId);
            assertThat(dto.getPages()).hasSize(1);
            assertThat(dto.getPages().get(0).getHqUrl())
                    .isEqualTo("/files/hq/" + comicId + "/" + globalOrder + "/001.jpg");
            // DB path 不被重写
            assertThat(mediaMapper.selectById(mediaId).getHqPath()).isEqualTo(legacyPath);
        }
    }

    // ======================== 内部辅助 ========================

    private Cache catalogCache() {
        return cacheManager.getCache(CatalogCacheInvalidator.CACHE_NAME);
    }

    private long[] createImportTask(String sourceName) throws Exception {
        var result = mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"sourceType": "DIRECTORY", "sourcePath": "%s"}
                            """.formatted(escapedPath(sourceName))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new long[]{data.path("id").asLong(), data.path("comicId").asLong()};
    }

    private void writeMetadataV3(long taskId, long comicId, String title, String author) throws IOException {
        String metadata = """
            {
              "version": 3,
              "comic": {
                "title": "%s",
                "titleJpn": null,
                "author": "%s",
                "category": null,
                "sourceGalleryId": null
              },
              "catalogs": [],
              "chapters": [
                {
                  "title": "第1话",
                  "chapterNo": "1",
                  "sortOrder": 0,
                  "globalOrder": 0,
                  "catalogIndex": null,
                  "mediaItems": [
                    {"pageNumber": 1, "fileName": "001.jpg", "hqPath": "%d/0/001.jpg", "fileSize": 1024, "width": 100, "height": 150, "mediaType": "IMAGE", "hqStatus": "READY"}
                  ]
                }
              ]
            }
            """.formatted(title, author, comicId);
        Files.writeString(MANGA_ROOT.resolve("metadata").resolve(taskId + ".json"), metadata);
    }

    private void writeRecoveryMetadata(long comicId) throws IOException {
        String metadata = """
            {
              "comic": {"title": "恢复兼容漫画", "author": "恢复作者", "category": null},
              "catalogs": [],
              "chapters": [
                {"title": "第1话", "chapterNo": "1", "sortOrder": 0, "globalOrder": 3, "catalogIndex": null}
              ]
            }
            """;
        Files.writeString(MANGA_ROOT.resolve("metadata").resolve(comicId + ".json"), metadata);
    }

    /** 通过真实媒体回收管线提交 MEDIA_TRASH，返回任务信息 */
    private OperationSubmitResultSafe trashMediaViaPipeline(Long mediaId) {
        com.comicatlas.api.management.dto.OperationSubmitResult result = mediaManagementService.trash(mediaId);
        assertThat(result.isNoOp()).isFalse();
        return new OperationSubmitResultSafe(result.getTaskId(), result.getItemCount());
    }

    private record OperationSubmitResultSafe(Long taskId, Integer itemCount) {
    }
}

