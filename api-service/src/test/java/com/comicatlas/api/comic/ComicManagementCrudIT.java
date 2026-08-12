package com.comicatlas.api.comic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.event.ImportEventHandler;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.ImportTaskFailedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 漫画管理 CRUD 契约集成测试（TDD）。
 *
 * <p>验证：
 * <ul>
 *   <li>POST /api/manage/comics — 创建 DRAFT（标题必填、可选 metadata/category/tags）</li>
 *   <li>PUT /api/manage/comics/{id} — version 乐观锁更新，并发冲突 409</li>
 *   <li>DELETE /api/manage/comics/{id} — 改为回收任务而非硬删</li>
 *   <li>阅读列表排除 DRAFT/IMPORT_FAILED/TRASHED（列表/详情查询已迁至 reading-service，此处直接验证 DB 状态）</li>
 *   <li>导入创建走 ImportService：预创建 comic 与 management task 同事务；成功 READY、失败 IMPORT_FAILED</li>
 *   <li>Idempotency-Key 重放不重复创建</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("漫画管理 CRUD 契约测试")
class ComicManagementCrudIT {

    private static boolean dockerAvailable;

    static {
        dockerAvailable = checkDockerAvailable();
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_atlas_crud_test")
                .withUsername("test")
                .withPassword("test")
            : null;

    /** 测试专用 MANGA_ROOT，导入 metadata.json 与事件处理都在此目录 */
    private static final Path MANGA_ROOT = createTempMangaRoot();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("MANGA_ROOT", () -> MANGA_ROOT.toString());
        if (dockerAvailable && mysql != null && mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        } else {
            registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.sql.init.mode", () -> "always");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ComicMapper comicMapper;

    @Autowired
    private ImportTaskMapper importTaskMapper;

    @Autowired
    private ManagementTaskMapper managementTaskMapper;

    @Autowired
    private ManagementTaskItemMapper managementTaskItemMapper;

    @Autowired
    private ImportEventHandler importEventHandler;

    @AfterEach
    void tearDown() {
        if (managementTaskItemMapper != null) { managementTaskItemMapper.delete(new LambdaQueryWrapper<>()); }
        if (managementTaskMapper != null) { managementTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (importTaskMapper != null) { importTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (comicMapper != null) { comicMapper.delete(new LambdaQueryWrapper<>()); }
    }

    private static boolean checkDockerAvailable() {
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
            Path root = Files.createTempDirectory("manga-crud-test");
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

    /** 将 Windows 路径转义为 JSON 字符串字面量（\ 需要双写） */
    private static String escapedPath(String name) {
        return MANGA_ROOT.resolve(name).toString().replace("\\", "\\\\");
    }

    // ======================== 创建 ========================

    @Nested
    @DisplayName("POST /api/comics 创建 DRAFT")
    class CreateComicTests {

        @Test
        @DisplayName("正常创建返回 DRAFT 与 lifecycle/allowedOperations/version")
        void createComic_returnsDraft() throws Exception {
            String body = """
                {"title": "新漫画", "author": "作者A", "description": "描述", "titleJpn": "タイトル"}
                """;

            mockMvc.perform(post("/api/manage/comics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.lifecycle").value("DRAFT"))
                    .andExpect(jsonPath("$.data.title").value("新漫画"))
                    .andExpect(jsonPath("$.data.author").value("作者A"))
                    .andExpect(jsonPath("$.data.version").value(1))
                    .andExpect(jsonPath("$.data.activeTask").value(nullValue()))
                    .andExpect(jsonPath("$.data.allowedOperations.allowed", hasItem("IMPORT")))
                    .andExpect(jsonPath("$.data.allowedOperations.allowed", hasItem("EDIT")))
                    .andExpect(jsonPath("$.data.allowedOperations.allowed", hasItem("DELETE")));

            // DB 断言：确实落库为 DRAFT
            assertThat(comicMapper.selectCount(new LambdaQueryWrapper<Comic>()
                    .eq(Comic::getTitle, "新漫画"))).isEqualTo(1);
        }

        @Test
        @DisplayName("缺少标题返回 400")
        void createComic_blankTitle_returns400() throws Exception {
            mockMvc.perform(post("/api/manage/comics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }

        @Test
        @DisplayName("分类不存在返回 400")
        void createComic_invalidCategory_returns400() throws Exception {
            mockMvc.perform(post("/api/manage/comics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"新漫画\", \"categoryId\": 99999}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }

        @Test
        @DisplayName("标签不存在返回 400")
        void createComic_invalidTag_returns400() throws Exception {
            mockMvc.perform(post("/api/manage/comics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"新漫画\", \"tagIds\": [99999]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    // ======================== 读取 ========================
    // 漫画详情/列表查询已随拆分迁至 reading-service，管理端测试直接验证 DB 状态。

    @Nested
    @DisplayName("创建后漫画详情状态")
    class GetComicDetailTests {

        @Test
        @DisplayName("创建后 DB 状态为 DRAFT")
        void getDetail_returnsLifecycleAndPolicy() throws Exception {
            long id = createDraft("详情漫画");

            Comic comic = comicMapper.selectById(id);
            assertThat(comic).isNotNull();
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.DRAFT);
        }

        @Test
        @DisplayName("不存在的漫画查询返回 null")
        void getDetail_nonExisting_returns404() {
            assertThat(comicMapper.selectById(99999L)).isNull();
        }
    }

    // ======================== 更新（version 乐观锁） ========================

    @Nested
    @DisplayName("PUT /api/comics/{id} 乐观锁更新")
    class UpdateComicTests {

        @Test
        @DisplayName("带正确 version 更新成功并递增版本")
        void updateComic_withVersion_succeeds() throws Exception {
            long id = createDraft("更新前");

            mockMvc.perform(put("/api/manage/comics/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\": 1, \"title\": \"更新后\", \"author\": \"新作者\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value("更新后"))
                    .andExpect(jsonPath("$.data.version").value(2))
                    .andExpect(jsonPath("$.data.lifecycle").value("DRAFT"));

            Comic updated = comicMapper.selectById(id);
            assertThat(updated.getTitle()).isEqualTo("更新后");
            assertThat(updated.getVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("过期 version 返回 409")
        void updateComic_staleVersion_returns409() throws Exception {
            long id = createDraft("过期版本");

            mockMvc.perform(put("/api/manage/comics/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\": 1, \"title\": \"第一次\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 再次用旧 version=1 提交 → 409
            mockMvc.perform(put("/api/manage/comics/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\": 1, \"title\": \"第二次\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("并发同 version 提交：一个成功一个 409")
        void concurrentUpdate_sameVersion_oneSucceedsOne409() throws Exception {
            long id = createDraft("并发更新");

            int threadCount = 2;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                List<Future<MvcResult>> futures = new ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    String title = "并发-" + System.nanoTime();
                    String body = "{\"version\": 1, \"title\": \"" + title + "\"}";
                    futures.add(executor.submit(() -> {
                        barrier.await();
                        return mockMvc.perform(put("/api/manage/comics/{id}", id)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                                .andReturn();
                    }));
                }

                int ok = 0;
                int conflict = 0;
                for (Future<MvcResult> future : futures) {
                    MvcResult result = future.get();
                    JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
                    int code = node.path("code").asInt();
                    if (code == 200) { ok++; }
                    else if (code == 409) { conflict++; }
                }
                assertThat(ok).isEqualTo(1);
                assertThat(conflict).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    // ======================== 回收任务 ========================

    @Nested
    @DisplayName("DELETE /api/comics/{id} 回收任务")
    class DeleteComicTests {

        @Test
        @DisplayName("删除创建回收任务且不硬删行")
        void deleteComic_createsTrashTask_keepsRow() throws Exception {
            long id = createDraft("待回收");

            MvcResult result = mockMvc.perform(delete("/api/manage/comics/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.taskType").value("COMIC_DELETE"))
                    .andExpect(jsonPath("$.data.targetType").value("COMIC"))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andReturn();

            long taskId = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asLong();

            // 行保留（软删除），状态迁移为 TRASHING（等待 Worker 回收完成）
            Comic comic = comicMapper.selectById(id);
            assertThat(comic).isNotNull();
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.TRASHING);

            // 回收任务存在且关联 comic
            ManagementTask task = managementTaskMapper.selectById(taskId);
            assertThat(task).isNotNull();
            ManagementTaskItem item = managementTaskItemMapper.selectOne(new LambdaQueryWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getTaskId, taskId));
            assertThat(item).isNotNull();
            assertThat(item.getTargetId()).isEqualTo(id);
            assertThat(item.getOperationType()).isEqualTo(TaskType.COMIC_DELETE);
        }

        @Test
        @DisplayName("已删除漫画再次 DELETE 返回 409")
        void deleteComic_alreadyDeleted_returns409() throws Exception {
            long id = createDraft("重复删除");
            mockMvc.perform(delete("/api/manage/comics/{id}", id))
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(delete("/api/manage/comics/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("DELETE 携带 Idempotency-Key 重放不重复建任务")
        void deleteComic_idempotencyKey_replay_noDuplicateTask() throws Exception {
            long id = createDraft("幂等删除");

            MvcResult first = mockMvc.perform(delete("/api/manage/comics/{id}", id)
                            .header("Idempotency-Key", "delete-key-" + id))
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            long firstTaskId = objectMapper.readTree(first.getResponse().getContentAsString())
                    .path("data").path("id").asLong();

            MvcResult second = mockMvc.perform(delete("/api/manage/comics/{id}", id)
                            .header("Idempotency-Key", "delete-key-" + id))
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            long secondTaskId = objectMapper.readTree(second.getResponse().getContentAsString())
                    .path("data").path("id").asLong();

            assertThat(secondTaskId).isEqualTo(firstTaskId);
            long taskCount = managementTaskMapper.selectCount(new LambdaQueryWrapper<ManagementTask>()
                    .eq(ManagementTask::getTaskType, TaskType.COMIC_DELETE));
            assertThat(taskCount).isEqualTo(1);
        }
    }

    // ======================== 阅读列表排除 ========================

    @Nested
    @DisplayName("阅读列表排除非可读状态")
    class ReadingListExclusionTests {

        @Test
        @DisplayName("DRAFT/IMPORT_FAILED/TRASHED/DELETED 不出现，READY 出现")
        void readingList_excludesNonReadableStatuses() {
            insertComic("LIST-EXCL-DRAFT", ComicStatus.DRAFT);
            insertComic("LIST-EXCL-FAILED", ComicStatus.IMPORT_FAILED);
            insertComic("LIST-EXCL-TRASHED", ComicStatus.TRASHED);
            insertComic("LIST-EXCL-DELETED", ComicStatus.DELETED);
            insertComic("LIST-EXCL-READY", ComicStatus.READY);

            List<Comic> readable = comicMapper.selectList(
                    new LambdaQueryWrapper<Comic>().eq(Comic::getStatus, ComicStatus.READY));
            assertThat(readable).hasSize(1);
            assertThat(readable.get(0).getTitle()).isEqualTo("LIST-EXCL-READY");
        }
    }

    // ======================== 导入 ========================

    @Nested
    @DisplayName("导入创建与生命周期")
    class ImportLifecycleTests {

        @Test
        @DisplayName("导入创建：预创建 comic 与 management task 同事务")
        void importCreation_preCreatesComicAndManagementTask() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/manage/tasks/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {"sourceType": "DIRECTORY", "sourcePath": "%s"}
                                """.formatted(escapedPath("import-src"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();

            long taskId = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asLong();
            long comicId = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("comicId").asLong();

            // comic 处于 IMPORTING
            Comic comic = comicMapper.selectById(comicId);
            assertThat(comic).isNotNull();
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.IMPORTING);

            // import_task 关联 management task
            ImportTask importTask = importTaskMapper.selectById(taskId);
            assertThat(importTask.getManagementTaskId()).isNotNull();

            ManagementTask managementTask = managementTaskMapper.selectById(importTask.getManagementTaskId());
            assertThat(managementTask).isNotNull();
            assertThat(managementTask.getTaskType()).isEqualTo(TaskType.IMPORT);
            assertThat(managementTask.getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
        }

        @Test
        @DisplayName("导入成功：comic → READY，management task item → SUCCEEDED")
        void importSuccess_setsComicReady() throws Exception {
            long[] ids = createImportTask("导入成功");
            long taskId = ids[0];
            long comicId = ids[1];

            writeMetadata(taskId, "导入成功漫画", "导入作者");

            importEventHandler.handleComicImported(
                    new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, null),
                    null, 0L);

            Comic comic = comicMapper.selectById(comicId);
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.READY);
            assertThat(comic.getTitle()).isEqualTo("导入成功漫画");

            ImportTask importTask = importTaskMapper.selectById(taskId);
            assertThat(importTask.getStatus()).isEqualTo(ImportTaskStatus.SUCCESS);

            // management task item 终态 SUCCEEDED
            ManagementTaskItem item = managementTaskItemMapper.selectOne(new LambdaQueryWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getTargetType, "COMIC")
                    .eq(ManagementTaskItem::getTargetId, comicId)
                    .eq(ManagementTaskItem::getOperationType, TaskType.IMPORT));
            assertThat(item).isNotNull();
            assertThat(item.getStatus()).isEqualTo(ManagementTaskStatus.SUCCEEDED);
        }

        @Test
        @DisplayName("导入失败：comic → IMPORT_FAILED，management task item → FAILED")
        void importFailure_setsComicImportFailed() throws Exception {
            long[] ids = createImportTask("导入失败");
            long taskId = ids[0];
            long comicId = ids[1];

            importEventHandler.handleImportTaskFailed(
                    new ImportTaskFailedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, "DOWNLOAD_FAILED", "下载失败"),
                    null, 0L);

            Comic comic = comicMapper.selectById(comicId);
            assertThat(comic.getStatus()).isEqualTo(ComicStatus.IMPORT_FAILED);

            ImportTask importTask = importTaskMapper.selectById(taskId);
            assertThat(importTask.getStatus()).isEqualTo(ImportTaskStatus.FAILED);

            ManagementTaskItem item = managementTaskItemMapper.selectOne(new LambdaQueryWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getTargetType, "COMIC")
                    .eq(ManagementTaskItem::getTargetId, comicId)
                    .eq(ManagementTaskItem::getOperationType, TaskType.IMPORT));
            assertThat(item).isNotNull();
            assertThat(item.getStatus()).isEqualTo(ManagementTaskStatus.FAILED);
        }

        @Test
        @DisplayName("IMPORT_FAILED 重试：comic 回到 IMPORTING，import_task 回到 PENDING")
        void retryAfterImportFailure_returnsToImporting() throws Exception {
            long[] ids = createImportTask("失败重试");
            long taskId = ids[0];
            long comicId = ids[1];

            importEventHandler.handleImportTaskFailed(
                    new ImportTaskFailedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, "DOWNLOAD_FAILED", "下载失败"),
                    null, 0L);
            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.IMPORT_FAILED);

            mockMvc.perform(post("/api/manage/tasks/import/{id}/retry", taskId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.IMPORTING);
            assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo(ImportTaskStatus.PENDING);
        }

        @Test
        @DisplayName("导入 Idempotency-Key 重放返回同一 comic，不重复建行")
        void importCreation_idempotencyKey_replay_returnsSameComic() throws Exception {
            String sourcePath = escapedPath("import-idem");
            String body = """
                {"sourceType": "DIRECTORY", "sourcePath": "%s"}
                """.formatted(sourcePath);

            MvcResult first = mockMvc.perform(post("/api/manage/tasks/import")
                            .header("Idempotency-Key", "import-idem-key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            JsonNode firstData = objectMapper.readTree(first.getResponse().getContentAsString()).path("data");
            long firstComicId = firstData.path("comicId").asLong();

            long comicCountBefore = comicMapper.selectCount(null);

            MvcResult second = mockMvc.perform(post("/api/manage/tasks/import")
                            .header("Idempotency-Key", "import-idem-key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            JsonNode secondData = objectMapper.readTree(second.getResponse().getContentAsString()).path("data");
            long secondComicId = secondData.path("comicId").asLong();

            assertThat(secondComicId).isEqualTo(firstComicId);
            assertThat(secondData.path("id").asLong()).isEqualTo(firstData.path("id").asLong());
            assertThat(comicMapper.selectCount(null)).isEqualTo(comicCountBefore);
        }

        @Test
        @DisplayName("同 Idempotency-Key 不同 payload 返回 409")
        void importCreation_idempotencyKey_differentPayload_returns409() throws Exception {
            mockMvc.perform(post("/api/manage/tasks/import")
                            .header("Idempotency-Key", "import-idem-key-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sourceType\": \"DIRECTORY\", \"sourcePath\": \"%s\"}"
                                    .formatted(escapedPath("idem-a"))))
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/api/manage/tasks/import")
                            .header("Idempotency-Key", "import-idem-key-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sourceType\": \"DIRECTORY\", \"sourcePath\": \"%s\"}"
                                    .formatted(escapedPath("idem-b"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }
    }

    // ======================== 辅助方法 ========================

    private long createDraft(String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/manage/comics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"%s\"}".formatted(title)))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private void insertComic(String title, ComicStatus status) {
        Comic comic = new Comic();
        comic.setTitle(title);
        comic.setStatus(status);
        comic.setStoragePolicy("MANAGED");
        comic.setVersion(1);
        comicMapper.insert(comic);
    }

    /** 创建导入任务，返回 [importTaskId, comicId] */
    private long[] createImportTask(String sourceName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/manage/tasks/import")
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

    private void writeMetadata(long taskId, String title, String author) throws IOException {
        String metadata = """
            {
              "version": 2,
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
                  "pages": [
                    {"pageNumber": 1, "imageName": "001.jpg", "fileSize": 1024, "width": 100, "height": 150}
                  ]
                }
              ]
            }
            """.formatted(title, author);
        Files.writeString(MANGA_ROOT.resolve("metadata").resolve(taskId + ".json"), metadata);
    }
}
