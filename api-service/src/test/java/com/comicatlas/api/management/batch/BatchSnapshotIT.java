package com.comicatlas.api.management.batch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 跨页批量快照集成测试（TDD）。
 *
 * <p>验证批量选择快照语义：
 * <ul>
 *   <li>FILTER{ComicListQuery, excludedIds} 判别联合：257 本筛选排除 2 本 → 物化 255 items</li>
 *   <li>创建后并发新增漫画不进入既有批次（快照语义）</li>
 *   <li>IDS 判别联合、稳定排序（id ASC）物化</li>
 *   <li>超限（max-items 可配置）→ 409 稳定 reasonCode；空筛选 → 409</li>
 *   <li>危险操作（COMIC_PURGE）preview token 过期/条件变化 → 409</li>
 *   <li>Idempotency-Key 重放不重复</li>
 *   <li>部分 blocked 汇总正确；只重试失败 items</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨页批量快照集成测试")
class BatchSnapshotIT {

    private static boolean dockerAvailable;

    static {
        dockerAvailable = checkDockerAvailable();
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_atlas_batch_test")
                .withUsername("test")
                .withPassword("test")
            : null;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
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
        registry.add("comic.batch.max-items", () -> 10000);
        registry.add("comic.batch.preview-ttl-seconds", () -> 300);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ComicMapper comicMapper;

    @Autowired
    private ManagementTaskMapper taskMapper;

    @Autowired
    private ManagementTaskItemMapper itemMapper;

    @Autowired
    private com.comicatlas.api.management.batch.config.BatchProperties batchProperties;

    private final String filterPrefix = "BATCHSNAP-";

    @AfterEach
    void tearDown() {
        if (itemMapper != null) { itemMapper.delete(new LambdaQueryWrapper<>()); }
        if (taskMapper != null) { taskMapper.delete(new LambdaQueryWrapper<>()); }
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

    // ======================== FILTER + excluded 快照 ========================

    @Nested
    @DisplayName("FILTER + excluded 判别联合快照")
    class FilterSnapshotTests {

        @Test
        @DisplayName("257 本筛选排除 2 本 → 物化 255 items，稳定排序，不含后增")
        void filterExcluded_materializes255Items_fixedSnapshot() throws Exception {
            insertComics(257);

            // 预览：选出全部 257 本，排除其中 2 本
            long excludeA = comicMapper.selectList(new LambdaQueryWrapper<Comic>()
                    .eq(Comic::getTitle, filterPrefix + "0")).get(0).getId();
            long excludeB = comicMapper.selectList(new LambdaQueryWrapper<Comic>()
                    .eq(Comic::getTitle, filterPrefix + "1")).get(0).getId();

            String previewBody = """
                {"operation":"METADATA_UPDATE",
                 "selection":{"type":"FILTER","query":{"keyword":"%s"},"excludedIds":[%d,%d]}}
                """.formatted(filterPrefix, excludeA, excludeB);

            MvcResult preview = mockMvc.perform(post("/api/management/batch/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(previewBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.selectedCount").value(255))
                    .andExpect(jsonPath("$.data.eligibleCount").value(255))
                    .andExpect(jsonPath("$.data.blocked.length()").value(0))
                    .andReturn();
            JsonNode previewData = objectMapper.readTree(
                    preview.getResponse().getContentAsString()).path("data");
            assertThat(previewData.path("selectedCount").asInt()).isEqualTo(255);
            assertThat(previewData.path("eligibleCount").asInt()).isEqualTo(255);

            // 创建批量任务（同 selection）
            MvcResult result = mockMvc.perform(post("/api/management/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(previewBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.selectedCount").value(255))
                    .andExpect(jsonPath("$.data.eligibleCount").value(255))
                    .andReturn();
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            long taskId = data.path("task").path("id").asLong();
            assertThat(taskId).isPositive();

            // 物化 255 个固定 item
            List<ManagementTaskItemResponse> items = getTaskItems(taskId);
            assertThat(items).hasSize(255);

            List<Long> itemTargetIds = items.stream()
                    .map(ManagementTaskItemResponse::getTargetId)
                    .sorted()
                    .collect(Collectors.toList());
            assertThat(itemTargetIds).doesNotContain(excludeA, excludeB);
            assertThat(itemTargetIds).hasSize(255);

            // 稳定排序：targetId 升序
            assertThat(items.stream().map(ManagementTaskItemResponse::getTargetId).toList())
                    .isSortedAccordingTo(Long::compareTo);

            // 快照语义：创建后并发新增 3 本匹配漫画，不进入既有批次
            insertComics(260, 263);
            List<ManagementTaskItemResponse> after = getTaskItems(taskId);
            assertThat(after).hasSize(255);
            List<Long> afterIds = after.stream().map(ManagementTaskItemResponse::getTargetId).toList();
            List<Long> newIds = comicMapper.selectList(new LambdaQueryWrapper<Comic>()
                    .likeRight(Comic::getTitle, filterPrefix)
                    .orderByDesc(Comic::getId)
                    .last("LIMIT 3")).stream().map(Comic::getId).toList();
            assertThat(newIds).noneMatch(afterIds::contains);
        }

        @Test
        @DisplayName("空筛选 → 409 EMPTY_SELECTION")
        void emptyFilter_returns409() throws Exception {
            String body = """
                {"operation":"METADATA_UPDATE",
                 "selection":{"type":"FILTER","query":{"keyword":"NO-SUCH-COMIC-XYZ"}}}
                """;
            mockMvc.perform(post("/api/management/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EMPTY_SELECTION")));
        }

        @Test
        @DisplayName("超限（max-items 可配置）→ 409 BATCH_SIZE_EXCEEDED")
        void overLimit_returns409() throws Exception {
            insertComics(12);
            // 临时调小上限验证可配置：恢复原值保证后续测试不受影响
            int original = batchProperties.getMaxItems();
            batchProperties.setMaxItems(10);
            try {
                String body = """
                    {"operation":"METADATA_UPDATE",
                     "selection":{"type":"FILTER","query":{"keyword":"%s"}}}
                    """.formatted(filterPrefix);
                mockMvc.perform(post("/api/management/batch")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(409))
                        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("BATCH_SIZE_EXCEEDED")));
            } finally {
                batchProperties.setMaxItems(original);
            }
        }
    }

    // ======================== IDS 判别联合 ========================

    @Nested
    @DisplayName("IDS 判别联合")
    class IdsSelectionTests {

        @Test
        @DisplayName("IDS 显式列表精确物化，稳定排序")
        void idsSelection_materializesExactItems() throws Exception {
            insertComics(5);
            List<Long> all = comicMapper.selectList(new LambdaQueryWrapper<Comic>()
                    .likeRight(Comic::getTitle, filterPrefix)
                    .orderByAsc(Comic::getId))
                    .stream().map(Comic::getId).collect(Collectors.toList());
            List<Long> chosen = List.of(all.get(4), all.get(0), all.get(2));

            String body = objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "operation", "METADATA_UPDATE",
                            "selection", java.util.Map.of("type", "IDS", "ids", chosen)));

            MvcResult result = mockMvc.perform(post("/api/management/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.selectedCount").value(3))
                    .andExpect(jsonPath("$.data.eligibleCount").value(3))
                    .andReturn();
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            long taskId = data.path("task").path("id").asLong();

            List<ManagementTaskItemResponse> items = getTaskItems(taskId);
            assertThat(items).hasSize(3);
            assertThat(items.stream().map(ManagementTaskItemResponse::getTargetId).toList())
                    .containsExactlyInAnyOrder(chosen.toArray(new Long[0]));
        }
    }

    // ======================== Idempotency 重放 ========================

    @Nested
    @DisplayName("Idempotency-Key 重放")
    class IdempotencyReplayTests {

        @Test
        @DisplayName("同键同 payload 重放不重复建任务")
        void replay_sameKey_sameTask_noDuplicate() throws Exception {
            insertComics(3);
            String body = """
                {"operation":"METADATA_UPDATE",
                 "selection":{"type":"FILTER","query":{"keyword":"%s"}}}
                """.formatted(filterPrefix);

            MvcResult first = mockMvc.perform(post("/api/management/batch")
                            .header("Idempotency-Key", "batch-replay-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            long taskId1 = objectMapper.readTree(first.getResponse().getContentAsString())
                    .path("data").path("task").path("id").asLong();

            long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getTaskId, taskId1));

            MvcResult second = mockMvc.perform(post("/api/management/batch")
                            .header("Idempotency-Key", "batch-replay-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            long taskId2 = objectMapper.readTree(second.getResponse().getContentAsString())
                    .path("data").path("task").path("id").asLong();

            assertThat(taskId2).isEqualTo(taskId1);
            assertThat(itemMapper.selectCount(new LambdaQueryWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getTaskId, taskId1))).isEqualTo(itemCount);
        }

        @Test
        @DisplayName("同键不同 payload → 409 IDEMPOTENCY_CONFLICT")
        void replay_sameKeyDifferentPayload_returns409() throws Exception {
            insertComics(3);
            String body1 = """
                {"operation":"METADATA_UPDATE",
                 "selection":{"type":"FILTER","query":{"keyword":"%s"}}}
                """.formatted(filterPrefix);
            String body2 = """
                {"operation":"METADATA_UPDATE",
                 "selection":{"type":"IDS","ids":[1]}}
                """;

            mockMvc.perform(post("/api/management/batch")
                            .header("Idempotency-Key", "batch-replay-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body1))
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/api/management/batch")
                            .header("Idempotency-Key", "batch-replay-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body2))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }
    }

    // ======================== 危险操作 preview token ========================

    @Nested
    @DisplayName("危险操作 preview token")
    class DangerousPreviewTests {

        @Test
        @DisplayName("COMIC_PURGE 需 preview token；过期/条件变化返回 409")
        void purge_previewTokenRequired_expiryAndConditionChange() throws Exception {
            // 插入 TRASHED 漫画（PURGE 前置状态）
            insertComicsWithStatus(ComicStatus.TRASHED, 5);
            String body = """
                {"operation":"COMIC_PURGE",
                 "selection":{"type":"FILTER","query":{"keyword":"%s"}}}
                """.formatted(filterPrefix);

            // 1. 无 token 直接创建 → 409 PREVIEW_TOKEN_REQUIRED
            mockMvc.perform(post("/api/management/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PREVIEW_TOKEN_REQUIRED")));

            // 2. preview 获取 token
            MvcResult preview = mockMvc.perform(post("/api/management/batch/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.dangerous").value(true))
                    .andReturn();
            JsonNode previewData = objectMapper.readTree(
                    preview.getResponse().getContentAsString()).path("data");
            String token = previewData.path("previewToken").asText();
            assertThat(token).isNotBlank();

            // 3. 条件变化：preview 后再新增一本 TRASHED 漫画 → 409 PREVIEW_CONDITION_CHANGED
            String changedBody = """
                {"operation":"COMIC_PURGE",
                 "selection":{"type":"FILTER","query":{"keyword":"%s"}},
                 "previewToken":"%s"}
                """.formatted(filterPrefix, token);
            insertComicsWithStatus(ComicStatus.TRASHED, 1);
            mockMvc.perform(post("/api/management/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(changedBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PREVIEW_CONDITION_CHANGED")));

            // 4. 过期 token → 409 PREVIEW_TOKEN_EXPIRED
            String expiredToken = "definitely-not-a-valid-token";
            String expiredBody = """
                {"operation":"COMIC_PURGE",
                 "selection":{"type":"FILTER","query":{"keyword":"%s"}},
                 "previewToken":"%s"}
                """.formatted(filterPrefix, expiredToken);
            mockMvc.perform(post("/api/management/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(expiredBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PREVIEW_TOKEN")));

            // 5. 有效 token + 条件未变 → 成功物化
            MvcResult preview2 = mockMvc.perform(post("/api/management/batch/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            String token2 = objectMapper.readTree(preview2.getResponse().getContentAsString())
                    .path("data").path("previewToken").asText();
            String okBody = """
                {"operation":"COMIC_PURGE",
                 "selection":{"type":"FILTER","query":{"keyword":"%s"}},
                 "previewToken":"%s"}
                """.formatted(filterPrefix, token2);
            MvcResult ok = mockMvc.perform(post("/api/management/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(okBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            JsonNode okData = objectMapper.readTree(ok.getResponse().getContentAsString()).path("data");
            long taskId = okData.path("task").path("id").asLong();
            assertThat(okData.path("eligibleCount").asInt()).isEqualTo(6);
            assertThat(getTaskItems(taskId)).hasSize(6);
        }
    }

    // ======================== blocked 汇总 + 只重试失败 ========================

    @Nested
    @DisplayName("blocked 汇总与失败重试")
    class BlockedAndRetryTests {

        @Test
        @DisplayName("部分 blocked 汇总正确；只重试失败 items")
        void blockedAggregation_and_retryOnlyFailed() throws Exception {
            // 5 本 READY + 1 本 TRASHED（TRASHED 不允许 METADATA_UPDATE 的 EDIT）
            insertComics(5);
            insertComicsWithStatus(ComicStatus.TRASHED, 1);

            String body = """
                {"operation":"METADATA_UPDATE",
                 "selection":{"type":"FILTER","query":{"keyword":"%s"}}}
                """.formatted(filterPrefix);

            MvcResult result = mockMvc.perform(post("/api/management/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");

            // 筛选到 6 本，但只有 5 本 READY 可编辑；1 本 TRASHED blocked
            assertThat(data.path("selectedCount").asInt()).isEqualTo(6);
            assertThat(data.path("eligibleCount").asInt()).isEqualTo(5);
            assertThat(data.path("blocked").size()).isEqualTo(1);
            assertThat(data.path("blocked").get(0).path("comicId").asLong()).isPositive();
            assertThat(data.path("blocked").get(0).path("reasonCode").asText()).isEqualTo("OP_NOT_ALLOWED");

            long taskId = data.path("task").path("id").asLong();
            List<ManagementTaskItemResponse> items = getTaskItems(taskId);
            assertThat(items).hasSize(5);

            // 将其中一个 item 标记为 FAILED，模拟失败项
            ManagementTaskItemResponse failedItem = items.get(0);
            markItemFailed(taskId, failedItem.getId());

            // retry → 只有失败 item 被重置，成功 item 保持 SUCCEEDED
            mockMvc.perform(post("/api/management/tasks/{id}/retry", taskId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            List<ManagementTaskItemResponse> afterRetry = getTaskItems(taskId);
            ManagementTaskItemResponse retried = afterRetry.stream()
                    .filter(i -> i.getId().equals(failedItem.getId()))
                    .findFirst().orElseThrow();
            assertThat(retried.getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
            assertThat(retried.getAttempt()).isEqualTo(2);

            // 其余成功 item 未重置（attempt 保持 1，状态保持终态）
            List<ManagementTaskItemResponse> others = afterRetry.stream()
                    .filter(i -> !i.getId().equals(failedItem.getId()))
                    .toList();
            assertThat(others).allMatch(i -> i.getStatus().isTerminal());
            assertThat(others).allMatch(i -> i.getAttempt() == 1);
        }
    }

    // ======================== 辅助方法 ========================

    private void insertComics(int count) {
        for (int i = 0; i < count; i++) {
            insertComic(filterPrefix + i, ComicStatus.READY);
        }
    }

    private void insertComics(int start, int endExclusive) {
        for (int i = start; i < endExclusive; i++) {
            insertComic(filterPrefix + i, ComicStatus.READY);
        }
    }

    private void insertComicsWithStatus(ComicStatus status, int count) {
        for (int i = 0; i < count; i++) {
            insertComic(filterPrefix + status + "-" + i, status);
        }
    }

    private void insertComic(String title, ComicStatus status) {
        Comic c = new Comic();
        c.setTitle(title);
        c.setStatus(status);
        c.setStoragePolicy("MANAGED");
        c.setVersion(1);
        comicMapper.insert(c);
    }

    private List<ManagementTaskItemResponse> getTaskItems(long taskId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/management/tasks/{id}/items", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode arr = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        List<ManagementTaskItemResponse> out = new java.util.ArrayList<>();
        for (JsonNode n : arr) {
            ManagementTaskItemResponse it = new ManagementTaskItemResponse();
            it.setId(n.path("id").asLong());
            it.setTaskId(n.path("taskId").asLong());
            it.setTargetId(n.path("targetId").asLong());
            it.setTargetType(n.path("targetType").asText());
            it.setOperationType(com.comicatlas.api.common.enums.TaskType.valueOf(n.path("operationType").asText()));
            it.setStatus(ManagementTaskStatus.valueOf(n.path("status").asText()));
            it.setAttempt(n.path("attempt").asInt());
            out.add(it);
        }
        return out;
    }

    private void markItemFailed(long taskId, long itemId) throws Exception {
        // 直接调用 DB 更新模拟失败，避免依赖 MQ 事件
        ManagementTaskItem item = itemMapper.selectById(itemId);
        item.setStatus(ManagementTaskStatus.FAILED);
        item.setErrorMessage("测试注入失败");
        item.setLockKey(null);
        item.setCompletedAt(java.time.LocalDateTime.now());
        itemMapper.updateById(item);
    }
}
