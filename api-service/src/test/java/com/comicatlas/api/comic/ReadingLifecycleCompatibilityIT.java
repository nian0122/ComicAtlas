package com.comicatlas.api.comic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.common.scan.RecoveryEngine;
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
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.comic.service.CatalogService;
import com.comicatlas.api.comic.service.ChapterManagementService;
import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.event.ImportEventHandler;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportPersistenceService;
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
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.common.event.ImportMetadataRefreshCompletedEvent;
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
    @Autowired private ImportPersistenceService importPersistenceService;
    @Autowired private ManagementCommandResultHandler managementCommandResultHandler;
    @Autowired private RecoveryEngine recoveryEngine;
    @Autowired private CatalogCacheInvalidator catalogCacheInvalidator;
    @Autowired private CacheManager cacheManager;

    @AfterEach
    void tearDown() {
        if (comicMapper == null) { return; }
        if (managementTaskItemMapper != null) { managementTaskItemMapper.delete(new LambdaQueryWrapper<>()); }
        if (managementTaskMapper != null) { managementTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (importTaskMapper != null) { importTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (mediaMapper != null) { mediaMapper.delete(new LambdaQueryWrapper<>()); }
        if (chapterMapper != null) { chapterMapper.delete(new LambdaQueryWrapper<>()); }
        if (catalogMapper != null) { catalogMapper.delete(new LambdaQueryWrapper<>()); }
        if (readingHistoryMapper != null) { readingHistoryMapper.delete(new LambdaQueryWrapper<>()); }
        if (comicMapper != null) { comicMapper.delete(new LambdaQueryWrapper<>()); }
        // 清掉 catalog 缓存，避免跨测试残留
        try {
            if (cacheManager != null) {
                Cache cache = cacheManager.getCache("comicCatalog");
                if (cache != null) { cache.clear(); }
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

    private Long insertComic(String title, ComicStatus status) {
        Comic c = new Comic();
        c.setTitle(title);
        c.setStatus(status);
        c.setStoragePolicy("MANAGED");
        c.setTotalPages(0);
        comicMapper.insert(c);
        return c.getId();
    }

    private Long insertChapter(Long comicId, int globalOrder, String chapterNo, ChapterLifecycleStatus status) {
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

    private Long insertMedia(Long chapterId, int pageNumber, String hqPath, MediaLifecycleStatus status) {
        Media m = new Media();
        m.setChapterId(chapterId);
        m.setPageNumber(pageNumber);
        m.setHqRoot("HQ");
        m.setHqPath(hqPath);
        m.setHqStatus(HqStatus.READY);
        m.setLqStatus(LqStatus.NOT_GENERATED);
        m.setStatus(status);
        m.setMediaType("IMAGE");
        m.setFileSize(100L);
        mediaMapper.insert(m);
        return m.getId();
    }

    /** 创建一个 READY 漫画 + 1 个 READY 章节 + 2 张 READY 页 */
    private long[] createReadyComicWithChapterAndMedia() {
        Long comicId = insertComic("可读漫画-" + System.nanoTime(), ComicStatus.READY);
        Long chapterId = insertChapter(comicId, 1, "1", ChapterLifecycleStatus.READY);
        insertMedia(chapterId, 1, comicId + "/" + chapterId + "/001.jpg", MediaLifecycleStatus.READY);
        insertMedia(chapterId, 2, comicId + "/" + chapterId + "/002.jpg", MediaLifecycleStatus.READY);
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
            List<ComicStatus> nonReadable = List.of(
                ComicStatus.DRAFT, ComicStatus.IMPORTING, ComicStatus.IMPORT_FAILED,
                ComicStatus.RECOVERY_REQUIRED, ComicStatus.DELETING, ComicStatus.TRASHED,
                ComicStatus.RESTORING, ComicStatus.PURGING, ComicStatus.DELETED);
            for (ComicStatus status : nonReadable) {
                Long comicId = insertComic("HIDE-" + status, status);
                Long chapterId = insertChapter(comicId, 1, "1", ChapterLifecycleStatus.READY);
                insertMedia(chapterId, 1, comicId + "/1/001.jpg", MediaLifecycleStatus.READY);

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
            for (ComicStatus status : List.of(ComicStatus.DRAFT, ComicStatus.IMPORT_FAILED, ComicStatus.TRASHED, ComicStatus.DELETED, ComicStatus.RECOVERY_REQUIRED)) {
                insertComic("LIST-LC-" + status, status);
            }
            insertComic("LIST-LC-READY", ComicStatus.READY);

            mockMvc.perform(get("/api/comics")
                            .param("keyword", "LIST-LC")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records.length()").value(1))
                    .andExpect(jsonPath("$.data.records[0].title").value("LIST-LC-READY"))
                    .andExpect(jsonPath("$.data.records[0].status").value("READY"));
        }

        @Test
        @DisplayName("管理端 status 过滤可看到 DRAFT/IMPORT_FAILED/RECOVERY_REQUIRED（可管理不可阅读）")
        void managementStatusFilter_exposesNonReady() throws Exception {
            insertComic("MGMT-DRAFT", ComicStatus.DRAFT);
            insertComic("MGMT-IMPORT_FAILED", ComicStatus.IMPORT_FAILED);
            insertComic("MGMT-RECOVERY_REQUIRED", ComicStatus.RECOVERY_REQUIRED);

            for (String status : List.of("DRAFT", "IMPORT_FAILED", "RECOVERY_REQUIRED")) {
                mockMvc.perform(get("/api/comics")
                                .param("keyword", "MGMT-" + status)
                                .param("status", status)
                                .param("size", "20"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.data.records.length()").value(1))
                        .andExpect(jsonPath("$.data.records[0].status").value(status));
            }

            // 管理态漫画详情仍可返回（status 等管理元数据），仅阅读入口被拦
            Comic draft = comicMapper.selectOne(new LambdaQueryWrapper<Comic>().eq(Comic::getTitle, "MGMT-DRAFT"));
            mockMvc.perform(get("/api/comics/{id}", draft.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @Test
        @DisplayName("TRASHED 章节从 catalog/reader 隐藏；READY 章节不受影响")
        void trashedChapter_hiddenFromCatalogAndReader() throws Exception {
            Long comicId = insertComic("TRASH-CH-COMIC", ComicStatus.READY);
            Long chReady = insertChapter(comicId, 1, "1", ChapterLifecycleStatus.READY);
            Long chTrashed = insertChapter(comicId, 2, "2", ChapterLifecycleStatus.TRASHED);
            insertMedia(chReady, 1, comicId + "/1/001.jpg", MediaLifecycleStatus.READY);
            insertMedia(chTrashed, 1, comicId + "/2/001.jpg", MediaLifecycleStatus.READY);

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
            Long ch3 = insertChapter(comicId, 3, "3", ChapterLifecycleStatus.READY);
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
                            task.getId(), item.getId(), 1, "COMIC_DELETE", "COMIC", comicId, null),
                    null, 0L);

            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.TRASHED);
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
            restored.setStatus(ComicStatus.READY);
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
            Long comicId = insertComic("REORDER-COMIC", ComicStatus.READY);
            Long ch1 = insertChapter(comicId, 1, "1", ChapterLifecycleStatus.READY);
            Long ch2 = insertChapter(comicId, 2, "2", ChapterLifecycleStatus.READY);
            Long ch3 = insertChapter(comicId, 3, "3", ChapterLifecycleStatus.READY);
            insertMedia(ch1, 1, comicId + "/1/001.jpg", MediaLifecycleStatus.READY);
            insertMedia(ch2, 1, comicId + "/2/001.jpg", MediaLifecycleStatus.READY);
            insertMedia(ch3, 1, comicId + "/3/001.jpg", MediaLifecycleStatus.READY);

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
            Long comicId = insertComic("MEDIA-TRASH-COMIC", ComicStatus.READY);
            Long chapterId = insertChapter(comicId, 1, "1", ChapterLifecycleStatus.READY);
            Long m1 = insertMedia(chapterId, 1, comicId + "/1/001.jpg", MediaLifecycleStatus.READY);
            Long m2 = insertMedia(chapterId, 2, comicId + "/1/002.jpg", MediaLifecycleStatus.READY);
            Long m3 = insertMedia(chapterId, 3, comicId + "/1/003.jpg", MediaLifecycleStatus.READY);

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
                            result.taskId(), item.getId(), 1, "MEDIA_TRASH", "MEDIA", m2, null),
                    null, 0L);

            // 媒体进入 TRASHED，HQ 引用指向 TRASH（不再暴露）
            Media trashed = mediaMapper.selectById(m2);
            assertThat(trashed.getStatus()).isEqualTo(MediaLifecycleStatus.TRASHED);
            assertThat(trashed.getHqStatus()).isEqualTo(HqStatus.DELETED);
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
            Long comicId = insertComic("MEDIA-REORDER-COMIC", ComicStatus.READY);
            Long chapterId = insertChapter(comicId, 1, "1", ChapterLifecycleStatus.READY);
            Long m1 = insertMedia(chapterId, 1, comicId + "/1/001.jpg", MediaLifecycleStatus.READY);
            Long m2 = insertMedia(chapterId, 2, comicId + "/1/002.jpg", MediaLifecycleStatus.READY);
            Long m3 = insertMedia(chapterId, 3, comicId + "/1/003.jpg", MediaLifecycleStatus.READY);

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
        @DisplayName("两阶段导入：completed 后仍不可读（comic IMPORTING/media PENDING），finalized 后可读")
        void importCompleted_writesReady_withRealStorageRef() throws Exception {
            long[] ids = createImportTask("import-lc");
            long taskId = ids[0];
            long comicId = ids[1];

            // staging 布局文件：hq/{comicId}/0/001.jpg（globalOrder=0），metadata 带 hqPath
            Path stagingDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId)).resolve("0");
            Files.createDirectories(stagingDir);
            Files.writeString(stagingDir.resolve("001.jpg"), "fake-jpeg");

            writeMetadataV3(taskId, comicId, "导入兼容漫画", "导入作者");

            // Phase 1：completed → 结构插入，comic 保持 IMPORTING、media PENDING（不得 READY）
            importEventHandler.handleComicImported(
                    new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, null),
                    null, 0L);

            Comic comic = comicMapper.selectById(comicId);
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.IMPORTING);
            assertThat(comic.getTitle()).isEqualTo("导入兼容漫画");

            List<Chapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
            assertThat(chapters).hasSize(1);
            Long chapterId = chapters.get(0).getId();

            List<Media> pages = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId));
            assertThat(pages).hasSize(1);
            Media page = pages.get(0);
            // 目标布局路径（chapterId 目录）但状态 PENDING —— 文件尚未最终化
            assertThat(page.getHqPath()).isEqualTo(comicId + "/" + chapterId + "/001.jpg");
            assertThat(page.getHqStatus()).isEqualTo(HqStatus.PENDING);
            // completed 后仍不可读
            assertThat(apiCode("/api/comics/" + comicId + "/catalog")).isEqualTo(404);
            assertThat(apiCode("/api/chapters/" + chapterId)).isEqualTo(404);

            // 模拟 Worker 最终化：把 staging 文件搬到目标目录
            Path targetDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId)).resolve(String.valueOf(chapterId));
            Files.createDirectories(targetDir);
            Files.move(stagingDir.resolve("001.jpg"), targetDir.resolve("001.jpg"));

            // Phase 2：finalize completed → media/chapter READY、task FINALIZING、comic 仍 IMPORTING
            importPersistenceService.applyFinalizeCompleted(new ImportStorageFinalizeCompletedEvent(
                    UUID.randomUUID(), Instant.now(), taskId, comicId, 0, chapterId,
                    "hq/" + comicId + "/" + chapterId, 1));

            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.IMPORTING);
            assertThat(mediaMapper.selectById(page.getId()).getHqStatus()).isEqualTo(HqStatus.READY);
            assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo(ImportTaskStatus.FINALIZING);

            // Phase 3：磁盘 metadata.json 重建成功（结果事件）→ comic READY、task SUCCESS
            importPersistenceService.applyMetadataRefreshCompleted(new ImportMetadataRefreshCompletedEvent(
                    UUID.randomUUID(), Instant.now(), taskId, comicId));

            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.READY);
            assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo(ImportTaskStatus.SUCCESS);

            // reader URL 命中真实文件
            ReaderDTO dto = readChapter(chapterId);
            assertThat(dto.getComicId()).isEqualTo(comicId);
            assertThat(dto.getPages()).hasSize(1);
            assertThat(dto.getPages().get(0).getHqUrl())
                    .isEqualTo("/files/hq/" + comicId + "/" + chapterId + "/001.jpg");
        }

        @Test
        @DisplayName("finalize failed：comic/task 明确失败可重试、reader 404、DB 无 READY 推测 hqPath")
        void importFinalizeFailed_comicNotReady_reader404_retryable() throws Exception {
            long[] ids = createImportTask("import-fail");
            long taskId = ids[0];
            long comicId = ids[1];

            Path stagingDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId)).resolve("0");
            Files.createDirectories(stagingDir);
            Files.writeString(stagingDir.resolve("001.jpg"), "fake-jpeg");
            writeMetadataV3(taskId, comicId, "失败漫画", "失败作者");

            importEventHandler.handleComicImported(
                    new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, null),
                    null, 0L);

            Long chapterId = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId)).get(0).getId();

            // Worker 最终化失败
            importPersistenceService.applyFinalizeFailed(new ImportStorageFinalizeFailedEvent(
                    UUID.randomUUID(), Instant.now(), taskId, comicId, 0, chapterId,
                    "STORAGE_FINALIZE_SOURCE_MISSING", "源目录不存在"));

            // 明确失败且可重试：comic → IMPORT_FAILED、task → FAILED（均非 READY/SUCCESS）
            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.IMPORT_FAILED);
            assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo(ImportTaskStatus.FAILED);
            assertThat(importTaskMapper.selectById(taskId).getErrorMessage()).contains("STORAGE_FINALIZE_SOURCE_MISSING");
            // reader 404（不得置 READY 可读）
            assertThat(apiCode("/api/chapters/" + chapterId)).isEqualTo(404);
            // DB 不含 READY 状态的推测 hqPath（media 保持 PENDING，未确认文件存在）
            List<Media> pages = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId));
            assertThat(pages).isNotEmpty();
            assertThat(pages).allMatch(m -> m.getHqStatus() != HqStatus.READY);
        }

        @Test
        @DisplayName("finalize completed 重复事件：幂等，不重复计数、不重复置 SUCCESS")
        void importFinalizeCompleted_duplicate_isIdempotent() throws Exception {
            long[] ids = createImportTask("import-dup");
            long taskId = ids[0];
            long comicId = ids[1];

            Path stagingDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId)).resolve("0");
            Files.createDirectories(stagingDir);
            Files.writeString(stagingDir.resolve("001.jpg"), "fake-jpeg");
            writeMetadataV3(taskId, comicId, "重复漫画", "重复作者");

            importEventHandler.handleComicImported(
                    new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, null),
                    null, 0L);
            Long chapterId = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId)).get(0).getId();

            Path targetDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId)).resolve(String.valueOf(chapterId));
            Files.createDirectories(targetDir);
            Files.move(stagingDir.resolve("001.jpg"), targetDir.resolve("001.jpg"));

            var completed = new ImportStorageFinalizeCompletedEvent(
                    UUID.randomUUID(), Instant.now(), taskId, comicId, 0, chapterId,
                    "hq/" + comicId + "/" + chapterId, 1);

            importPersistenceService.applyFinalizeCompleted(completed);
            importPersistenceService.applyFinalizeCompleted(completed);

            // 幂等：media 仍只有 1 张且 READY，task 仍 FINALIZING（重复 completed 不重复触发），
            // 元数据重建结果事件后才是 SUCCESS，无重复计数
            assertThat(mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                    .eq(Media::getChapterId, chapterId))).isEqualTo(1);
            assertThat(mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                    .eq(Media::getChapterId, chapterId)))
                    .allMatch(m -> m.getHqStatus() == HqStatus.READY);
            assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo(ImportTaskStatus.FINALIZING);
            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.IMPORTING);

            importPersistenceService.applyMetadataRefreshCompleted(new ImportMetadataRefreshCompletedEvent(
                    UUID.randomUUID(), Instant.now(), taskId, comicId));
            assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo(ImportTaskStatus.SUCCESS);
            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.READY);
            assertThat(comicMapper.selectById(comicId).getTotalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("chapterId==globalOrder（staging 即最终位置）：completed + finalize 全链可读")
        void importFinalizeSameDir_chapterIdEqualsGlobalOrder_fullChain() throws Exception {
            long[] ids = createImportTask("import-samedir");
            long taskId = ids[0];
            long comicId = ids[1];

            writeMetadataV3(taskId, comicId, "同目录漫画", "同目录作者");

            importEventHandler.handleComicImported(
                    new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, null),
                    null, 0L);

            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.IMPORTING);
            Long chapterId = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId)).get(0).getId();
            Media page = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId)).get(0);
            assertThat(page.getHqStatus()).isEqualTo(HqStatus.PENDING);
            assertThat(page.getHqPath()).isEqualTo(comicId + "/" + chapterId + "/001.jpg");

            // chapterId==globalOrder：文件从导入阶段就位于最终位置（staging 即最终，无需移动）
            Path finalDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId))
                    .resolve(String.valueOf(chapterId));
            Files.createDirectories(finalDir);
            Files.writeString(finalDir.resolve("001.jpg"), "fake-jpeg");

            importPersistenceService.applyFinalizeCompleted(new ImportStorageFinalizeCompletedEvent(
                    UUID.randomUUID(), Instant.now(), taskId, comicId, chapterId.intValue(), chapterId,
                    "hq/" + comicId + "/" + chapterId, 1));

            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.IMPORTING);
            assertThat(mediaMapper.selectById(page.getId()).getHqStatus()).isEqualTo(HqStatus.READY);
            assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo(ImportTaskStatus.FINALIZING);

            importPersistenceService.applyMetadataRefreshCompleted(new ImportMetadataRefreshCompletedEvent(
                    UUID.randomUUID(), Instant.now(), taskId, comicId));
            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.READY);
            assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo(ImportTaskStatus.SUCCESS);

            ReaderDTO dto = readChapter(chapterId);
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
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.READY);
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

        @Test
        @DisplayName("现代恢复：metadata hqPath 优先重建 page，新 DB chapterId 可不同，hqPath 保留真实磁盘引用")
        void recovery_rebuildsModern_withRealHqPathStorageRef() throws Exception {
            long comicId = 8800002L;
            long legacyChapterId = 99L;
            Path hqDir = MANGA_ROOT.resolve("hq").resolve(String.valueOf(comicId)).resolve(String.valueOf(legacyChapterId));
            Files.createDirectories(hqDir);
            Files.writeString(hqDir.resolve("001.jpg"), "fake-jpeg-modern");
            writeModernRecoveryMetadata(comicId, legacyChapterId);

            var progress = recoveryEngine.processComicDir(comicId, 0);
            assertThat(progress.recoveredComics()).isEqualTo(1);

            Comic comic = comicMapper.selectById(comicId);
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.READY);
            assertThat(comic.getTitle()).isEqualTo("现代恢复漫画");

            List<Catalog> catalogs = catalogMapper.selectList(
                    new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId)
                            .orderByAsc(Catalog::getSortOrder));
            assertThat(catalogs).hasSize(2);
            assertThat(catalogs.get(1).getParentId()).isEqualTo(catalogs.get(0).getId());

            List<Chapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
            assertThat(chapters).hasSize(1);
            Chapter chapter = chapters.get(0);
            assertThat(chapter.getPageCount()).isEqualTo(1);
            assertThat(chapter.getCatalogId()).isEqualTo(catalogs.get(1).getId());
            assertThat(chapter.getGlobalOrder()).isEqualTo(3);

            List<Media> pages = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapter.getId()));
            assertThat(pages).hasSize(1);
            Media page = pages.get(0);
            assertThat(page.getHqPath()).isEqualTo(comicId + "/" + legacyChapterId + "/001.jpg");
            assertThat(page.getHqStatus()).isEqualTo(HqStatus.READY);
            assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(comicId + "/" + legacyChapterId + "/001.jpg")))
                    .isTrue();

            ReaderDTO dto = readChapter(chapter.getId());
            assertThat(dto.getPages()).hasSize(1);
            assertThat(dto.getPages().get(0).getHqUrl())
                    .isEqualTo("/files/hq/" + comicId + "/" + legacyChapterId + "/001.jpg");
        }

        @Test
        @DisplayName("恢复缺文件：hqPath 指向不存在文件 → media MISSING，不得标 READY")
        void recovery_rebuildsMissingFile_asMissingNotReady() throws Exception {
            long comicId = 8800003L;
            writeMissingFileRecoveryMetadata(comicId);

            var progress = recoveryEngine.processComicDir(comicId, 0);
            assertThat(progress.recoveredComics()).isEqualTo(1);

            Comic comic = comicMapper.selectById(comicId);
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.READY);

            List<Chapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
            assertThat(chapters).hasSize(1);
            List<Media> pages = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapters.get(0).getId()));
            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).getHqStatus()).isEqualTo(HqStatus.MISSING);
            assertThat(pages.get(0).getHqPath()).isEqualTo(comicId + "/77/001.jpg");
        }

        @Test
        @DisplayName("恢复坏索引：catalog parentIndex 越界 → typed-fail，不写任何 DB 行、不静默挂根")
        void recovery_rejectsBadParentIndex_typedFail_noDbRows() throws Exception {
            long comicId = 8800004L;
            writeBadIndexRecoveryMetadata(comicId);

            var progress = recoveryEngine.processComicDir(comicId, 0);
            assertThat(progress.errorComics()).isEqualTo(1);
            assertThat(progress.lastError()).contains("parentIndex");

            assertThat(comicMapper.selectById(comicId)).isNull();
            assertThat(catalogMapper.selectCount(
                    new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId))).isZero();
            assertThat(chapterMapper.selectCount(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))).isZero();
        }
    }

    // ======================== 5. 旧 globalOrder 路径 ========================

    @Nested
    @DisplayName("旧 globalOrder 路径继续读取，不重写 DB path")
    class LegacyGlobalOrderPathTests {

        @Test
        @DisplayName("DB 中旧布局 hq_path 原样解析，不重写为推测值")
        void legacyGlobalOrderPath_readsWithoutRewrite() throws Exception {
            Long comicId = insertComic("LEGACY-COMIC", ComicStatus.READY);
            // 旧布局：目录按 globalOrder 而非 chapterId
            int globalOrder = 7;
            Long chapterId = insertChapter(comicId, globalOrder, "1", ChapterLifecycleStatus.READY);
            String legacyPath = comicId + "/" + globalOrder + "/001.jpg";
            Long mediaId = insertMedia(chapterId, 1, legacyPath, MediaLifecycleStatus.READY);
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

    private void writeModernRecoveryMetadata(long comicId, long legacyChapterId) throws IOException {
        String metadata = """
            {
              "version": 3,
              "comic": {"title": "现代恢复漫画", "author": "现代作者", "category": "Action"},
              "catalogs": [
                {"title": "卷1", "sortOrder": 0},
                {"title": "卷1·话", "sortOrder": 1, "parentIndex": 0}
              ],
              "chapters": [
                {
                  "title": "第1话", "chapterNo": "1", "sortOrder": 0, "globalOrder": 3, "catalogIndex": 1,
                  "mediaItems": [
                    {"pageNumber": 1, "fileName": "001.jpg", "hqPath": "%d/%d/001.jpg",
                     "fileSize": 2048, "width": 100, "height": 150, "mediaType": "IMAGE", "hqStatus": "READY"}
                  ]
                }
              ]
            }
            """.formatted(comicId, legacyChapterId);
        Files.writeString(MANGA_ROOT.resolve("metadata").resolve(comicId + ".json"), metadata);
    }

    private void writeMissingFileRecoveryMetadata(long comicId) throws IOException {
        String metadata = """
            {
              "version": 3,
              "comic": {"title": "缺文件漫画", "author": "作者", "category": null},
              "catalogs": [],
              "chapters": [
                {
                  "title": "第1话", "chapterNo": "1", "sortOrder": 0, "globalOrder": 0, "catalogIndex": null,
                  "mediaItems": [
                    {"pageNumber": 1, "fileName": "001.jpg", "hqPath": "%d/77/001.jpg",
                     "fileSize": 1024, "width": 100, "height": 150, "mediaType": "IMAGE", "hqStatus": "READY"}
                  ]
                }
              ]
            }
            """.formatted(comicId);
        Files.writeString(MANGA_ROOT.resolve("metadata").resolve(comicId + ".json"), metadata);
    }

    private void writeBadIndexRecoveryMetadata(long comicId) throws IOException {
        String metadata = """
            {
              "version": 3,
              "comic": {"title": "坏索引漫画", "author": "作者", "category": null},
              "catalogs": [
                {"title": "目录1", "sortOrder": 0, "parentIndex": 5}
              ],
              "chapters": [
                {"title": "第1话", "chapterNo": "1", "sortOrder": 0, "globalOrder": 0, "catalogIndex": null}
              ]
            }
            """;
        Files.writeString(MANGA_ROOT.resolve("metadata").resolve(comicId + ".json"), metadata);
    }

    /** 通过真实媒体回收管线提交 MEDIA_TRASH，返回任务信息 */
    private OperationSubmitResultSafe trashMediaViaPipeline(Long mediaId) {
        com.comicatlas.api.management.dto.OperationSubmitResultDTO result = mediaManagementService.trash(mediaId);
        assertThat(result.isNoOp()).isFalse();
        return new OperationSubmitResultSafe(result.getTaskId(), result.getItemCount());
    }

    private record OperationSubmitResultSafe(Long taskId, Integer itemCount) {
    }
}

