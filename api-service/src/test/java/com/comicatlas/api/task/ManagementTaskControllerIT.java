package com.comicatlas.api.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.task.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.mapper.ManagementTaskMapper;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.everyItem;


/**
 * ManagementTaskController 契约集成测试（TDD）。
 *
 * <p>验证：
 * <ul>
 *   <li>POST /api/manage/tasks — 创建任务，Idempotency-Key 支持</li>
 *   <li>GET /api/manage/tasks — 分页查询，支持过滤</li>
 *   <li>GET /api/manage/tasks/{id} — 任务详情</li>
 *   <li>GET /api/manage/tasks/{id}/items — 逐目标项列表</li>
 *   <li>POST /api/manage/tasks/{id}/cancel — 取消任务</li>
 *   <li>POST /api/manage/tasks/{id}/retry — 重试任务</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ManagementTaskController 契约测试")
class ManagementTaskControllerIT {

    private static boolean dockerAvailable;

    static {
        dockerAvailable = checkDockerAvailable();
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_atlas_test")
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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ManagementTaskMapper taskMapper;

    @Autowired
    private ManagementTaskItemMapper itemMapper;

    @Autowired
    private ManagementTaskService managementTaskService;

    @AfterEach
    void tearDown() {
        if (itemMapper != null) { itemMapper.delete(new LambdaQueryWrapper<>()); }
        if (taskMapper != null) { taskMapper.delete(new LambdaQueryWrapper<>()); }
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

    // ======================== 创建任务 ========================

    @Nested
    @DisplayName("POST /api/manage/tasks")
    class CreateTaskTests {

        @Test
        @DisplayName("正常创建返回 200 和任务数据")
        void createTask_returns200() throws Exception {
            CreateManagementTaskRequest req = buildImportRequest("CTRL-BATCH-01");
            String body = objectMapper.writeValueAsString(req);

            mockMvc.perform(post("/api/manage/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.taskType").value("IMPORT"))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andExpect(jsonPath("$.data.attempt").value(1))
                    .andExpect(jsonPath("$.data.isBatch").isBoolean())
                    .andExpect(jsonPath("$.data.batch").doesNotExist());
        }

        @Test
        @DisplayName("Idempotency-Key：同键同 body 返回原任务")
        void idempotency_sameKeySameBody_returnsExisting() throws Exception {
            CreateManagementTaskRequest req = buildImportRequest("CTRL-IDEM-01");
            String body = objectMapper.writeValueAsString(req);

            String firstResponse = mockMvc.perform(post("/api/manage/tasks")
                            .header("Idempotency-Key", "ctrl-idem-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            int firstId = objectMapper.readTree(firstResponse).get("data").get("id").asInt();

            mockMvc.perform(post("/api/manage/tasks")
                            .header("Idempotency-Key", "ctrl-idem-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(firstId));
        }

        @Test
        @DisplayName("Idempotency-Key：同键不同 body 返回 409")
        void idempotency_sameKeyDifferentBody_returns409() throws Exception {
            CreateManagementTaskRequest req1 = buildImportRequest("CTRL-IDEM-02A");
            CreateManagementTaskRequest req2 = buildImportRequest("CTRL-IDEM-02B");

            mockMvc.perform(post("/api/manage/tasks")
                            .header("Idempotency-Key", "ctrl-idem-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/manage/tasks")
                            .header("Idempotency-Key", "ctrl-idem-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isOk()) // BusinessException with code=409 still maps to 200 in this project
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value(containsString("payload 不匹配")));
        }

        @Test
        @DisplayName("缺少必填字段返回 400")
        void missingRequiredFields_returns400() throws Exception {
            String invalidBody = "{\"taskType\": \"IMPORT\"}"; // 缺少 operation

            mockMvc.perform(post("/api/manage/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isOk()) // validation exception wraps in Result
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    // ======================== 分页查询 ========================

    @Nested
    @DisplayName("GET /api/manage/tasks")
    class ListTasksTests {

        @BeforeEach
        void setUpData() {
            managementTaskService.createTask(buildImportRequest("CTRL-LIST-01"), null, null);
            managementTaskService.createTask(buildExportRequest("CTRL-LIST-02"), null, null);
        }

        @Test
        @DisplayName("默认分页返回任务列表")
        void defaultPagination_returnsList() throws Exception {
            mockMvc.perform(get("/api/manage/tasks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray())
                    .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(2)));
        }

        @Test
        @DisplayName("按 type=IMPORT 过滤")
        void filterByType() throws Exception {
            mockMvc.perform(get("/api/manage/tasks")
                            .param("type", "IMPORT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.records[*].taskType")
                            .value(everyItem(is("IMPORT"))));
        }

        @Test
        @DisplayName("按 status=QUEUED 过滤")
        void filterByStatus() throws Exception {
            mockMvc.perform(get("/api/manage/tasks")
                            .param("status", "QUEUED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.records[*].status")
                            .value(everyItem(is("QUEUED"))));
        }

        @Test
        @DisplayName("分页参数生效")
        void paginationParameters() throws Exception {
            mockMvc.perform(get("/api/manage/tasks")
                            .param("page", "1")
                            .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.records.length()").value(lessThanOrEqualTo(1)));
        }
    }

    // ======================== 任务详情 ========================

    @Nested
    @DisplayName("GET /api/manage/tasks/{id}")
    class GetTaskTests {

        @Test
        @DisplayName("获取存在的任务")
        void existingTask_returns200() throws Exception {
            CreateManagementTaskRequest req = buildImportRequest("CTRL-DETAIL");
            ManagementTaskResponse created = managementTaskService.createTask(req, null, null);

            mockMvc.perform(get("/api/manage/tasks/" + created.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(created.getId()))
                    .andExpect(jsonPath("$.data.taskType").value("IMPORT"));
        }

        @Test
        @DisplayName("不存在的任务返回 404")
        void nonExistingTask_returns404() throws Exception {
            mockMvc.perform(get("/api/manage/tasks/99999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value(containsString("任务不存在")));
        }
    }

    // ======================== 任务 items ========================

    @Nested
    @DisplayName("GET /api/manage/tasks/{id}/items")
    class GetTaskItemsTests {

        @Test
        @DisplayName("返回逐目标项列表")
        void returnsItemList() throws Exception {
            CreateManagementTaskRequest req = buildMultiTargetRequest(
                    new String[]{"COMIC", "COMIC"},
                    new Long[]{10001L, 10002L},
                    TaskType.IMPORT);
            ManagementTaskResponse created = managementTaskService.createTask(req, null, null);

            mockMvc.perform(get("/api/manage/tasks/" + created.getId() + "/items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[*].targetType").value(everyItem(is("COMIC"))));
        }
    }

    // ======================== 取消 ========================

    @Nested
    @DisplayName("POST /api/manage/tasks/{id}/cancel")
    class CancelTaskTests {

        @Test
        @DisplayName("QUEUED 任务取消后状态变为 CANCELLING")
        void queuedTask_cancel_returnsCancelling() throws Exception {
            CreateManagementTaskRequest req = buildImportRequest("CTRL-CANCEL");
            ManagementTaskResponse created = managementTaskService.createTask(req, null, null);

            mockMvc.perform(post("/api/manage/tasks/" + created.getId() + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value(
                            anyOf(is("CANCELLING"), is("CANCELLED"))));
        }
    }

    // ======================== 重试 ========================

    @Nested
    @DisplayName("POST /api/manage/tasks/{id}/retry")
    class RetryTaskTests {

        @Test
        @DisplayName("FAILED 任务重试：attempt 递增到 2")
        void failedTask_retry_incrementsAttempt() throws Exception {
            CreateManagementTaskRequest req = buildRequestWithTarget("COMIC", 20001L, TaskType.IMPORT);
            ManagementTaskResponse created = managementTaskService.createTask(req, null, null);

            // 先标记失败
            List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(created.getId());
            managementTaskService.updateItemStatus(items.get(0).getId(),
                    ManagementTaskStatus.FAILED, "test fail", null, null);

            mockMvc.perform(post("/api/manage/tasks/" + created.getId() + "/retry"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.attempt").value(2))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andExpect(jsonPath("$.data.failureCount").value(0));
        }
    }

    // ======================== 辅助方法 ========================

    private CreateManagementTaskRequest buildImportRequest(String batchId) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(TaskType.IMPORT);
        req.setOperation("导入漫画");
        req.setTargetType("COMIC");
        req.setBatchId(batchId);
        req.setTargets(List.of());
        return req;
    }

    private CreateManagementTaskRequest buildExportRequest(String batchId) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(TaskType.EXPORT);
        req.setOperation("导出漫画");
        req.setTargetType("COMIC");
        req.setBatchId(batchId);
        req.setTargets(List.of());
        return req;
    }

    private CreateManagementTaskRequest buildRequestWithTarget(String targetType, Long targetId,
                                                                TaskType operationType) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(operationType);
        req.setOperation("测试操作");
        req.setTargetType(targetType);

        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(targetType);
        target.setTargetId(targetId);
        target.setOperationType(operationType);

        req.setTargets(List.of(target));
        return req;
    }

    private CreateManagementTaskRequest buildMultiTargetRequest(String[] targetTypes,
                                                                  Long[] targetIds,
                                                                  TaskType operationType) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(operationType);
        req.setOperation("批量测试操作");
        req.setTargetType(targetTypes[0]);

        List<CreateManagementTaskRequest.TaskTarget> targets = new java.util.ArrayList<>();
        for (int i = 0; i < targetTypes.length; i++) {
            CreateManagementTaskRequest.TaskTarget t = new CreateManagementTaskRequest.TaskTarget();
            t.setTargetType(targetTypes[i]);
            t.setTargetId(targetIds[i]);
            t.setOperationType(operationType);
            targets.add(t);
        }
        req.setTargets(targets);
        return req;
    }
}
